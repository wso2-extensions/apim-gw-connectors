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
    protected String getContext(ApiContract sourceApi) {
        String path = sourceApi.path();
        if (path == null || path.isEmpty()) {
            path = AzureConstants.AZURE_NO_CONTEXT;
        }
        return "/" + path;
    }
    
    /**
     * WebSocket APIs don't include version placeholder in template.
     */
    @Override
    protected String getContextTemplate(ApiContract sourceApi) {
        String context = "/";
        context += sourceApi.path().isEmpty() ? AzureConstants.AZURE_NO_CONTEXT : sourceApi.path();
        return context;
    }

    @Override
    protected void mapSpecificDetails(API api, ApiContract data, Environment environment) throws APIManagementException {
        api.setType(AzureConstants.AZURE_API_TYPE_WEBSOCKET);
        api.setTransports(AzureConstants.AZURE_WEBSOCKET_TRANSPORTS);
        
        String protocol = data.protocols().contains(Protocol.WSS)
                ? AzureConstants.AZURE_PROTOCOL_WSS : AzureConstants.AZURE_PROTOCOL_WS;
        
        String productionUrl = AzureAPIUtil.buildWebSocketProductionUrl(environment, data, protocol);
        String asyncApiDefinition = AzureAPIUtil.loadAsyncApiTemplate(data.displayName(), getVersion(data), productionUrl, protocol);
        api.setAsyncApiDefinition(asyncApiDefinition);
        
        if (data.serviceUrl() != null) {
            api.setEndpointConfig(AzureAPIUtil.buildEndpointConfigJson(
                    data.serviceUrl(), data.serviceUrl(), false, AzureConstants.AZURE_PROTOCOL_WS));
        }
        api.setAvailableTiers(new HashSet<>(java.util.Collections.singleton(new Tier("Unlimited"))));
    }
}
