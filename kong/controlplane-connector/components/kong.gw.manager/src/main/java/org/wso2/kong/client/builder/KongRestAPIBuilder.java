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
        // Check protocol from service - if WebSocket, return false
        KongService svc = data.getService();
        if (svc != null && svc.getProtocol() != null) {
            String protocol = svc.getProtocol().toLowerCase();
            if (protocol.equals("ws") || protocol.equals("wss")) {
                return false; // WebSocket handled by WebSocketAPIBuilder
            }
        }
        // Handles HTTP/HTTPS/gRPC (everything except WebSocket)
        return true;
    }

    @Override
    protected void mapSpecificDetails(API api, KongApiBundle data, Environment environment) throws APIManagementException {
        // Set API type
        api.setType("HTTP");
        
        // Set transports
        api.setTransports("http,https");
        
        // Fetch and set OAS definition if API metadata exists
        if (data.hasApiMetadata() && data.getApi().getApiSpecIds() != null && 
            !data.getApi().getApiSpecIds().isEmpty()) {
            String specId = data.getApi().getApiSpecIds().get(0);
            try {
                KongAPISpec spec = apiGatewayClient.getAPISpec(data.getApi().getId(), specId);
                if (spec != null && spec.getContent() != null) {
                    api.setSwaggerDefinition(spec.getContent());
                }
            } catch (Exception e) {
                // If spec fetch fails, generate from routes
                String generatedOas = KongAPIUtil.buildOasFromRoutes(
                    data.getService(), data.getRoutes(), data.getVhost());
                api.setSwaggerDefinition(generatedOas);
            }
        } else {
            // No API spec - generate OAS from routes
            String generatedOas = KongAPIUtil.buildOasFromRoutes(
                data.getService(), data.getRoutes(), data.getVhost());
            api.setSwaggerDefinition(generatedOas);
        }
        
        // Set endpoint configuration from service
        KongService svc = data.getService();
        if (svc != null) {
            String endpoint = KongAPIUtil.buildEndpointUrl(
                svc.getProtocol(), svc.getHost(), svc.getPort(), svc.getPath());
            api.setEndpointConfig(KongAPIUtil.buildEndpointConfigJson(endpoint, endpoint, false));
        }
        
        // Set default tier
        api.setAvailableTiers(new HashSet<>(Collections.singleton(new Tier(KongConstants.DEFAULT_TIER))));
        
        // Process plugins for CORS and rate limiting
        if (data.getPlugins() != null) {
            String selectedRateLimitPolicy = null;
            
            for (KongPlugin plugin : data.getPlugins()) {
                String pluginType = plugin.getName();
                
                // Handle CORS plugin
                if (KongConstants.KONG_CORS_PLUGIN_TYPE.equals(pluginType)) {
                    api.setCorsConfiguration(KongAPIUtil.kongCorsToWso2Cors(plugin));
                    continue;
                }
                
                // Handle rate limiting (prefer advanced over standard)
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
