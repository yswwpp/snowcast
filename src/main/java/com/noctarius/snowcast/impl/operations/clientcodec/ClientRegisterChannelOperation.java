/*
 * Copyright (c) 2015-2017, Christoph Engelbert (aka noctarius) and
 * contributors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.noctarius.snowcast.impl.operations.clientcodec;

import com.hazelcast.spi.EventRegistration;
import com.noctarius.snowcast.impl.NodeSequencerService;

 class ClientRegisterChannelOperation
        extends AbstractClientRequestOperation {

    private String registrationId;

     ClientRegisterChannelOperation(String sequencerName, MessageChannel messageChannel) {
        super(sequencerName, messageChannel);
    }

    @Override
    public void run() {
        NodeSequencerService sequencerService = getService();
        EventRegistration registration = sequencerService.registerClientChannel(getSequencerName(), getMessageChannel());
        registrationId = registration.getId();
    }

    @Override
    public Object getResponse() {
        return registrationId;
    }
}
