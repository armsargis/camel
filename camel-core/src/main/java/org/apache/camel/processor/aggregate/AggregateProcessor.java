/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.processor.aggregate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.camel.CamelContext;
import org.apache.camel.CamelExchangeException;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.Expression;
import org.apache.camel.Navigate;
import org.apache.camel.NoSuchEndpointException;
import org.apache.camel.Predicate;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.TimeoutMap;
import org.apache.camel.Traceable;
import org.apache.camel.impl.LoggingExceptionHandler;
import org.apache.camel.spi.AggregationRepository;
import org.apache.camel.spi.ExceptionHandler;
import org.apache.camel.spi.RecoverableAggregationRepository;
import org.apache.camel.spi.Synchronization;
import org.apache.camel.support.DefaultTimeoutMap;
import org.apache.camel.support.ServiceSupport;
import org.apache.camel.util.ExchangeHelper;
import org.apache.camel.util.LRUCache;
import org.apache.camel.util.ObjectHelper;
import org.apache.camel.util.ServiceHelper;
import org.apache.camel.util.StopWatch;
import org.apache.camel.util.TimeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An implementation of the <a
 * href="http://camel.apache.org/aggregator2.html">Aggregator</a>
 * pattern where a batch of messages are processed (up to a maximum amount or
 * until some timeout is reached) and messages for the same correlation key are
 * combined together using some kind of {@link AggregationStrategy}
 * (by default the latest message is used) to compress many message exchanges
 * into a smaller number of exchanges.
 * <p/>
 * A good example of this is stock market data; you may be receiving 30,000
 * messages/second and you may want to throttle it right down so that multiple
 * messages for the same stock are combined (or just the latest message is used
 * and older prices are discarded). Another idea is to combine line item messages
 * together into a single invoice message.
 */
public class AggregateProcessor extends ServiceSupport implements Processor, Navigate<Processor>, Traceable {

    public static final String AGGREGATE_TIMEOUT_CHECKER = "AggregateTimeoutChecker";

    private static final Logger LOG = LoggerFactory.getLogger(AggregateProcessor.class);

    private final Lock lock = new ReentrantLock();
    private final CamelContext camelContext;
    private final Processor processor;
    private final AggregationStrategy aggregationStrategy;
    private final Expression correlationExpression;
    private final ExecutorService executorService;
    private ScheduledExecutorService timeoutCheckerExecutorService;    
    private ScheduledExecutorService recoverService;
    // store correlation key -> exchange id in timeout map
    private TimeoutMap<String, String> timeoutMap;
    private ExceptionHandler exceptionHandler = new LoggingExceptionHandler(getClass());
    private AggregationRepository aggregationRepository = new MemoryAggregationRepository();
    private Map<Object, Object> closedCorrelationKeys;
    private Set<String> batchConsumerCorrelationKeys = new LinkedHashSet<String>();
    private final Set<String> inProgressCompleteExchanges = new HashSet<String>();
    private final Map<String, RedeliveryData> redeliveryState = new ConcurrentHashMap<String, RedeliveryData>();

    // keep booking about redelivery
    private class RedeliveryData {
        int redeliveryCounter;
    }

    // options
    private boolean ignoreInvalidCorrelationKeys;
    private Integer closeCorrelationKeyOnCompletion;
    private boolean parallelProcessing;

    // different ways to have completion triggered
    private boolean eagerCheckCompletion;
    private Predicate completionPredicate;
    private long completionTimeout;
    private Expression completionTimeoutExpression;
    private long completionInterval;
    private int completionSize;
    private Expression completionSizeExpression;
    private boolean completionFromBatchConsumer;
    private AtomicInteger batchConsumerCounter = new AtomicInteger();
    private boolean discardOnCompletionTimeout;
    private boolean forceCompletionOnStop;

    private ProducerTemplate deadLetterProducerTemplate;

    public AggregateProcessor(CamelContext camelContext, Processor processor,
                              Expression correlationExpression, AggregationStrategy aggregationStrategy,
                              ExecutorService executorService) {
        ObjectHelper.notNull(camelContext, "camelContext");
        ObjectHelper.notNull(processor, "processor");
        ObjectHelper.notNull(correlationExpression, "correlationExpression");
        ObjectHelper.notNull(aggregationStrategy, "aggregationStrategy");
        ObjectHelper.notNull(executorService, "executorService");
        this.camelContext = camelContext;
        this.processor = processor;
        this.correlationExpression = correlationExpression;
        this.aggregationStrategy = aggregationStrategy;
        this.executorService = executorService;
    }

    @Override
    public String toString() {
        return "AggregateProcessor[to: " + processor + "]";
    }

    public String getTraceLabel() {
        return "aggregate[" + correlationExpression + "]";
    }

    public List<Processor> next() {
        if (!hasNext()) {
            return null;
        }
        List<Processor> answer = new ArrayList<Processor>(1);
        answer.add(processor);
        return answer;
    }

    public boolean hasNext() {
        return processor != null;
    }

    public void process(Exchange exchange) throws Exception {

        //check for the special header to force completion of all groups (and ignore the exchange otherwise)
        boolean completeAllGroups = exchange.getIn().getHeader(Exchange.AGGREGATION_COMPLETE_ALL_GROUPS, false, boolean.class);
        if (completeAllGroups) {
            forceCompletionOfAllGroups();
            return;
        }

        // compute correlation expression
        String key = correlationExpression.evaluate(exchange, String.class);
        if (ObjectHelper.isEmpty(key)) {
            // we have a bad correlation key
            if (isIgnoreInvalidCorrelationKeys()) {
                LOG.debug("Invalid correlation key. This Exchange will be ignored: {}", exchange);
                return;
            } else {
                throw new CamelExchangeException("Invalid correlation key", exchange);
            }
        }

        // is the correlation key closed?
        if (closedCorrelationKeys != null && closedCorrelationKeys.containsKey(key)) {
            throw new ClosedCorrelationKeyException(key, exchange);
        }

        // copy exchange, and do not share the unit of work
        // the aggregated output runs in another unit of work
        Exchange copy = ExchangeHelper.createCorrelatedCopy(exchange, false);

        // when memory based then its fast using synchronized, but if the aggregation repository is IO
        // bound such as JPA etc then concurrent aggregation per correlation key could
        // improve performance as we can run aggregation repository get/add in parallel
        lock.lock();
        try {
            doAggregation(key, copy);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Aggregates the exchange with the given correlation key
     * <p/>
     * This method <b>must</b> be run synchronized as we cannot aggregate the same correlation key
     * in parallel.
     *
     * @param key      the correlation key
     * @param exchange the exchange
     * @return the aggregated exchange
     * @throws org.apache.camel.CamelExchangeException is thrown if error aggregating
     */
    private Exchange doAggregation(String key, Exchange exchange) throws CamelExchangeException {
        LOG.trace("onAggregation +++ start +++ with correlation key: {}", key);

        Exchange answer;
        Exchange oldExchange = aggregationRepository.get(exchange.getContext(), key);
        Exchange newExchange = exchange;

        Integer size = 1;
        if (oldExchange != null) {
            size = oldExchange.getProperty(Exchange.AGGREGATED_SIZE, 0, Integer.class);
            size++;
        }

        // check if we are complete
        String complete = null;
        if (isEagerCheckCompletion()) {
            // put the current aggregated size on the exchange so its avail during completion check
            newExchange.setProperty(Exchange.AGGREGATED_SIZE, size);
            complete = isCompleted(key, newExchange);
            // remove it afterwards
            newExchange.removeProperty(Exchange.AGGREGATED_SIZE);
        }

        // prepare the exchanges for aggregation and aggregate it
        ExchangeHelper.prepareAggregation(oldExchange, newExchange);
        answer = onAggregation(oldExchange, exchange);
        if (answer == null) {
            throw new CamelExchangeException("AggregationStrategy " + aggregationStrategy + " returned null which is not allowed", exchange);
        }

        // update the aggregated size
        answer.setProperty(Exchange.AGGREGATED_SIZE, size);

        // maybe we should check completion after the aggregation
        if (!isEagerCheckCompletion()) {
            complete = isCompleted(key, answer);
        }

        // only need to update aggregation repository if we are not complete
        if (complete == null) {
            LOG.trace("In progress aggregated exchange: {} with correlation key: {}", answer, key);
            aggregationRepository.add(exchange.getContext(), key, answer);
        } else {
            // if batch consumer completion is enabled then we need to complete the group
            if ("consumer".equals(complete)) {
                for (String batchKey : batchConsumerCorrelationKeys) {
                    Exchange batchAnswer;
                    if (batchKey.equals(key)) {
                        // skip the current aggregated key as we have already aggregated it and have the answer
                        batchAnswer = answer;
                    } else {
                        batchAnswer = aggregationRepository.get(camelContext, batchKey);
                    }

                    if (batchAnswer != null) {
                        batchAnswer.setProperty(Exchange.AGGREGATED_COMPLETED_BY, complete);
                        onCompletion(batchKey, batchAnswer, false);
                    }
                }
                batchConsumerCorrelationKeys.clear();
            } else {
                // we are complete for this exchange
                answer.setProperty(Exchange.AGGREGATED_COMPLETED_BY, complete);
                onCompletion(key, answer, false);
            }
        }

        LOG.trace("onAggregation +++  end  +++ with correlation key: {}", key);

        return answer;
    }

    /**
     * Tests whether the given exchange is complete or not
     *
     * @param key      the correlation key
     * @param exchange the incoming exchange
     * @return <tt>null</tt> if not completed, otherwise a String with the type that triggered the completion
     */
    protected String isCompleted(String key, Exchange exchange) {
        if (getCompletionPredicate() != null) {
            boolean answer = getCompletionPredicate().matches(exchange);
            if (answer) {
                return "predicate";
            }
        }

        if (getCompletionSizeExpression() != null) {
            Integer value = getCompletionSizeExpression().evaluate(exchange, Integer.class);
            if (value != null && value > 0) {
                int size = exchange.getProperty(Exchange.AGGREGATED_SIZE, 1, Integer.class);
                if (size >= value) {
                    return "size";
                }
            }
        }
        if (getCompletionSize() > 0) {
            int size = exchange.getProperty(Exchange.AGGREGATED_SIZE, 1, Integer.class);
            if (size >= getCompletionSize()) {
                return "size";
            }
        }

        // timeout can be either evaluated based on an expression or from a fixed value
        // expression takes precedence
        boolean timeoutSet = false;
        if (getCompletionTimeoutExpression() != null) {
            Long value = getCompletionTimeoutExpression().evaluate(exchange, Long.class);
            if (value != null && value > 0) {
                if (LOG.isTraceEnabled()) {
                    LOG.trace("Updating correlation key {} to timeout after {} ms. as exchange received: {}",
                            new Object[]{key, value, exchange});
                }
                addExchangeToTimeoutMap(key, exchange, value);
                timeoutSet = true;
            }
        }
        if (!timeoutSet && getCompletionTimeout() > 0) {
            // timeout is used so use the timeout map to keep an eye on this
            if (LOG.isTraceEnabled()) {
                LOG.trace("Updating correlation key {} to timeout after {} ms. as exchange received: {}",
                        new Object[]{key, getCompletionTimeout(), exchange});
            }
            addExchangeToTimeoutMap(key, exchange, getCompletionTimeout());
        }

        if (isCompletionFromBatchConsumer()) {
            batchConsumerCorrelationKeys.add(key);
            batchConsumerCounter.incrementAndGet();
            int size = exchange.getProperty(Exchange.BATCH_SIZE, 0, Integer.class);
            if (size > 0 && batchConsumerCounter.intValue() >= size) {
                // batch consumer is complete then reset the counter
                batchConsumerCounter.set(0);
                return "consumer";
            }
        }

        // not complete
        return null;
    }

    protected Exchange onAggregation(Exchange oldExchange, Exchange newExchange) {
        return aggregationStrategy.aggregate(oldExchange, newExchange);
    }

    protected void onCompletion(final String key, final Exchange exchange, boolean fromTimeout) {
        // store the correlation key as property
        exchange.setProperty(Exchange.AGGREGATED_CORRELATION_KEY, key);
        // remove from repository as its completed
        aggregationRepository.remove(exchange.getContext(), key, exchange);
        if (!fromTimeout && timeoutMap != null) {
            // cleanup timeout map if it was a incoming exchange which triggered the timeout (and not the timeout checker)
            timeoutMap.remove(key);
        }

        // this key has been closed so add it to the closed map
        if (closedCorrelationKeys != null) {
            closedCorrelationKeys.put(key, key);
        }

        if (fromTimeout && isDiscardOnCompletionTimeout()) {
            // discard due timeout
            LOG.debug("Aggregation for correlation key {} discarding aggregated exchange: ()", key, exchange);
            // must confirm the discarded exchange
            aggregationRepository.confirm(exchange.getContext(), exchange.getExchangeId());
            // and remove redelivery state as well
            redeliveryState.remove(exchange.getExchangeId());
        } else {
            // the aggregated exchange should be published (sent out)
            onSubmitCompletion(key, exchange);
        }
    }

    private void onSubmitCompletion(final Object key, final Exchange exchange) {
        LOG.debug("Aggregation complete for correlation key {} sending aggregated exchange: {}", key, exchange);

        // add this as in progress before we submit the task
        inProgressCompleteExchanges.add(exchange.getExchangeId());

        // send this exchange
        executorService.submit(new Runnable() {
            public void run() {
                LOG.debug("Processing aggregated exchange: {}", exchange);

                // add on completion task so we remember to update the inProgressCompleteExchanges
                exchange.addOnCompletion(new AggregateOnCompletion(exchange.getExchangeId()));

                try {
                    processor.process(exchange);
                } catch (Throwable e) {
                    exchange.setException(e);
                }

                // log exception if there was a problem
                if (exchange.getException() != null) {
                    // if there was an exception then let the exception handler handle it
                    getExceptionHandler().handleException("Error processing aggregated exchange", exchange, exchange.getException());
                } else {
                    LOG.trace("Processing aggregated exchange: {} complete.", exchange);
                }
            }
        });
    }

    /**
     * Restores the timeout map with timeout values from the aggregation repository.
     * <p/>
     * This is needed in case the aggregator has been stopped and started again (for example a server restart).
     * Then the existing exchanges from the {@link AggregationRepository} must have its timeout conditions restored.
     */
    protected void restoreTimeoutMapFromAggregationRepository() throws Exception {
        // grab the timeout value for each partly aggregated exchange
        Set<String> keys = aggregationRepository.getKeys();
        if (keys == null || keys.isEmpty()) {
            return;
        }

        StopWatch watch = new StopWatch();
        LOG.trace("Starting restoring CompletionTimeout for {} existing exchanges from the aggregation repository...", keys.size());

        for (String key : keys) {
            Exchange exchange = aggregationRepository.get(camelContext, key);
            // grab the timeout value
            long timeout = exchange.hasProperties() ? exchange.getProperty(Exchange.AGGREGATED_TIMEOUT, 0, long.class) : 0;
            if (timeout > 0) {
                LOG.trace("Restoring CompletionTimeout for exchangeId: {} with timeout: {} millis.", exchange.getExchangeId(), timeout);
                addExchangeToTimeoutMap(key, exchange, timeout);
            }
        }

        // log duration of this task so end user can see how long it takes to pre-check this upon starting
        LOG.info("Restored {} CompletionTimeout conditions in the AggregationTimeoutChecker in {}",
                timeoutMap.size(), TimeUtils.printDuration(watch.stop()));
    }

    /**
     * Adds the given exchange to the timeout map, which is used by the timeout checker task to trigger timeouts.
     *
     * @param key      the correlation key
     * @param exchange the exchange
     * @param timeout  the timeout value in millis
     */
    private void addExchangeToTimeoutMap(String key, Exchange exchange, long timeout) {
        // store the timeout value on the exchange as well, in case we need it later
        exchange.setProperty(Exchange.AGGREGATED_TIMEOUT, timeout);
        timeoutMap.put(key, exchange.getExchangeId(), timeout);
    }

    public Predicate getCompletionPredicate() {
        return completionPredicate;
    }

    public void setCompletionPredicate(Predicate completionPredicate) {
        this.completionPredicate = completionPredicate;
    }

    public boolean isEagerCheckCompletion() {
        return eagerCheckCompletion;
    }

    public void setEagerCheckCompletion(boolean eagerCheckCompletion) {
        this.eagerCheckCompletion = eagerCheckCompletion;
    }

    public long getCompletionTimeout() {
        return completionTimeout;
    }

    public void setCompletionTimeout(long completionTimeout) {
        this.completionTimeout = completionTimeout;
    }

    public Expression getCompletionTimeoutExpression() {
        return completionTimeoutExpression;
    }

    public void setCompletionTimeoutExpression(Expression completionTimeoutExpression) {
        this.completionTimeoutExpression = completionTimeoutExpression;
    }

    public long getCompletionInterval() {
        return completionInterval;
    }

    public void setCompletionInterval(long completionInterval) {
        this.completionInterval = completionInterval;
    }

    public int getCompletionSize() {
        return completionSize;
    }

    public void setCompletionSize(int completionSize) {
        this.completionSize = completionSize;
    }

    public Expression getCompletionSizeExpression() {
        return completionSizeExpression;
    }

    public void setCompletionSizeExpression(Expression completionSizeExpression) {
        this.completionSizeExpression = completionSizeExpression;
    }

    public boolean isIgnoreInvalidCorrelationKeys() {
        return ignoreInvalidCorrelationKeys;
    }

    public void setIgnoreInvalidCorrelationKeys(boolean ignoreInvalidCorrelationKeys) {
        this.ignoreInvalidCorrelationKeys = ignoreInvalidCorrelationKeys;
    }

    public Integer getCloseCorrelationKeyOnCompletion() {
        return closeCorrelationKeyOnCompletion;
    }

    public void setCloseCorrelationKeyOnCompletion(Integer closeCorrelationKeyOnCompletion) {
        this.closeCorrelationKeyOnCompletion = closeCorrelationKeyOnCompletion;
    }

    public boolean isCompletionFromBatchConsumer() {
        return completionFromBatchConsumer;
    }

    public void setCompletionFromBatchConsumer(boolean completionFromBatchConsumer) {
        this.completionFromBatchConsumer = completionFromBatchConsumer;
    }

    public ExceptionHandler getExceptionHandler() {
        return exceptionHandler;
    }

    public void setExceptionHandler(ExceptionHandler exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
    }

    public boolean isParallelProcessing() {
        return parallelProcessing;
    }

    public void setParallelProcessing(boolean parallelProcessing) {
        this.parallelProcessing = parallelProcessing;
    }

    public AggregationRepository getAggregationRepository() {
        return aggregationRepository;
    }

    public void setAggregationRepository(AggregationRepository aggregationRepository) {
        this.aggregationRepository = aggregationRepository;
    }

    public boolean isDiscardOnCompletionTimeout() {
        return discardOnCompletionTimeout;
    }

    public void setDiscardOnCompletionTimeout(boolean discardOnCompletionTimeout) {
        this.discardOnCompletionTimeout = discardOnCompletionTimeout;
    }

    public void setForceCompletionOnStop(boolean forceCompletionOnStop) {
        this.forceCompletionOnStop = forceCompletionOnStop;
    }

    public void setTimeoutCheckerExecutorService(ScheduledExecutorService timeoutCheckerExecutorService) {
        this.timeoutCheckerExecutorService = timeoutCheckerExecutorService;
    }

    public ScheduledExecutorService getTimeoutCheckerExecutorService() {
        return timeoutCheckerExecutorService;
    }
    
    /**
     * On completion task which keeps the booking of the in progress up to date
     */
    private final class AggregateOnCompletion implements Synchronization {
        private final String exchangeId;

        private AggregateOnCompletion(String exchangeId) {
            // must use the original exchange id as it could potentially change if send over SEDA etc.
            this.exchangeId = exchangeId;
        }

        public void onFailure(Exchange exchange) {
            LOG.trace("Aggregated exchange onFailure: {}", exchange);

            // must remember to remove in progress when we failed
            inProgressCompleteExchanges.remove(exchangeId);
            // do not remove redelivery state as we need it when we redeliver again later
        }

        public void onComplete(Exchange exchange) {
            LOG.trace("Aggregated exchange onComplete: {}", exchange);

            // only confirm if we processed without a problem
            try {
                aggregationRepository.confirm(exchange.getContext(), exchangeId);
                // and remove redelivery state as well
                redeliveryState.remove(exchangeId);
            } finally {
                // must remember to remove in progress when we are complete
                inProgressCompleteExchanges.remove(exchangeId);
            }
        }

        @Override
        public String toString() {
            return "AggregateOnCompletion";
        }
    }

    /**
     * Background task that looks for aggregated exchanges which is triggered by completion timeouts.
     */
    private final class AggregationTimeoutMap extends DefaultTimeoutMap<String, String> {

        private AggregationTimeoutMap(ScheduledExecutorService executor, long requestMapPollTimeMillis) {
            // do NOT use locking on the timeout map as this aggregator has its own shared lock we will use instead
            super(executor, requestMapPollTimeMillis, false);
        }

        @Override
        public void purge() {
            // must acquire the shared aggregation lock to be able to purge
            lock.lock();
            try {
                super.purge();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public boolean onEviction(String key, String exchangeId) {
            log.debug("Completion timeout triggered for correlation key: {}", key);

            boolean inProgress = inProgressCompleteExchanges.contains(exchangeId);
            if (inProgress) {
                LOG.trace("Aggregated exchange with id: {} is already in progress.", exchangeId);
                return true;
            }

            // get the aggregated exchange
            Exchange answer = aggregationRepository.get(camelContext, key);
            if (answer != null) {
                // indicate it was completed by timeout
                answer.setProperty(Exchange.AGGREGATED_COMPLETED_BY, "timeout");
                onCompletion(key, answer, true);
            }
            return true;
        }
    }

    /**
     * Background task that triggers completion based on interval.
     */
    private final class AggregationIntervalTask implements Runnable {

        public void run() {
            // only run if CamelContext has been fully started
            if (!camelContext.getStatus().isStarted()) {
                LOG.trace("Completion interval task cannot start due CamelContext({}) has not been started yet", camelContext.getName());
                return;
            }

            LOG.trace("Starting completion interval task");

            // trigger completion for all in the repository
            Set<String> keys = aggregationRepository.getKeys();

            if (keys != null && !keys.isEmpty()) {
                // must acquire the shared aggregation lock to be able to trigger interval completion
                lock.lock();
                try {
                    for (String key : keys) {
                        Exchange exchange = aggregationRepository.get(camelContext, key);
                        if (exchange != null) {
                            LOG.trace("Completion interval triggered for correlation key: {}", key);
                            // indicate it was completed by interval
                            exchange.setProperty(Exchange.AGGREGATED_COMPLETED_BY, "interval");
                            onCompletion(key, exchange, false);
                        }
                    }
                } finally {
                    lock.unlock();
                }
            }

            LOG.trace("Completion interval task complete");
        }
    }

    /**
     * Background task that looks for aggregated exchanges to recover.
     */
    private final class RecoverTask implements Runnable {
        private final RecoverableAggregationRepository recoverable;

        private RecoverTask(RecoverableAggregationRepository recoverable) {
            this.recoverable = recoverable;
        }

        public void run() {
            // only run if CamelContext has been fully started
            if (!camelContext.getStatus().isStarted()) {
                LOG.trace("Recover check cannot start due CamelContext({}) has not been started yet", camelContext.getName());
                return;
            }

            LOG.trace("Starting recover check");

            Set<String> exchangeIds = recoverable.scan(camelContext);
            for (String exchangeId : exchangeIds) {

                // we may shutdown while doing recovery
                if (!isRunAllowed()) {
                    LOG.info("We are shutting down so stop recovering");
                    return;
                }

                boolean inProgress = inProgressCompleteExchanges.contains(exchangeId);
                if (inProgress) {
                    LOG.trace("Aggregated exchange with id: {} is already in progress.", exchangeId);
                } else {
                    LOG.debug("Loading aggregated exchange with id: {} to be recovered.", exchangeId);
                    Exchange exchange = recoverable.recover(camelContext, exchangeId);
                    if (exchange != null) {
                        // get the correlation key
                        String key = exchange.getProperty(Exchange.AGGREGATED_CORRELATION_KEY, String.class);
                        // and mark it as redelivered
                        exchange.getIn().setHeader(Exchange.REDELIVERED, Boolean.TRUE);

                        // get the current redelivery data
                        RedeliveryData data = redeliveryState.get(exchange.getExchangeId());

                        // if we are exhausted, then move to dead letter channel
                        if (data != null && recoverable.getMaximumRedeliveries() > 0 && data.redeliveryCounter >= recoverable.getMaximumRedeliveries()) {
                            LOG.warn("The recovered exchange is exhausted after " + recoverable.getMaximumRedeliveries()
                                    + " attempts, will now be moved to dead letter channel: " + recoverable.getDeadLetterUri());

                            // send to DLC
                            try {
                                // set redelivery counter
                                exchange.getIn().setHeader(Exchange.REDELIVERY_COUNTER, data.redeliveryCounter);
                                exchange.getIn().setHeader(Exchange.REDELIVERY_EXHAUSTED, Boolean.TRUE);
                                deadLetterProducerTemplate.send(recoverable.getDeadLetterUri(), exchange);
                            } catch (Throwable e) {
                                exchange.setException(e);
                            }

                            // handle if failed
                            if (exchange.getException() != null) {
                                getExceptionHandler().handleException("Failed to move recovered Exchange to dead letter channel: " + recoverable.getDeadLetterUri(), exchange.getException());
                            } else {
                                // it was ok, so confirm after it has been moved to dead letter channel, so we wont recover it again
                                recoverable.confirm(camelContext, exchangeId);
                            }
                        } else {
                            // update current redelivery state
                            if (data == null) {
                                // create new data
                                data = new RedeliveryData();
                                redeliveryState.put(exchange.getExchangeId(), data);
                            }
                            data.redeliveryCounter++;

                            // set redelivery counter
                            exchange.getIn().setHeader(Exchange.REDELIVERY_COUNTER, data.redeliveryCounter);
                            if (recoverable.getMaximumRedeliveries() > 0) {
                                exchange.getIn().setHeader(Exchange.REDELIVERY_MAX_COUNTER, recoverable.getMaximumRedeliveries());
                            }

                            LOG.debug("Delivery attempt: {} to recover aggregated exchange with id: {}", data.redeliveryCounter, exchangeId);

                            // not exhaust so resubmit the recovered exchange
                            onSubmitCompletion(key, exchange);
                        }
                    }
                }
            }

            LOG.trace("Recover check complete");
        }
    }

    @Override
    protected void doStart() throws Exception {
        if (getCompletionTimeout() <= 0 && getCompletionInterval() <= 0 && getCompletionSize() <= 0 && getCompletionPredicate() == null
                && !isCompletionFromBatchConsumer() && getCompletionTimeoutExpression() == null
                && getCompletionSizeExpression() == null) {
            throw new IllegalStateException("At least one of the completions options"
                    + " [completionTimeout, completionInterval, completionSize, completionPredicate, completionFromBatchConsumer] must be set");
        }

        if (getCloseCorrelationKeyOnCompletion() != null) {
            if (getCloseCorrelationKeyOnCompletion() > 0) {
                LOG.info("Using ClosedCorrelationKeys with a LRUCache with a capacity of " + getCloseCorrelationKeyOnCompletion());
                closedCorrelationKeys = new LRUCache<Object, Object>(getCloseCorrelationKeyOnCompletion());
            } else {
                LOG.info("Using ClosedCorrelationKeys with unbounded capacity");
                closedCorrelationKeys = new HashMap<Object, Object>();
            }
        }

        ServiceHelper.startServices(processor, aggregationRepository);

        // should we use recover checker
        if (aggregationRepository instanceof RecoverableAggregationRepository) {
            RecoverableAggregationRepository recoverable = (RecoverableAggregationRepository) aggregationRepository;
            if (recoverable.isUseRecovery()) {
                long interval = recoverable.getRecoveryIntervalInMillis();
                if (interval <= 0) {
                    throw new IllegalArgumentException("AggregationRepository has recovery enabled and the RecoveryInterval option must be a positive number, was: " + interval);
                }

                // create a background recover thread to check every interval
                recoverService = camelContext.getExecutorServiceManager().newScheduledThreadPool(this, "AggregateRecoverChecker", 1);
                Runnable recoverTask = new RecoverTask(recoverable);
                LOG.info("Using RecoverableAggregationRepository by scheduling recover checker to run every " + interval + " millis.");
                // use fixed delay so there is X interval between each run
                recoverService.scheduleWithFixedDelay(recoverTask, 1000L, interval, TimeUnit.MILLISECONDS);

                if (recoverable.getDeadLetterUri() != null) {
                    int max = recoverable.getMaximumRedeliveries();
                    if (max <= 0) {
                        throw new IllegalArgumentException("Option maximumRedeliveries must be a positive number, was: " + max);
                    }
                    LOG.info("After " + max + " failed redelivery attempts Exchanges will be moved to deadLetterUri: " + recoverable.getDeadLetterUri());

                    // dead letter uri must be a valid endpoint
                    Endpoint endpoint = camelContext.getEndpoint(recoverable.getDeadLetterUri());
                    if (endpoint == null) {
                        throw new NoSuchEndpointException(recoverable.getDeadLetterUri());
                    }
                    deadLetterProducerTemplate = camelContext.createProducerTemplate();
                }
            }
        }

        if (getCompletionInterval() > 0 && getCompletionTimeout() > 0) {
            throw new IllegalArgumentException("Only one of completionInterval or completionTimeout can be used, not both.");
        }
        if (getCompletionInterval() > 0) {
            LOG.info("Using CompletionInterval to run every " + getCompletionInterval() + " millis.");
            if (getTimeoutCheckerExecutorService() == null) {
                setTimeoutCheckerExecutorService(camelContext.getExecutorServiceManager().newScheduledThreadPool(this, AGGREGATE_TIMEOUT_CHECKER, 1));
            }
            // trigger completion based on interval
            getTimeoutCheckerExecutorService().scheduleAtFixedRate(new AggregationIntervalTask(), getCompletionInterval(), getCompletionInterval(), TimeUnit.MILLISECONDS);
        }

        // start timeout service if its in use
        if (getCompletionTimeout() > 0 || getCompletionTimeoutExpression() != null) {
            LOG.info("Using CompletionTimeout to trigger after " + getCompletionTimeout() + " millis of inactivity.");
            if (getTimeoutCheckerExecutorService() == null) {
                setTimeoutCheckerExecutorService(camelContext.getExecutorServiceManager().newScheduledThreadPool(this, AGGREGATE_TIMEOUT_CHECKER, 1));
            }
            // check for timed out aggregated messages once every second
            timeoutMap = new AggregationTimeoutMap(getTimeoutCheckerExecutorService(), 1000L);
            // fill in existing timeout values from the aggregation repository, for example if a restart occurred, then we
            // need to re-establish the timeout map so timeout can trigger
            restoreTimeoutMapFromAggregationRepository();
            ServiceHelper.startService(timeoutMap);
        }
    }

    @Override
    protected void doStop() throws Exception {

        if (forceCompletionOnStop) {
            forceCompletionOfAllGroups();

            while (inProgressCompleteExchanges.size() > 0) {
                LOG.trace("waiting for {} in progress exchanges to complete", inProgressCompleteExchanges.size());
                Thread.sleep(100);
            }
        }

        if (recoverService != null) {
            camelContext.getExecutorServiceManager().shutdownNow(recoverService);
        }
        ServiceHelper.stopServices(timeoutMap, processor, deadLetterProducerTemplate);

        if (closedCorrelationKeys != null) {
            // it may be a service so stop it as well
            ServiceHelper.stopService(closedCorrelationKeys);
            closedCorrelationKeys.clear();
        }
        batchConsumerCorrelationKeys.clear();
        redeliveryState.clear();
    }

    @Override
    protected void doShutdown() throws Exception {
        // shutdown aggregation repository
        ServiceHelper.stopService(aggregationRepository);

        // cleanup when shutting down
        inProgressCompleteExchanges.clear();

        super.doShutdown();
    }

    public void forceCompletionOfAllGroups() {

        // only run if CamelContext has been fully started
        if (!camelContext.getStatus().isStarted()) {
            LOG.warn("cannot start force completion because CamelContext({}) has not been started yet", camelContext.getName());
            return;
        }

        LOG.trace("Starting force completion of all groups task");

        // trigger completion for all in the repository
        Set<String> keys = aggregationRepository.getKeys();

        if (keys != null && !keys.isEmpty()) {
            // must acquire the shared aggregation lock to be able to trigger force completion
            lock.lock();
            try {
                for (String key : keys) {
                    Exchange exchange = aggregationRepository.get(camelContext, key);
                    if (exchange != null) {
                        LOG.trace("force completion triggered for correlation key: {}", key);
                        // indicate it was completed by a force completion request
                        exchange.setProperty(Exchange.AGGREGATED_COMPLETED_BY, "forceCompletion");
                        onCompletion(key, exchange, false);
                    }
                }
            } finally {
                lock.unlock();
            }
        }

        LOG.trace("Completed force completion of all groups task");
    }
}
