/*
 *
 * Copyright (c) 2025 WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
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
 *
 */

package org.wso2.aws.client;

import com.google.gson.JsonObject;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.aws.client.builder.AWSAPIBuilderFactory;
import org.wso2.aws.client.discovery.AWSDiscoveryService;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.FederatedAPIDiscovery;
import org.wso2.carbon.apimgt.api.model.DiscoveredAPI;
import org.wso2.carbon.apimgt.api.model.Environment;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client;

import java.util.ArrayList;
import java.util.List;

import static org.wso2.carbon.apimgt.impl.importexport.ImportExportConstants.DEPLOYMENT_NAME;
import static org.wso2.carbon.apimgt.impl.importexport.ImportExportConstants.DEPLOYMENT_VHOST;
import static org.wso2.carbon.apimgt.impl.importexport.ImportExportConstants.DISPLAY_ON_DEVPORTAL_OPTION;

/**
 * Represents the federated API discovery implementation for AWS API Gateway.
 * This class is responsible for discovering REST APIs (V1) and WebSocket APIs (V2)
 * deployed on AWS API Gateway and integrating them with the API management system.
 *
 * <p>Uses the builder factory pattern to delegate API construction to type-specific
 * builders ({@link org.wso2.aws.client.builder.AWSRestAPIBuilder} for REST,
 * {@link org.wso2.aws.client.builder.AWSWebSocketAPIBuilder} for WebSocket).
 *
 * <p>REST APIs are discovered via API Gateway V1 ({@link ApiGatewayClient})
 * and WebSocket APIs via API Gateway V2 ({@link ApiGatewayV2Client}).
 */
public class AWSFederatedAPIDiscovery implements FederatedAPIDiscovery {

    private static final Log log = LogFactory.getLog(AWSFederatedAPIDiscovery.class);

    private Environment environment;
    private ApiGatewayClient apiGatewayClient;
    private ApiGatewayV2Client apiGatewayV2Client;
    private String organization;
    private String region;
    private String stage;
    private JsonObject deploymentConfigObject;

    @Override
    public void init(Environment environment, String organization)
            throws APIManagementException {
        log.debug("Initializing AWS Gateway Deployer for environment: " + environment.getName());
        try {
            this.environment = environment;
            this.organization = organization;
            this.region = environment.getAdditionalProperties().get(AWSConstants.AWS_ENVIRONMENT_REGION);
            this.stage = environment.getAdditionalProperties().get(AWSConstants.AWS_API_STAGE);

            String accessKey = environment.getAdditionalProperties().get(AWSConstants.AWS_ENVIRONMENT_ACCESS_KEY);
            String secretKey = environment.getAdditionalProperties().get(AWSConstants.AWS_ENVIRONMENT_SECRET_KEY);

            if (region == null || accessKey == null || secretKey == null) {
                throw new APIManagementException("Missing required AWS environment configurations");
            }

            SdkHttpClient httpClient = ApacheHttpClient.builder().build();
            StaticCredentialsProvider credentialsProvider = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey));

            this.apiGatewayClient = ApiGatewayClient.builder()
                    .region(Region.of(region))
                    .httpClient(httpClient)
                    .credentialsProvider(credentialsProvider)
                    .build();

            this.apiGatewayV2Client = ApiGatewayV2Client.builder()
                    .region(Region.of(region))
                    .httpClient(httpClient)
                    .credentialsProvider(credentialsProvider)
                    .build();

            this.deploymentConfigObject = new JsonObject();
            deploymentConfigObject.addProperty(DEPLOYMENT_NAME, environment.getName());
            deploymentConfigObject.addProperty(DEPLOYMENT_VHOST, environment.getVhosts().get(0).getHost());
            deploymentConfigObject.addProperty(DISPLAY_ON_DEVPORTAL_OPTION, true);
            log.debug("Initialization completed AWS Gateway Deployer for environment: " + environment.getName());

        } catch (Exception e) {
            throw new APIManagementException("Error occurred while initializing AWS Gateway Deployer", e);
        }
    }

    @Override
    public List<DiscoveredAPI> discoverAPI() {
        AWSAPIBuilderFactory factory = new AWSAPIBuilderFactory(apiGatewayClient, apiGatewayV2Client, region, stage);
        AWSDiscoveryService discoveryService = new AWSDiscoveryService();
        List<DiscoveredAPI> retrievedAPIs = new ArrayList<>();

        // Discover V1 REST APIs (paginator streams page-by-page)
        discoveryService.discover(
                apiGatewayClient.getRestApisPaginator().items(),
                factory, environment, organization,
                retrievedAPIs::add);

        // Discover V2 APIs — factory dispatches HTTP vs WebSocket via canHandle()
        discoveryService.discover(
                apiGatewayV2Client.getApis().items(),
                factory, environment, organization,
                retrievedAPIs::add);

        return retrievedAPIs;
    }

    @Override
    public boolean isAPIUpdated(String existingReferenceArtifact, String newReferenceArtifact) {
        return !existingReferenceArtifact.equals(newReferenceArtifact);
    }
}
