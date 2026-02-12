package org.wso2.azure.gw.client.builder;

import com.azure.core.http.HttpClient;
import com.azure.resourcemanager.apimanagement.ApiManagementManager;
import com.azure.resourcemanager.apimanagement.models.ApiContract;
import com.azure.resourcemanager.apimanagement.models.ApiType;

import java.util.HashSet;

import org.wso2.azure.gw.client.util.AzureAPIUtil;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.Environment;
import org.wso2.carbon.apimgt.api.model.Tier;

/**
 * Builder for REST/HTTP/SOAP APIs from Azure API Management.
 * Extends AzureAPIBuilder which provides common Azure logic.
 * Only implements API-type-specific logic for REST APIs.
 */
public class AzureRestAPIBuilder extends AzureAPIBuilder {

    public AzureRestAPIBuilder(ApiManagementManager manager, HttpClient httpClient,
                              String resourceGroup, String serviceName) {
        super(manager, httpClient, resourceGroup, serviceName);
    }

    @Override
    public boolean canHandle(ApiContract sourceApi) {
        // Handles all non-WebSocket APIs (HTTP, REST, SOAP, GraphQL, gRPC)
        return sourceApi.apiType() == null || ApiType.HTTP.equals(sourceApi.apiType());
    }

    @Override
    protected void mapSpecificDetails(API api, ApiContract sourceApi, Environment environment) throws APIManagementException {
        // Set API type
        api.setType("HTTP");
        
        // Set transports
        api.setTransports("http,https");
        
        // Get and set OpenAPI/Swagger definition
        String definition = AzureAPIUtil.getRestApiDefinition(manager, httpClient, sourceApi);
        if (definition != null && !definition.isEmpty()) {
            api.setSwaggerDefinition(definition);
        }
        
        // Set endpoint URL if available
        if (sourceApi.serviceUrl() != null) {
            api.setEndpointConfig(AzureAPIUtil.buildEndpointConfigJson(
                    sourceApi.serviceUrl(), sourceApi.serviceUrl(), false, "http"));
        }

    }
}
