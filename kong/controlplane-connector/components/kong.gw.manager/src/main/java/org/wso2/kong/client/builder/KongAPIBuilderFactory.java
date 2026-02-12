package org.wso2.kong.client.builder;

import org.wso2.carbon.apimgt.api.FederatedBuilderFactory;
import org.wso2.kong.client.KongKonnectApi;

/**
 * Kong-specific implementation of FederatedBuilderFactory.
 * Creates and manages builders for different Kong API types (REST, WebSocket, gRPC, etc.).
 * 
 * This factory extends the gateway-agnostic FederatedBuilderFactory and registers
 * Kong-specific builders for each API type.
 */
public class KongAPIBuilderFactory extends FederatedBuilderFactory<KongApiBundle> {
    
    /**
     * Constructor initializes the factory with Kong-specific builders.
     * 
     * @param apiGatewayClient Kong Konnect API client for making API calls
     * @param controlPlaneId Kong control plane ID
     * @param organization WSO2 organization name
     */
    public KongAPIBuilderFactory(KongKonnectApi apiGatewayClient, String controlPlaneId, String organization) {
        super(); // Initialize the builders list
        
        // Register Kong-specific builders
        registerBuilder(new KongRestAPIBuilder(apiGatewayClient, controlPlaneId, organization));
        registerBuilder(new KongWebSocketAPIBuilder(apiGatewayClient, controlPlaneId, organization));
        
        // Future: Add more Kong API type builders
        // registerBuilder(new KongGrpcAPIBuilder(apiGatewayClient, controlPlaneId, organization));
        // registerBuilder(new KongGraphQLAPIBuilder(apiGatewayClient, controlPlaneId, organization));
    }

}
