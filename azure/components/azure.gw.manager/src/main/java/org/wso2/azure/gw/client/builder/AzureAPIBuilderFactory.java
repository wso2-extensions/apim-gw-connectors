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

package org.wso2.azure.gw.client.builder;


import com.azure.core.http.HttpClient;
import com.azure.resourcemanager.apimanagement.ApiManagementManager;
import com.azure.resourcemanager.apimanagement.models.ApiContract;

import java.util.ArrayList;
import java.util.List;

/**
 * Azure-specific implementation of FederatedBuilderFactory.
 * Creates and manages builders for different Azure API types (REST, WebSocket, etc.).
 */
public class AzureAPIBuilderFactory {

    private final List<AzureAPIBuilder> builders;

    /**
     * Constructor initializes the factory with Azure-specific builders.
     *
     * @param manager Azure API Management Manager
     * @param httpClient HTTP client for making Azure API calls
     * @param resourceGroup Azure resource group name
     * @param serviceName Azure API Management service name
     */
    public AzureAPIBuilderFactory(ApiManagementManager manager, HttpClient httpClient,
                               String resourceGroup, String serviceName) {
        this.builders = new ArrayList<>();
        initBuilders(manager, httpClient, resourceGroup, serviceName);
    }

    private void initBuilders(ApiManagementManager manager, HttpClient httpClient,
                           String resourceGroup, String serviceName) {
        registerBuilder(new AzureRestAPIBuilder(manager, httpClient, resourceGroup, serviceName));
        registerBuilder(new AzureWebSocketAPIBuilder(manager, httpClient, resourceGroup, serviceName));
    }
    
    /**
     * @param sourceApi The raw API data from the gateway
     * @return The builder that can handle this API type, or exception if unsupported
     */
    public AzureAPIBuilder getBuilder(ApiContract sourceApi) {
        for (AzureAPIBuilder builder : builders) {
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
    protected void registerBuilder(AzureAPIBuilder builder) {
        if (builder != null) {
            // 1. Check if ANY builder in the list has the same CLASS as the new one
            boolean alreadyExists = false;
            for (AzureAPIBuilder existing : builders) {
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
    public List<AzureAPIBuilder> getRegisteredBuilders() {
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
