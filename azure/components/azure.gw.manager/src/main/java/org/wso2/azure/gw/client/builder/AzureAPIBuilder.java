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

import java.util.UUID;

import org.wso2.azure.gw.client.AzureConstants;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.APIIdentifier;
import org.wso2.carbon.apimgt.api.model.Environment;

/**
 * Abstract base class for Azure API builders.
 * Contains common Azure-specific logic that applies to all Azure API types.
 */
public abstract class AzureAPIBuilder {
    
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

    /**
     * Builds a WSO2 API object from the raw external data.
     *
     * @param sourceApi The raw data object from the external gateway.
     * @param env     The environment where the API is discovered.
     * @param org     The organization context.
     * @return The constructed API object.
     * @throws APIManagementException If an error occurs during building.
     */
    public API build(ApiContract sourceApi, Environment env, String org) throws APIManagementException {
        // 1. Basic Identification
        APIIdentifier apiId = new APIIdentifier(org, getName(sourceApi), getVersion(sourceApi));

        API api = new API(apiId);

        // 2. Map Common Properties
        api.setContext(getContext(sourceApi));
        api.setContextTemplate(getContextTemplate(sourceApi));
        api.setUuid(UUID.randomUUID().toString());
        api.setDescription(getDescription(sourceApi));

        // 3. Set Standard WSO2 Flags
        api.setOrganization(org);
        if (env != null) {
            api.setGatewayType(env.getGatewayType());
        }
        api.setInitiatedFromGateway(true);
        api.setRevision(false);
        api.setGatewayVendor("external");

        // 4. Specific Mapping (Delegated to subclasses)
        mapSpecificDetails(api, sourceApi, env);

        return api;
    }
    
    protected String getName(ApiContract sourceApi) {
        return sourceApi.displayName() != null ? sourceApi.displayName() : sourceApi.name();
    }
    
    protected String getVersion(ApiContract sourceApi) {
        return sourceApi.apiVersion() != null ? sourceApi.apiVersion() : AzureConstants.AZURE_DEFAULT_VERSION;
    }
    
    protected String getDescription(ApiContract sourceApi) {
        return sourceApi.description() != null ? sourceApi.description() : "";
    }
    
    protected String getContext(ApiContract sourceApi) {
        String path = sourceApi.path();
        if (path == null || path.isEmpty()) {
            path = AzureConstants.AZURE_NO_CONTEXT;
        }
        String version = getVersion(sourceApi);
        return "/" + path + "/" + version;
    }
    
    protected String getContextTemplate(ApiContract sourceApi) {
        String path = sourceApi.path();
        String context = "/";
        context += (path == null || path.isEmpty()) ? AzureConstants.AZURE_NO_CONTEXT : path;
        return context + "/{version}";
    }

    /**
     * Maps type-specific details (protocol, endpoints, definitions, etc.) to the API object.
     *
     * @param api     The WSO2 API object to populate.
     * @param sourceApi The raw data object.
     * @throws APIManagementException If an error occurs during mapping.
     */
    protected abstract void mapSpecificDetails(API api, ApiContract sourceApi, Environment env) throws APIManagementException;

    /**
     * Checks if this builder can handle the given raw data object.
     *
     * @param sourceApi The raw data object.
     * @return True if this builder can handle the object, false otherwise.
     */
    public abstract boolean canHandle(ApiContract sourceApi);
    
}
