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
package org.apache.camel.component.bean;

import java.util.Map;

import org.apache.camel.CamelExecutionException;
import org.apache.camel.ContextTestSupport;
import org.apache.camel.ExpressionEvaluationException;
import org.apache.camel.NoTypeConversionAvailableException;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.JndiRegistry;

/**
 *
 */
public class BeanParameterInvalidValueTest extends ContextTestSupport {

    public void testBeanParameterInvalidValueA() throws Exception {
        getMockEndpoint("mock:result").expectedMessageCount(0);

        try {
            template.sendBody("direct:a", "World");
            fail("Should have thrown exception");
        } catch (CamelExecutionException e) {
            NoTypeConversionAvailableException cause = assertIsInstanceOf(NoTypeConversionAvailableException.class, e.getCause());
            assertEquals(String.class, cause.getFromType());
            assertEquals(int.class, cause.getToType());
            assertEquals("A", cause.getValue());
        }

        assertMockEndpointsSatisfied();
    }

    public void testBeanParameterInvalidValueB() throws Exception {
        getMockEndpoint("mock:result").expectedMessageCount(0);

        try {
            template.sendBody("direct:b", "World");
            fail("Should have thrown exception");
        } catch (CamelExecutionException e) {
            NoTypeConversionAvailableException cause = assertIsInstanceOf(NoTypeConversionAvailableException.class, e.getCause());
            assertEquals(String.class, cause.getFromType());
            assertEquals(int.class, cause.getToType());
            assertEquals("true", cause.getValue());
        }

        assertMockEndpointsSatisfied();
    }

    public void testBeanParameterNullC() throws Exception {
        // should be an empty string
        getMockEndpoint("mock:result").expectedBodiesReceived("");

        template.sendBody("direct:c", "World");

        assertMockEndpointsSatisfied();
    }

    public void testBeanParameterInvalidValueD() throws Exception {
        getMockEndpoint("mock:result").expectedMessageCount(0);

        try {
            template.sendBody("direct:d", "World");
            fail("Should have thrown exception");
        } catch (CamelExecutionException e) {
            ExpressionEvaluationException cause = assertIsInstanceOf(ExpressionEvaluationException.class, e.getCause());
            assertEquals("Illegal syntax: xxx", cause.getCause().getMessage());
        }

        assertMockEndpointsSatisfied();
    }

    @Override
    protected JndiRegistry createRegistry() throws Exception {
        JndiRegistry jndi = super.createRegistry();
        jndi.bind("foo", new MyBean());
        return jndi;
    }

    @Override
    protected RouteBuilder createRouteBuilder() throws Exception {
        return new RouteBuilder() {
            @Override
            public void configure() throws Exception {
                from("direct:a")
                    .to("bean:foo?method=echo(*, 'A')")
                    .to("mock:result");

                from("direct:b")
                    .to("bean:foo?method=echo(*, true)")
                    .to("mock:result");

                from("direct:c")
                    .to("bean:foo?method=echo(null, 2)")
                    .to("mock:result");

                from("direct:d")
                    .to("bean:foo?method=echo(${xxx}, 2)")
                    .to("mock:result");
            }
        };
    }

    public static class MyBean {

        public String echo(String body, int times) {
            if (body == null) {
                // use an empty string for no body
                return "";
            }

            if (times > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < times; i++) {
                    sb.append(body);
                }
                return sb.toString();
            }

            return body;
        }

        public String heads(String body, Map headers) {
            return headers.get("hello") + " " + body;
        }

    }
}
