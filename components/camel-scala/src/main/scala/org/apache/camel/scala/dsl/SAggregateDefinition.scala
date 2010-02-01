/*
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
package org.apache.camel.scala.dsl;

import org.apache.camel.model.AggregateDefinition
import org.apache.camel.processor.aggregate.AggregationStrategy
import org.apache.camel.scala.dsl.builder.RouteBuilder
import org.apache.camel.Exchange
import org.apache.camel.scala.Period

/**
 * Scala wrapper for Camel AggregateDefinition
 */
case class SAggregateDefinition(override val target: AggregateDefinition)(implicit val builder: RouteBuilder) extends SAbstractDefinition[AggregateDefinition] {
  
  def strategy(function: (Exchange, Exchange) => Exchange) = {
    target.setAggregationStrategy(
      new AggregationStrategy() {
        def aggregate(oldExchange: Exchange, newExchange: Exchange) = function(oldExchange, newExchange)
      }
    )
    this
  }

  def strategy(strategy: AggregationStrategy) = wrap(target.setAggregationStrategy(strategy))
  def strategy(ref: String) = wrap(target.setStrategyRef(ref));

  def batchTimout(period: Period) = wrap(target.setBatchTimeout(period.milliseconds))
  def completion(predicate: Exchange => Any) = wrap(target.completionPredicate(predicate))

  def batchSize(count: Int) = wrap(target.batchSize(count))
  def outBatchSize(count: Int) = wrap(target.outBatchSize(count))

  def groupExchanges = wrap(target.groupExchanges)
  def batchSizeFromConsumer = wrap(target.batchSizeFromConsumer)

  override def wrap(block: => Unit) = super.wrap(block).asInstanceOf[SAggregateDefinition]
}