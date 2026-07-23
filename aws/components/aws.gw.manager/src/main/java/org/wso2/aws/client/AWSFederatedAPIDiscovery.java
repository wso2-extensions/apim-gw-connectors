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
import org.wso2.aws.client.util.AWSAPIUtil;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.FederatedAPIDiscovery;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.DiscoveredAPI;
import org.wso2.carbon.apimgt.api.model.Environment;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.RestApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.wso2.carbon.apimgt.impl.importexport.ImportExportConstants.DEPLOYMENT_NAME;
import static org.wso2.carbon.apimgt.impl.importexport.ImportExportConstants.DEPLOYMENT_VHOST;
import static org.wso2.carbon.apimgt.impl.importexport.ImportExportConstants.DISPLAY_ON_DEVPORTAL_OPTION;

/**
 * Represents the federated API discovery implementation for AWS API Gateway.
 * This class is responsible for discovering REST APIs deployed on AWS API Gateway
 * and integrating them with the API management system.
 *
 * It includes methods for initializing the discovery process, fetching
 * deployed APIs, and evaluating whether an API has been updated.
 *
 * This implementation works in conjunction with AWS API Gateway and uses
 * AWS SDK for Java for API interactions.
 */
public class AWSFederatedAPIDiscovery implements FederatedAPIDiscovery {

    private static final Log log = LogFactory.getLog(AWSFederatedAPIDiscovery.class);

    private Environment environment;
    private ApiGatewayClient apiGatewayClient;
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

            if (region == null || region.isEmpty()) {
                throw new APIManagementException("AWS Region is a mandatory configuration");
            }

            // Resolve credentials provider: use static keys if provided, otherwise fall back to
            // DefaultCredentialsProvider which supports IAM Roles (EC2 Instance Profiles, EKS Pod
            // Identity, environment variables, ~/.aws/credentials, etc.)
            AwsCredentialsProvider credentialsProvider;
            if (accessKey != null && !accessKey.isEmpty() && secretKey != null && !secretKey.isEmpty()) {
                log.debug("Using static AWS credentials for environment: " + environment.getName());
                credentialsProvider = StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey));
            } else {
                log.info("Static AWS credentials not provided for environment: " + environment.getName()
                        + ". Falling back to DefaultCredentialsProvider (IAM Roles).");
                credentialsProvider = DefaultCredentialsProvider.create();
            }

            // If a Role ARN is provided, assume that role using the base credentials.
            // This enables cross-account access and least-privilege security.
            String roleArn = environment.getAdditionalProperties().get(AWSConstants.AWS_ENVIRONMENT_ROLE_ARN);
            SdkHttpClient httpClient = ApacheHttpClient.builder().build();
            if (roleArn != null && !roleArn.isEmpty()) {
                log.info("Assuming IAM Role: " + roleArn + " for environment: " + environment.getName());
                StsClient stsClient = StsClient.builder()
                        .region(Region.of(region))
                        .httpClient(httpClient)
                        .credentialsProvider(credentialsProvider)
                        .build();
                credentialsProvider = StsAssumeRoleCredentialsProvider.builder()
                        .stsClient(stsClient)
                        .refreshRequest(AssumeRoleRequest.builder()
                                .roleArn(roleArn)
                                .roleSessionName("WSO2-APIM-Discovery-" + environment.getName())
                                .build())
                        .build();
            }

            this.apiGatewayClient = ApiGatewayClient.builder()
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
        List<RestApi> restApis = AWSAPIUtil.getRestApis(apiGatewayClient);
        List<DiscoveredAPI> retrievedAPIs = java.util.Collections.synchronizedList(new ArrayList<>());
        
        restApis.parallelStream().forEach(restApi -> {
            String deploymentId = getStageDeploymentId(restApi.id());
            if (deploymentId == null) {
                return;
            }
            String apiDefinition = AWSAPIUtil.getRestApiDefinition(apiGatewayClient, restApi.id(), stage);
            API api = AWSAPIUtil.restAPItoAPI(restApi, apiDefinition, organization, environment);
            AWSAPIUtil.setEndpointConfig(api, restApi, apiGatewayClient);
            
            DiscoveredAPI discoveredAPI = new DiscoveredAPI(api,
                    AWSAPIUtil.createReferenceArtifact(restApi, apiDefinition, deploymentId));
            retrievedAPIs.add(discoveredAPI);
        });
        return new ArrayList<>(retrievedAPIs);
    }

    @Override
    public List<DiscoveredAPI> discoverMetadata() {
        log.debug("[LOGGING] AWS Connector: discoverMetadata() called. Fetching metadata for AWS REST APIs in parallel.");
        List<RestApi> restApis = AWSAPIUtil.getRestApis(apiGatewayClient);
        List<DiscoveredAPI> retrievedAPIs = java.util.Collections.synchronizedList(new ArrayList<>());
        
        restApis.parallelStream().forEach(restApi -> {
            String deploymentId = getStageDeploymentId(restApi.id());
            if (deploymentId == null) {
                return; // Not deployed to this stage, or access denied
            }
            
            // Skip fetching heavy spec definition and endpoint configuration to avoid massive network overhead
            String apiDefinition = "{}";
            API api = AWSAPIUtil.restAPItoAPI(restApi, apiDefinition, organization, environment);
            
            DiscoveredAPI discoveredAPI = new DiscoveredAPI(api,
                    AWSAPIUtil.createReferenceArtifact(restApi, apiDefinition, deploymentId));
            retrievedAPIs.add(discoveredAPI);
        });
        return new ArrayList<>(retrievedAPIs);
    }

    @Override
    public List<DiscoveredAPI> discoverAPI(List<String> apiIds) {
        log.debug("[LOGGING] AWS Connector: discoverAPI(List<String> apiIds) called in parallel with IDs: " + apiIds);
        List<RestApi> restApis = AWSAPIUtil.getRestApis(apiGatewayClient);
        List<DiscoveredAPI> retrievedAPIs = java.util.Collections.synchronizedList(new ArrayList<>());
        
        restApis.parallelStream().forEach(restApi -> {
            String awsApiId = restApi.id();
            String compositeKey = (restApi.name() != null ? restApi.name() : restApi.id()) + ":" + stage;
            
            if (apiIds.contains(awsApiId) || apiIds.contains(compositeKey)) {
                String deploymentId = getStageDeploymentId(restApi.id());
                if (deploymentId == null) {
                    return;
                }
                log.debug("[LOGGING] AWS Connector: MATCH FOUND. Fetching full specification (OAS definition) for API ID: " + awsApiId);
                String apiDefinition = AWSAPIUtil.getRestApiDefinition(apiGatewayClient, restApi.id(), stage);
                API api = AWSAPIUtil.restAPItoAPI(restApi, apiDefinition, organization, environment);
                AWSAPIUtil.setEndpointConfig(api, restApi, apiGatewayClient);
                
                DiscoveredAPI discoveredAPI = new DiscoveredAPI(api,
                        AWSAPIUtil.createReferenceArtifact(restApi, apiDefinition, deploymentId));
                retrievedAPIs.add(discoveredAPI);
            }
        });
        return new ArrayList<>(retrievedAPIs);
    }

    @Override
    public boolean isAPIUpdated(String existingReferenceArtifact, String newReferenceArtifact) {
        if (existingReferenceArtifact == null || newReferenceArtifact == null) {
            return true;
        }
        try {
            com.google.gson.JsonArray existingArr = com.google.gson.JsonParser
                    .parseString(existingReferenceArtifact).getAsJsonArray();
            com.google.gson.JsonArray newArr = com.google.gson.JsonParser
                    .parseString(newReferenceArtifact).getAsJsonArray();
            
            if (existingArr.size() >= 3 && newArr.size() >= 3) {
                String existingDeploymentId = existingArr.get(2).getAsString();
                String newDeploymentId = newArr.get(2).getAsString();
                return !existingDeploymentId.equals(newDeploymentId);
            }
            return !existingReferenceArtifact.equals(newReferenceArtifact);
        } catch (Exception e) {
            log.error("Error parsing AWS reference artifact", e);
            return !existingReferenceArtifact.equals(newReferenceArtifact);
        }
    }

    private String getStageDeploymentId(String apiId) {
        try {
            software.amazon.awssdk.services.apigateway.model.GetStageRequest request =
                    software.amazon.awssdk.services.apigateway.model.GetStageRequest.builder()
                            .restApiId(apiId)
                            .stageName(stage)
                            .build();
            software.amazon.awssdk.services.apigateway.model.GetStageResponse response =
                    apiGatewayClient.getStage(request);
            if (response.deploymentId() != null) {
                return response.deploymentId();
            }
        } catch (Exception e) {
            String message = e.getMessage();
            boolean isNotFound = e.getClass().getSimpleName().equals("NotFoundException") ||
                    e instanceof software.amazon.awssdk.services.apigateway.model.NotFoundException ||
                    (message != null && (message.contains("NotFoundException") || message.contains("not found")));
            if (isNotFound) {
                log.debug("Stage '" + stage + "' not found for API: " + apiId);
                return null;
            } else {
                log.error("Could not retrieve stage deployment ID for API: "
                        + apiId + ", stage: " + stage + ". Error: " + message, e);
                throw new RuntimeException("Transient error retrieving stage deployment ID for API " + apiId, e);
            }
        }
        return null;
    }
}
