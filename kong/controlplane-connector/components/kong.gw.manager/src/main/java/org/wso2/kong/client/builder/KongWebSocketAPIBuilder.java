package org.wso2.kong.client.builder;

import java.util.Collections;
import java.util.HashSet;

import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.Environment;
import org.wso2.carbon.apimgt.api.model.Tier;
import org.wso2.kong.client.KongConstants;
import org.wso2.kong.client.KongKonnectApi;
import org.wso2.kong.client.model.KongPlugin;
import org.wso2.kong.client.model.KongService;
import org.wso2.kong.client.util.KongAPIUtil;

/**
 * Builder for WebSocket APIs from Kong Gateway.
 * Extends KongAPIBuilder which provides common Kong logic.
 * Only implements WebSocket-specific logic.
 */
public class KongWebSocketAPIBuilder extends KongAPIBuilder {
    
    public KongWebSocketAPIBuilder(KongKonnectApi apiGatewayClient, String controlPlaneId, String organization) {
        super(apiGatewayClient, controlPlaneId, organization);
    }

    @Override
    public boolean canHandle(KongApiBundle data) {
        // Check protocol from service
        KongService svc = data.getService();
        if (svc != null && svc.getProtocol() != null) {
            String protocol = svc.getProtocol().toLowerCase();
            return protocol.equals("ws") || protocol.equals("wss");
        }
        return false;
    }

    @Override
    protected void mapSpecificDetails(API api, KongApiBundle data, Environment environment) throws APIManagementException {
        // Set API type to WebSocket
        api.setType("WS");
        
        // Set WebSocket transports
        api.setTransports("ws,wss");
        
        // Generate synthetic AsyncAPI definition for WebSocket
        KongService svc = data.getService();
        String asyncApiDef = KongAPIUtil.buildDefinitionFromRoutesForAsync(
            data.getService(), data.getRoutes(), data.getVhost());
        api.setAsyncApiDefinition(asyncApiDef);
        
        // Set WebSocket endpoint configuration
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
                
                // Handle rate limiting
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
