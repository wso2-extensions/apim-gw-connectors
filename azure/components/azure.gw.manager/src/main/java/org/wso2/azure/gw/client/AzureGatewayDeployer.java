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

package org.wso2.azure.gw.client;

import com.azure.core.credential.TokenCredential;
import com.azure.core.http.HttpClient;
import com.azure.core.http.netty.NettyAsyncHttpClientBuilder;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.profile.AzureProfile;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.azure.resourcemanager.apimanagement.ApiManagementManager;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.wso2.azure.gw.client.util.AzureAPIUtil;
import org.wso2.azure.gw.client.util.GatewayUtil;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.Environment;
import org.wso2.carbon.apimgt.api.model.GatewayAPIValidationResult;
import org.wso2.carbon.apimgt.api.model.GatewayDeployer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * This class controls the API artifact deployments on the Azure API Management Gateway.
 */
public class AzureGatewayDeployer implements GatewayDeployer {

    private String resourceGroup;
    private String serviceName;
    private String hostName;
    private ApiManagementManager manager;

    /**
     * Initializes the Azure Gateway Deployer with the necessary credentials and configurations.
     *
     * @param environment The gateway environment containing Azure configurations.
     * @throws APIManagementException If there is an error during initialization.
     */
    @Override
    public void init(Environment environment) throws APIManagementException {
        try {
            String tenantId = environment.getAdditionalProperties().get(AzureConstants.AZURE_ENVIRONMENT_TENANT_ID);
            String clientId = environment.getAdditionalProperties().get(AzureConstants.AZURE_ENVIRONMENT_CLIENT_ID);
            String clientSecret = environment.getAdditionalProperties()
                    .get(AzureConstants.AZURE_ENVIRONMENT_CLIENT_SECRET);
            String subscriptionId = environment.getAdditionalProperties()
                    .get(AzureConstants.AZURE_ENVIRONMENT_SUBSCRIPTION_ID);
            resourceGroup = environment.getAdditionalProperties().get(AzureConstants.AZURE_ENVIRONMENT_RESOURCE_GROUP);
            serviceName = environment.getAdditionalProperties().get(AzureConstants.AZURE_ENVIRONMENT_SERVICE_NAME);
            hostName = environment.getAdditionalProperties().get(AzureConstants.AZURE_ENVIRONMENT_HOSTNAME);

            if (tenantId == null || clientId == null || clientSecret == null ||
                subscriptionId == null || resourceGroup == null || serviceName == null || hostName == null ||
                tenantId.isEmpty() || clientId.isEmpty() || clientSecret.isEmpty() ||
                subscriptionId.isEmpty() || resourceGroup.isEmpty() || serviceName.isEmpty() || hostName.isEmpty()) {

                throw new APIManagementException("Missing required Azure environment properties.");
            }

            HttpClient httpClient = new NettyAsyncHttpClientBuilder().build();

            TokenCredential cred = new ClientSecretCredentialBuilder()
                    .httpClient(httpClient)
                    .tenantId(tenantId)
                    .clientId(clientId)
                    .clientSecret(clientSecret)
                    .authorityHost(AzureEnvironment.AZURE.getActiveDirectoryEndpoint())
                    .build();

            AzureProfile profile = new AzureProfile(tenantId, subscriptionId, AzureEnvironment.AZURE);
            manager = ApiManagementManager.configure().withHttpClient(httpClient).authenticate(cred, profile);
        }  catch (Exception e) {
            throw new APIManagementException("Error initializing Azure Gateway Deployer.", e);
        }
    }

    /**
     * Returns the gateway type.
     *
     * @return The gateway type.
     */
    @Override
    public String getType() {
        return AzureConstants.AZURE_TYPE;
    }

    /**
     * Deploys the API to the Azure API Management service.
     *
     * @param api                The API to be deployed.
     * @param externalReference  The external reference from the API External API Mapping.
     * @return The external reference that should be stored.
     * @throws APIManagementException If there is an error during deployment.
     */
    @Override
    public String deploy(API api, String externalReference) throws APIManagementException {
        return AzureAPIUtil.deployRestAPI(api, manager, resourceGroup, serviceName);
    }

    /**
     * Undeploys the API from the Azure API Management service.
     *
     * @param externalReference  The external reference from the API External API Mapping.
     * @return true
     * @throws APIManagementException If there is an error during undeployment.
     */
    @Override
    public boolean undeploy(String externalReference) throws APIManagementException {
        AzureAPIUtil.deleteDeployment(externalReference, manager, resourceGroup, serviceName);
        return true;
    }

    /**
     * Validates the API endpoint for Azure API Management.
     *
     * @param api The API to be validated.
     * @return The validation result containing any errors found.
     * @throws APIManagementException If there is an error during validation.
     */
    @Override
    public GatewayAPIValidationResult validateApi(API api) throws APIManagementException {
        GatewayAPIValidationResult result = new GatewayAPIValidationResult();
        List<String> errorList = new ArrayList<>();

        errorList.add(GatewayUtil.validateAzureAPIEndpoint(GatewayUtil.getEndpointURL(api)));
        errorList.add(GatewayUtil.validateAzureAPIContextTemplate(api.getContextTemplate()));

        result.setValid(errorList.stream().allMatch(Objects::isNull));
        result.setErrors(errorList.stream().filter(Objects::nonNull).collect(Collectors.toList()));
        return result;
    }

    /**
     * Returns the API execution URL for the Azure API Management service.
     *
     * @param externalReference The external reference from the API External API Mapping.
     * @return The API execution URL.
     * @throws APIManagementException If there is an error while constructing the URL.
     */
    @Override
    public String getAPIExecutionURL(String externalReference) throws APIManagementException {

        if (externalReference == null || externalReference.isEmpty()) {
            throw new APIManagementException("External reference cannot be null or empty.");
        }

        StringBuilder resolvedUrl = new StringBuilder(AzureConstants.AZURE_API_EXECUTION_URL_TEMPLATE);

        //replace {service_name} placeHolder with actual Service Name
        int start = resolvedUrl.indexOf(AzureConstants.AZURE_API_EXECUTION_URL_TEMPLATE_SERVICE_NAME_PLACEHOLDER);
        if (start != -1) {
            resolvedUrl.replace(start, start +
                    AzureConstants.AZURE_API_EXECUTION_URL_TEMPLATE_SERVICE_NAME_PLACEHOLDER.length(), serviceName);
        }
        //replace {context} placeHolder with actual context
        JsonObject root = JsonParser.parseString(externalReference).getAsJsonObject();
        String context = root.get(AzureConstants.AZURE_EXTERNAL_REFERENCE_CONTEXT).getAsString();
        start = resolvedUrl.indexOf(AzureConstants.AZURE_API_EXECUTION_URL_TEMPLATE_CONTEXT_PLACEHOLDER);
        if (start != -1) {
            resolvedUrl.replace(start, start +
                    AzureConstants.AZURE_API_EXECUTION_URL_TEMPLATE_CONTEXT_PLACEHOLDER.length(), context);
        }

        //replace {host_name} placeHolder with actual hostname
        start = resolvedUrl.indexOf(AzureConstants.AZURE_API_EXECUTION_URL_TEMPLATE_HOSTNAME_PLACEHOLDER);
        if (start != -1) {
            resolvedUrl.replace(start, start +
                    AzureConstants.AZURE_API_EXECUTION_URL_TEMPLATE_HOSTNAME_PLACEHOLDER.length(), hostName);
        }

        return resolvedUrl.toString();
    }

    /**
     * Transforms the API for Azure API Management.
     *
     * @param api The API to be transformed.
     * @throws APIManagementException If there is an error during transformation.
     */
    @Override
    public void transformAPI(API api) throws APIManagementException {

    }
}
