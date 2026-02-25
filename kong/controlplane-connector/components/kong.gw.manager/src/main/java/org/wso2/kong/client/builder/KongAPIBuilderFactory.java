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

import java.util.ArrayList;
import java.util.List;

import org.wso2.kong.client.KongKonnectApi;

/**
 * Kong-specific implementation of FederatedBuilderFactory.
 * Creates and manages builders for different Kong API types (REST, WebSocket, gRPC, etc.).
 * 
 * This factory extends the gateway-agnostic FederatedBuilderFactory and registers
 * Kong-specific builders for each API type.
 */
public class KongAPIBuilderFactory {

    private final List<KongAPIBuilder> builders;

    /**
     * Constructor initializes the factory with Kong-specific builders.
     * 
     * @param apiGatewayClient Kong Konnect API client for making API calls
     * @param controlPlaneId Kong control plane ID
     * @param organization WSO2 organization name
     */
    public KongAPIBuilderFactory(KongKonnectApi apiGatewayClient, String controlPlaneId, String organization){
        this.builders = new ArrayList<>();
        initBuilders(apiGatewayClient, controlPlaneId, organization);
    }

    private void initBuilders(KongKonnectApi apiGatewayClient, String controlPlaneId, String organization) {
        registerBuilder(new KongRestAPIBuilder(apiGatewayClient, controlPlaneId, organization));
        registerBuilder(new KongWebSocketAPIBuilder(apiGatewayClient, controlPlaneId, organization));
    }
    
    /**
     * @param sourceApi The raw API data from the gateway
     * @return The builder that can handle this API type, or exception if unsupported
     */
    public KongAPIBuilder getBuilder(KongApiBundle sourceApi) {
        for (KongAPIBuilder builder : builders) {
            if (builder.canHandle(sourceApi)) {
                return builder;
            }
        }
        throw new IllegalStateException(
        "No registered builder can handle the given API data");
    }
    
    /**
     * Registers a builder in the factory.
     * Subclasses can use this to add builders in their constructor.
     * 
     * @param builder The builder to register
     */
    protected void registerBuilder(KongAPIBuilder builder) {
        if (builder != null) {
            // 1. Check if ANY builder in the list has the same CLASS as the new one
            boolean alreadyExists = false;
            for (KongAPIBuilder existing : builders) {
                if (existing.getClass().equals(builder.getClass())) {
                    alreadyExists = true;
                    break;
                }
            }

            if (!alreadyExists) {
                builders.add(builder);
            }
        }
    }
    
    /**
     * Gets all registered builders.
     * Useful for testing and debugging.
     * 
     * @return List of all registered builders
     */
    public List<KongAPIBuilder> getRegisteredBuilders() {
        return new ArrayList<>(builders); // Return a copy for safety
    }
    
    /**
     * Gets the count of registered builders.
     * 
     * @return Number of registered builders
     */
    public int getBuilderCount() {
        return builders.size();
    }

}
