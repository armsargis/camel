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
package org.apache.camel.itest.ibatis;

import org.apache.camel.CamelExecutionException;
import org.apache.camel.EndpointInject;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.test.junit4.CamelSpringTestSupport;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.context.support.AbstractXmlApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 * @version 
 */
public class TransactedFullExampleTest extends CamelSpringTestSupport {

    private static class MyException extends Exception {
        private static final long serialVersionUID = -221148660533176643L;

        public MyException() {
            super("my exception");
        }
    }

    DummyTable table;

    @EndpointInject(uri = "direct:start")
    ProducerTemplate producer;

    @EndpointInject(uri = "mock:middle")
    private MockEndpoint middle;

    @EndpointInject(uri = "mock:end")
    private MockEndpoint end;

    @EndpointInject(uri = "mock:error1")
    private MockEndpoint error1;

    @EndpointInject(uri = "mock:error2")
    private MockEndpoint error2;

    @Override
    protected AbstractXmlApplicationContext createApplicationContext() {
        return new ClassPathXmlApplicationContext("org/apache/camel/itest/ibatis/TransactedFullExampleTest.xml");
    }

    @Before
    public void createTable() {
        table = context.getRegistry().lookup("table", DummyTable.class);
        table.create();
    }

    @After
    public void dropTable() {
        table.drop();
    }

    @Test
    public void pipelineCompletes() {
        middle.expectedMessageCount(1);
        end.expectedMessageCount(1);
        error1.expectedMessageCount(0);
        error2.expectedMessageCount(0);
        producer.sendBody(3);
        assertEquals(1, table.values().size());
        assertEquals(3, (int) table.iterator().next());
    }

    @Test
    public void pipelineAbortsInMiddle() {
        middle.expectedMessageCount(1);
        middle.whenAnyExchangeReceived(new Processor() {
            public void process(Exchange exchange) throws Exception {
                throw new MyException();
            }
        });
        error1.expectedMessageCount(1);

        try {
            producer.sendBody(3);
            assertFalse(true);
        } catch (Exception e) {
            // ignore
        }
    }

    @Test
    public void pipelineAbortsAtEnd() {
        middle.expectedMessageCount(1);
        end.expectedMessageCount(1);
        end.whenAnyExchangeReceived(new Processor() {
            public void process(Exchange exchange) throws Exception {
                throw new MyException();
            }
        });
        error2.expectedMessageCount(1);

        try {
            producer.sendBody(3);
            assertFalse(true);
        } catch (CamelExecutionException e) {
            assertEquals(MyException.class, e.getCause().getCause().getClass());
            assertEquals(0, table.values().size());
        }
    }

}
