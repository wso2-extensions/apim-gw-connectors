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

import org.wso2.carbon.apimgt.api.FederatedAPIBuilder;
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
public abstract class KongAPIBuilder extends FederatedAPIBuilder<KongApiBundle> {
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
    
    // ========== Common Kong Implementations ==========
    
    @Override
    protected String getName(KongApiBundle sourceApi) {
        // Prefer API metadata name, fallback to service name
        if (sourceApi.hasApiMetadata()) {
            return sourceApi.getApi().getName();
        }
        return sourceApi.getService().getName();
    }
    
    @Override
    protected String getVersion(KongApiBundle sourceApi) {
        // Prefer API metadata version, fallback to default
        if (sourceApi.hasApiMetadata() && sourceApi.getApi().getVersion() != null) {
            return sourceApi.getApi().getVersion();
        }
        return KongConstants.DEFAULT_API_VERSION;
    }
    
    @Override
    protected String getContext(KongApiBundle sourceApi) {
        // Prefer API slug, fallback to service name
        if (sourceApi.hasApiMetadata() && sourceApi.getApi().getSlug() != null) {
            return KongAPIUtil.ensureLeadingSlash(sourceApi.getApi().getSlug());
        }
        return KongAPIUtil.ensureLeadingSlash(sourceApi.getService().getName());
    }
    
    @Override
    protected String getContextTemplate(KongApiBundle sourceApi) {
        String context = getContext(sourceApi);
        // Remove leading slash for template
        String template = context.startsWith("/") ? context.substring(1) : context;
        return template.toLowerCase().replace(" ", "-");
    }
    
    @Override
    protected String getGatewayId(KongApiBundle sourceApi) {
        // Prefer API ID, fallback to service ID
        if (sourceApi.hasApiMetadata()) {
            return sourceApi.getApi().getId();
        }
        return sourceApi.getService().getId();
    }
    
    @Override
    protected String getDescription(KongApiBundle sourceApi) {
        // Prefer API description, fallback to empty
        if (sourceApi.hasApiMetadata() && sourceApi.getApi().getDescription() != null) {
            return sourceApi.getApi().getDescription();
        }
        return "";
    }
    
    // ========== Abstract Methods ==========
    // canHandle() - already abstract in FederatedAPIBuilder
    // mapSpecificDetails() - already abstract in FederatedAPIBuilder
}
