package org.wso2.azure.gw.client.builder;


import com.azure.core.http.HttpClient;
import com.azure.resourcemanager.apimanagement.ApiManagementManager;
import com.azure.resourcemanager.apimanagement.models.ApiContract;
import org.wso2.carbon.apimgt.api.FederatedBuilderFactory;

/**
 * Azure-specific implementation of FederatedBuilderFactory.
 * Creates and manages builders for different Azure API types (REST, WebSocket, etc.).
 * 
 * This factory extends the gateway-agnostic FederatedBuilderFactory and registers
 * Azure-specific builders for each API type.
 */
public class AzureAPIBuilderFactory extends FederatedBuilderFactory<ApiContract> {

    /**
     * Constructor initializes the factory with Azure-specific builders.
     * 
     * @param manager Azure API Management Manager
     * @param httpClient HTTP client for making Azure API calls
     * @param resourceGroup Azure resource group name
     * @param serviceName Azure API Management service name
     */
    public AzureAPIBuilderFactory(ApiManagementManager manager, HttpClient httpClient, 
                               String resourceGroup, String serviceName) {
        super(); // Initialize the builders list
        
        // Register Azure-specific builders
        registerBuilder(new AzureRestAPIBuilder(manager, httpClient, resourceGroup, serviceName));
        registerBuilder(new AzureWebSocketAPIBuilder(manager, httpClient, resourceGroup, serviceName));
        
        // Future: Add more Azure API type builders
        // registerBuilder(new AzureGraphQLAPIBuilder(manager, httpClient, resourceGroup, serviceName));
        // registerBuilder(new AzureGrpcAPIBuilder(manager, httpClient, resourceGroup, serviceName));
    }
}
