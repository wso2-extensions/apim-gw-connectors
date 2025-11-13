/*
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
 */

package org.wso2.aws.client;

import org.wso2.aws.client.util.AWSAPIUtil;
import org.wso2.aws.client.util.GatewayUtil;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.Environment;
import org.wso2.carbon.apimgt.api.model.GatewayAPIValidationResult;
import org.wso2.carbon.apimgt.api.model.GatewayDeployer;
import org.wso2.carbon.apimgt.api.model.URITemplate;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;


/**
 * This class controls the API artifact deployments on the AWS API Gateway
 */
public class AWSGatewayDeployer implements GatewayDeployer {
    private ApiGatewayClient apiGatewayClient;
    private String region;
    private String stage;


    @Override
    public void init(Environment environment) throws APIManagementException {
        try {
            this.region = environment.getAdditionalProperties().get(AWSConstants.AWS_ENVIRONMENT_REGION);
            this.stage = environment.getAdditionalProperties().get(AWSConstants.AWS_API_STAGE);

            String accessKey = environment.getAdditionalProperties().get(AWSConstants.AWS_ENVIRONMENT_ACCESS_KEY);
            String secretKey = environment.getAdditionalProperties().get(AWSConstants.AWS_ENVIRONMENT_SECRET_KEY);

            SdkHttpClient httpClient = ApacheHttpClient.builder().build();
            this.apiGatewayClient = ApiGatewayClient.builder().region(Region.of(region))
                    .httpClient(httpClient).credentialsProvider(StaticCredentialsProvider
                            .create(AwsBasicCredentials.create(accessKey, secretKey))).build();
        } catch (Exception e) {
            throw new APIManagementException("Error occurred while initializing AWS Gateway Deployer", e);
        }
    }

    @Override
    public String getType() {
        return AWSConstants.AWS_TYPE;
    }

    @Override
    public String deploy(API api, String externalReference) throws APIManagementException {
        try {
            if (externalReference == null) {
                return AWSAPIUtil.importRestAPI(api, apiGatewayClient, region, stage);
            } else {
                return AWSAPIUtil.reimportRestAPI(externalReference, api, apiGatewayClient, region, stage);
            }
        } catch (SdkException e) {
            throw new APIManagementException("Error occurred while deploying the API to AWS API Gateway", e);
        }
    }

    @Override
    public boolean undeploy(String externalReference, boolean delete) throws APIManagementException {
        try {
            AWSAPIUtil.deleteDeployment(externalReference, apiGatewayClient, stage);
            if (delete) {
                AWSAPIUtil.deleteAPI(externalReference, apiGatewayClient);
            }
        } catch (SdkException e) {
            throw new APIManagementException("Error occurred while undeploy the API to AWS API Gateway", e);
        }
        return true;
    }

    @Override
    public boolean undeploy(String externalReference) throws APIManagementException {
        return true;
    }

    @Override
    public GatewayAPIValidationResult validateApi(API api) throws APIManagementException {
        List<String> errorList = new ArrayList<>();
        // Endpoint validation
        errorList.add(GatewayUtil.validateAWSAPIEndpoint(GatewayUtil.getEndpointURL(api)));
        // Check for wildcard in the resources
        errorList.add(GatewayUtil.validateResourceContexts(api));

        GatewayAPIValidationResult result = new GatewayAPIValidationResult();
        result.setValid(errorList.stream().allMatch(Objects::isNull));
        result.setErrors(errorList.stream().filter(Objects::nonNull).collect(Collectors.toList()));

        return result;
    }

    @Override
    public String getAPIExecutionURL(String externalReference) throws APIManagementException {
        StringBuilder resolvedUrl = new StringBuilder(AWSConstants.AWS_API_EXECUTION_URL_TEMPLATE);
        String awsAPIId = GatewayUtil.getAWSApiIdFromReferenceArtifact(externalReference);

        //replace {apiId} placeHolder with actual API ID
        int start = resolvedUrl.indexOf("{apiId}");
        if (start != -1) {
            resolvedUrl.replace(start, start + "{apiId}".length(), awsAPIId);
        }

        //replace {region} placeHolder with actual region
        start = resolvedUrl.indexOf("{region}");
        if (start != -1) {
            resolvedUrl.replace(start, start + "{region}".length(), region);
        }
        return resolvedUrl.toString() + "/" + stage;
    }

    @Override
    public void transformAPI(API api) throws APIManagementException {
        // change all /* resources to / in the resources list
        for (URITemplate resource : api.getUriTemplates()) {
            if (resource.getUriTemplate().endsWith("/*")) {
                resource.setUriTemplate(resource.getUriTemplate().replace("/*", "/"));
            }
        }
    }

}
