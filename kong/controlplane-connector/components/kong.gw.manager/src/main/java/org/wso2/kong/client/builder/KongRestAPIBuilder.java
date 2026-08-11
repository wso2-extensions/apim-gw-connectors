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

import java.util.Collections;
import java.util.HashSet;

import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.Environment;
import org.wso2.carbon.apimgt.api.model.Tier;
import org.wso2.kong.client.KongConstants;
import org.wso2.kong.client.KongKonnectApi;
import org.wso2.kong.client.model.KongAPISpec;
import org.wso2.kong.client.model.KongPlugin;
import org.wso2.kong.client.model.KongService;
import org.wso2.kong.client.util.KongAPIUtil;

/**
 * Builder for REST/HTTP/SOAP APIs from Kong Gateway.
 * Extends KongAPIBuilder which provides common Kong logic.
 * Only implements API-type-specific logic for REST APIs.
 */
public class KongRestAPIBuilder extends KongAPIBuilder {
    
    public KongRestAPIBuilder(KongKonnectApi apiGatewayClient, String controlPlaneId, String organization) {
        super(apiGatewayClient, controlPlaneId, organization);
    }

    @Override
    public boolean canHandle(KongApiBundle data) {
        KongService svc = data.getService();
        if (svc != null && svc.getProtocol() != null) {
            String protocol = svc.getProtocol().toLowerCase();
            if (protocol.equals(KongConstants.PROTOCOL_WS) || protocol.equals(KongConstants.PROTOCOL_WSS)) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void mapSpecificDetails(API api, KongApiBundle data, Environment environment) throws APIManagementException {
        api.setType(KongConstants.API_TYPE_HTTP);
        api.setTransports(KongConstants.TRANSPORT_HTTP);
        
        if (data.hasApiMetadata() && data.getApi().getApiSpecIds() != null && 
            !data.getApi().getApiSpecIds().isEmpty()) {
            String specId = data.getApi().getApiSpecIds().get(0);
            try {
                KongAPISpec spec = apiGatewayClient.getAPISpec(data.getApi().getId(), specId);
                if (spec != null && spec.getContent() != null) {
                    api.setSwaggerDefinition(spec.getContent());
                }
            } catch (Exception e) {
                String generatedOas = KongAPIUtil.buildOasFromRoutes(
                    data.getService(), data.getRoutes(), data.getVhost());
                api.setSwaggerDefinition(generatedOas);
            }
        } else {
            String generatedOas = KongAPIUtil.buildOasFromRoutes(
                data.getService(), data.getRoutes(), data.getVhost());
            api.setSwaggerDefinition(generatedOas);
        }
        
        KongService svc = data.getService();
        if (svc != null) {
            String endpoint = KongAPIUtil.buildEndpointUrl(
                svc.getProtocol(), svc.getHost(), svc.getPort(), svc.getPath());
            api.setEndpointConfig(KongAPIUtil.buildEndpointConfigJson(endpoint, endpoint, false));
        }
        
        api.setAvailableTiers(new HashSet<>(Collections.singleton(new Tier(KongConstants.DEFAULT_TIER))));
        
        if (data.getPlugins() != null) {
            String selectedRateLimitPolicy = null;
            
            for (KongPlugin plugin : data.getPlugins()) {
                String pluginType = plugin.getName();
                
                if (KongConstants.KONG_CORS_PLUGIN_TYPE.equals(pluginType)) {
                    api.setCorsConfiguration(KongAPIUtil.kongCorsToWso2Cors(plugin));
                    continue;
                }
                
                if (KongConstants.KONG_RATELIMIT_ADVANCED_PLUGIN_TYPE.equals(pluginType) 
                    && selectedRateLimitPolicy == null) {
                    String policy = KongAPIUtil.kongRateLimitingToWso2Policy(plugin);
                    if (policy != null) {
                        selectedRateLimitPolicy = policy;
                    }
                    continue;
                }
                
                if (KongConstants.KONG_RATELIMIT_PLUGIN_TYPE.equals(pluginType) 
                    && selectedRateLimitPolicy == null) {
                    String policy = KongAPIUtil.kongRateLimitingStandardToWso2Policy(plugin);
                    if (policy != null) {
                        selectedRateLimitPolicy = policy;
                    }
                }
            }
            
            if (selectedRateLimitPolicy != null) {
                api.setApiLevelPolicy(selectedRateLimitPolicy);
            }
        }
    }
}
