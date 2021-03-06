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
package org.apache.camel.component.ssh;

import org.apache.camel.CamelExchangeException;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.impl.DefaultProducer;

public class SshProducer extends DefaultProducer {
    private SshEndpoint endpoint;

    public SshProducer(SshEndpoint endpoint) {
        super(endpoint);
        this.endpoint = endpoint;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        final Message in = exchange.getIn();
        String command = in.getMandatoryBody(String.class);

        try {
            byte[] result = endpoint.sendExecCommand(command);
            exchange.getOut().setBody(result);
        } catch (Exception e) {
            throw new CamelExchangeException("Cannot execute command: " + command, exchange, e);
        }

        // propagate headers and attachments
        exchange.getOut().getHeaders().putAll(in.getHeaders());
        exchange.getOut().setAttachments(in.getAttachments());
    }
}