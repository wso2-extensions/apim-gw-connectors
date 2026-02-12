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
import org.wso2.carbon.apimgt.api.FederatedBuilderFactory;

/**
 * Azure-specific implementation of FederatedBuilderFactory.
 * Creates and manages builders for different Azure API types (REST, WebSocket, etc.).
 * 
 * This factory extends the gateway-agnostic FederatedBuilderFactory and registers
 * Azure-specific builders for each API type.
 */
public class AzureAPIBuilderFactory extends FederatedBuilderFactory<ApiContract> {

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
        super();
        
        registerBuilder(new AzureRestAPIBuilder(manager, httpClient, resourceGroup, serviceName));
        registerBuilder(new AzureWebSocketAPIBuilder(manager, httpClient, resourceGroup, serviceName));
        
    }
}
