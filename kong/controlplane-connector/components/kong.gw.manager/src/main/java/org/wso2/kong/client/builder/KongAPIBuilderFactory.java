/*
 * Copyright (c) 2026 WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
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

package org.wso2.kong.client.builder;

import org.wso2.carbon.apimgt.api.FederatedBuilderFactory;
import org.wso2.kong.client.KongKonnectApi;

/**
 * Kong-specific implementation of FederatedBuilderFactory.
 * Creates and manages builders for different Kong API types (REST, WebSocket, gRPC, etc.).
 * 
 * This factory extends the gateway-agnostic FederatedBuilderFactory and registers
 * Kong-specific builders for each API type.
 */
public class KongAPIBuilderFactory extends FederatedBuilderFactory<KongApiBundle> {
    
    /**
     * Constructor initializes the factory with Kong-specific builders.
     * 
     * @param apiGatewayClient Kong Konnect API client for making API calls
     * @param controlPlaneId Kong control plane ID
     * @param organization WSO2 organization name
     */
    public KongAPIBuilderFactory(KongKonnectApi apiGatewayClient, String controlPlaneId, String organization) {
        super(); // Initialize the builders list
        
        // Register Kong-specific builders
        registerBuilder(new KongRestAPIBuilder(apiGatewayClient, controlPlaneId, organization));
        registerBuilder(new KongWebSocketAPIBuilder(apiGatewayClient, controlPlaneId, organization));
        
        // Future: Add more Kong API type builders
        // registerBuilder(new KongGrpcAPIBuilder(apiGatewayClient, controlPlaneId, organization));
        // registerBuilder(new KongGraphQLAPIBuilder(apiGatewayClient, controlPlaneId, organization));
    }

}
