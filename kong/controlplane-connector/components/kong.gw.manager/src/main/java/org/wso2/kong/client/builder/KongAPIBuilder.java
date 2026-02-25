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

import java.util.UUID;

import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.APIIdentifier;
import org.wso2.carbon.apimgt.api.model.Environment;
import org.wso2.kong.client.KongConstants;
import org.wso2.kong.client.KongKonnectApi;
import org.wso2.kong.client.util.KongAPIUtil;

/**
 * Abstract base class for Kong API builders.
 * Contains common Kong-specific logic that applies to all Kong API types.
 * 
 * Subclasses (REST, WebSocket, gRPC, etc.) only need to implement:
 * - canHandle(): Which API type they support
 * - mapSpecificDetails(): Type-specific mapping logic
 */
public abstract class KongAPIBuilder {
    protected KongKonnectApi apiGatewayClient;
    protected String controlPlaneId;
    protected String organization;
    
    /**
     * Constructor with Kong-specific dependencies.
     * All Kong builders need these common dependencies.
     */
    public KongAPIBuilder(KongKonnectApi apiGatewayClient, String controlPlaneId, String organization) {
        this.apiGatewayClient = apiGatewayClient;
        this.controlPlaneId = controlPlaneId;
        this.organization = organization;
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
    public API build(KongApiBundle sourceApi, Environment env, String org) throws APIManagementException {
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
    
    protected String getName(KongApiBundle sourceApi) {
        if (sourceApi.hasApiMetadata()) {
            return sourceApi.getApi().getName();
        }
        return sourceApi.getService().getName();
    }
    
    protected String getVersion(KongApiBundle sourceApi) {
        if (sourceApi.hasApiMetadata() && sourceApi.getApi().getVersion() != null) {
            return sourceApi.getApi().getVersion();
        }
        return KongConstants.DEFAULT_API_VERSION;
    }
    
    protected String getContext(KongApiBundle sourceApi) {
        if (sourceApi.hasApiMetadata() && sourceApi.getApi().getSlug() != null) {
            return KongAPIUtil.ensureLeadingSlash(sourceApi.getApi().getSlug());
        }
        return KongAPIUtil.ensureLeadingSlash(sourceApi.getService().getName());
    }
    
    protected String getContextTemplate(KongApiBundle sourceApi) {
        String context = getContext(sourceApi);
        String template = context.startsWith("/") ? context.substring(1) : context;
        return template.toLowerCase().replace(" ", "-");
    }
    
    protected String getGatewayId(KongApiBundle sourceApi) {
        if (sourceApi.hasApiMetadata()) {
            return sourceApi.getApi().getId();
        }
        return sourceApi.getService().getId();
    }

    protected String getDescription(KongApiBundle sourceApi) {
        if (sourceApi.hasApiMetadata() && sourceApi.getApi().getDescription() != null) {
            return sourceApi.getApi().getDescription();
        }
        return "";
    }

    /**
     * Maps type-specific details (protocol, endpoints, definitions, etc.) to the API object.
     *
     * @param api     The WSO2 API object to populate.
     * @param sourceApi The raw data object.
     * @throws APIManagementException If an error occurs during mapping.
     */
    protected abstract void mapSpecificDetails(API api, KongApiBundle sourceApi, Environment env) throws APIManagementException;

    /**
     * Checks if this builder can handle the given raw data object.
     *
     * @param sourceApi The raw data object.
     * @return True if this builder can handle the object, false otherwise.
     */
    public abstract boolean canHandle(KongApiBundle sourceApi);

}
