/*
 * Copyright (c) 2025 WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.kong.client.model;

/**
 * Request body for adding a consumer to a Kong Consumer Group.
 * Maps to: POST /v2/control-planes/{cpId}/core-entities/consumer_groups/{groupId}/consumers
 * Body: {"consumer": "consumer-uuid"}
 */
public class KongConsumerGroupMembership {

    private String consumer;

    public KongConsumerGroupMembership() {
    }

    public KongConsumerGroupMembership(String consumerId) {
        this.consumer = consumerId;
    }

    public String getConsumer() {
        return consumer;
    }

    public void setConsumer(String consumer) {
        this.consumer = consumer;
    }
}
