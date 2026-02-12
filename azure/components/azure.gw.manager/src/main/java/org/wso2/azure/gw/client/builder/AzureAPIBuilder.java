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

package org.wso2.azure.gw.client.builder;

import com.azure.core.http.HttpClient;
import com.azure.resourcemanager.apimanagement.ApiManagementManager;
import com.azure.resourcemanager.apimanagement.models.ApiContract;

import org.wso2.azure.gw.client.AzureConstants;
import org.wso2.carbon.apimgt.api.FederatedAPIBuilder;

/**
 * Abstract base class for Azure API builders.
 * Contains common Azure-specific logic that applies to all Azure API types.
 * 
 * Subclasses (REST, WebSocket, GraphQL, etc.) only need to implement:
 * - canHandle(): Which API type they support
 * - mapSpecificDetails(): Type-specific mapping logic
 * - getContext(): If they need custom context path logic
 * - getContextTemplate(): If they need custom context template logic
 */
public abstract class AzureAPIBuilder extends FederatedAPIBuilder<ApiContract> {
    
    protected ApiManagementManager manager;
    protected HttpClient httpClient;
    protected String resourceGroup;
    protected String serviceName;
    
    /**
     * Constructor with Azure-specific dependencies.
     * All Azure builders need these common dependencies.
     */
    public AzureAPIBuilder(ApiManagementManager manager, HttpClient httpClient,
                          String resourceGroup, String serviceName) {
        this.manager = manager;
        this.httpClient = httpClient;
        this.resourceGroup = resourceGroup;
        this.serviceName = serviceName;
    }
    
    // ========== Common Azure Implementations ==========
    // These are the same for all Azure API types
    
    @Override
    protected String getName(ApiContract sourceApi) {
        return sourceApi.displayName() != null ? sourceApi.displayName() : sourceApi.name();
    }
    
    @Override
    protected String getVersion(ApiContract sourceApi) {
        return sourceApi.apiVersion() != null ? sourceApi.apiVersion() : "1.0.0";
    }
    
    @Override
    protected String getGatewayId(ApiContract sourceApi) {
        return sourceApi.name(); // Azure API name is the unique identifier
    }
    
    @Override
    protected String getDescription(ApiContract sourceApi) {
        return sourceApi.description() != null ? sourceApi.description() : "";
    }
    
    /**
     * Default context implementation includes version.
     * Subclasses can override if they need different logic (e.g., WebSocket).
     */
    @Override
    protected String getContext(ApiContract sourceApi) {
        String path = sourceApi.path();
        if (path == null || path.isEmpty()) {
            path = AzureConstants.AZURE_NO_CONTEXT;
        }
        String version = getVersion(sourceApi);
        return "/" + path + "/" + version;
    }
    
    /**
     * Default context template includes version placeholder.
     * Subclasses can override if needed.
     */
    @Override
    protected String getContextTemplate(ApiContract sourceApi) {
        String context = "/";
        context += sourceApi.path().isEmpty() ? AzureConstants.AZURE_NO_CONTEXT : sourceApi.path();
        return context + "/{version}";
    }
    
    // ========== Abstract Methods ==========
    // Subclasses must implement these for API-type-specific logic
    
    // canHandle() - already abstract in FederatedAPIBuilder
    // mapSpecificDetails() - already abstract in FederatedAPIBuilder
}
