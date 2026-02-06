package org.wso2.azure.gw.client.builder;

import com.azure.core.http.HttpClient;
import com.azure.resourcemanager.apimanagement.ApiManagementManager;
import com.azure.resourcemanager.apimanagement.models.ApiContract;
import com.azure.resourcemanager.apimanagement.models.ApiType;
import com.azure.resourcemanager.apimanagement.models.Protocol;

import java.util.HashSet;

import org.wso2.azure.gw.client.AzureConstants;
import org.wso2.azure.gw.client.util.AzureAPIUtil;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.Environment;
import org.wso2.carbon.apimgt.api.model.Tier;

import com.google.gson.JsonObject;

/**
 * Builder for WebSocket APIs from Azure API Management.
 * Extends AzureAPIBuilder which provides common Azure logic.
 * Overrides getContext() to exclude version from WebSocket paths.
 */
public class AzureWebSocketAPIBuilder extends AzureAPIBuilder {

    public AzureWebSocketAPIBuilder(ApiManagementManager manager, HttpClient httpClient,
                                   String resourceGroup, String serviceName) {
        super(manager, httpClient, resourceGroup, serviceName);
    }

    @Override
    public boolean canHandle(ApiContract data) {
        return ApiType.WEBSOCKET.equals(data.apiType());
    }
    
    /**
     * WebSocket APIs don't include version in the context path.
     * Overrides the default Azure implementation.
     */
    @Override
    protected String getContext(ApiContract rawData) {
        String path = rawData.path();
        if (path == null || path.isEmpty()) {
            path = AzureConstants.AZURE_NO_CONTEXT;
        }
        // WebSocket doesn't include version
        return "/" + path;
    }
    
    /**
     * WebSocket APIs don't include version placeholder in template.
     */
    @Override
    protected String getContextTemplate(ApiContract rawData) {
        String context = "/";
        context += rawData.path().isEmpty() ? AzureConstants.AZURE_NO_CONTEXT : rawData.path();
        // No version placeholder for WebSocket
        return context;
    }

    @Override
    protected void mapSpecificDetails(API api, ApiContract data, Environment environment) throws APIManagementException {
        // Set API type to WebSocket
        api.setType("WS");
        
        // Set WebSocket transports
        api.setTransports("ws,wss");
        
        // Generate synthetic AsyncAPI definition for WebSocket
        String protocol = data.protocols().contains(Protocol.WSS) ? "wss" : "ws";
        
        // Build production URL for AsyncAPI definition
        String productionUrl = AzureAPIUtil.buildWebSocketProductionUrl(environment, data, protocol);
        String asyncApiDefinition = AzureAPIUtil.loadAsyncApiTemplate(data.displayName(), getVersion(data), productionUrl, protocol);
        api.setAsyncApiDefinition(asyncApiDefinition);
        
        // Set WebSocket endpoint URL if available
        if (data.serviceUrl() != null) {
            api.setEndpointConfig(AzureAPIUtil.buildEndpointConfigJson(
                    data.serviceUrl(), data.serviceUrl(), false, "ws"));
        }
        api.setAvailableTiers(new HashSet<>(java.util.Collections.singleton(new Tier("Unlimited"))));
    }
}
