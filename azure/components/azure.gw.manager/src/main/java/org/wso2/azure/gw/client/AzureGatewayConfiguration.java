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
import com.azure.core.exception.AzureException;
import com.azure.core.http.HttpClient;
import com.azure.core.http.netty.NettyAsyncHttpClientBuilder;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.profile.AzureProfile;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.azure.resourcemanager.apimanagement.ApiManagementManager;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.osgi.service.component.annotations.Component;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.ConfigurationDto;
import org.wso2.carbon.apimgt.api.model.Environment;
import org.wso2.carbon.apimgt.api.model.GatewayAgentConfiguration;
import org.wso2.carbon.apimgt.api.model.GatewayEnvironmentValidationResult;
import org.wso2.carbon.apimgt.api.model.GatewayPortalConfiguration;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;


/**
 * This class contains the configurations related to Azure Gateway.
 */
@Component(
        name = "azure.external.gateway.configuration.component",
        immediate = true,
        service = GatewayAgentConfiguration.class
)
public class AzureGatewayConfiguration implements GatewayAgentConfiguration {
    private static final Log log = LogFactory.getLog(AzureGatewayConfiguration.class);
    private static final String INCOMPLETE_AZURE_CONFIGURATION =
            "The gateway configuration you added is incomplete. Provide the required Azure gateway details.";
    private static final String INVALID_AZURE_CONFIGURATION =
            "The Azure gateway configuration you added is invalid. Verify the credentials and service details.";

    /**
     * Returns the Deployer classname.
     */
    @Override
    public String getImplementation() {
        return AzureGatewayDeployer.class.getName();
    }

    /**
     * Returns the Gateway Deployer implementation.
     */
    @Override
    public String getGatewayDeployerImplementation() {
        return AzureGatewayDeployer.class.getName();
    }

    @Override
    public String getDiscoveryImplementation() {
        return AzureFederatedAPIDiscovery.class.getName();
    }

    @Override
    public String getApiKeyConnectorImplementation() {
        return AzureFederatedApiKeyConnector.class.getName();
    }

    /**
     * Returns the configuration values required to connect to Azure API Management.
     */
    @Override
    public List<ConfigurationDto> getConnectionConfigurations() {
        List<ConfigurationDto> configurationDtoList = new ArrayList<>();
        configurationDtoList.add(new ConfigurationDto(AzureConstants.AZURE_ENVIRONMENT_TENANT_ID, "Tenant ID", "input",
                "Directory (tenant) ID of your Microsoft Entra ID.", "", true, false, Collections.emptyList(), false));
        configurationDtoList.add(new ConfigurationDto(AzureConstants.AZURE_ENVIRONMENT_SUBSCRIPTION_ID,
                "Subscription ID", "input", "Azure subscription GUID that owns the APIM instance.", "", true, false,
                Collections.emptyList(), false));
        configurationDtoList.add(new ConfigurationDto(AzureConstants.AZURE_ENVIRONMENT_CLIENT_ID, "Client ID", "input",
                "Application (client) ID of the service principal used for Azure authentication.", "", true, false,
                Collections.emptyList(), false));
        configurationDtoList.add(new ConfigurationDto(AzureConstants.AZURE_ENVIRONMENT_CLIENT_SECRET, "Client Secret",
                "input", "Password/secret created for the service principal.", "", true, true, Collections.emptyList(),
                false));
        configurationDtoList.add(new ConfigurationDto(AzureConstants.AZURE_ENVIRONMENT_RESOURCE_GROUP, "Resource Group",
                "input", "The Azure resource group name containing the API Management service.", "", true, false,
                Collections.emptyList(), false));
        configurationDtoList.add(new ConfigurationDto(AzureConstants.AZURE_ENVIRONMENT_SERVICE_NAME,
                "APIM Service Name", "input", "The name of the Azure API Management service resource.", "", true, false,
                Collections.emptyList(), false));
        configurationDtoList.add(new ConfigurationDto(AzureConstants.AZURE_ENVIRONMENT_HOSTNAME, "APIM Host Name",
                "input", "The host name of the Azure API Management service resource.", "azure-api.net", false, false,
                Collections.emptyList(), false));

        return configurationDtoList;
    }
    public GatewayEnvironmentValidationResult validateEnvironment(Environment environment) {
        List<String> errors = new ArrayList<>();
        Map<String, String> additionalProperties = environment.getAdditionalProperties();
        if (additionalProperties == null) {
            log.warn("Azure gateway validation failed due to missing additional properties.");
            errors.add(INCOMPLETE_AZURE_CONFIGURATION);
            return buildValidationResult(errors);
        }
        String tenantId = additionalProperties.get(AzureConstants.AZURE_ENVIRONMENT_TENANT_ID);
        String clientId = additionalProperties.get(AzureConstants.AZURE_ENVIRONMENT_CLIENT_ID);
        String clientSecret = additionalProperties.get(AzureConstants.AZURE_ENVIRONMENT_CLIENT_SECRET);
        String subscriptionId = additionalProperties.get(AzureConstants.AZURE_ENVIRONMENT_SUBSCRIPTION_ID);
        String resourceGroup = additionalProperties.get(AzureConstants.AZURE_ENVIRONMENT_RESOURCE_GROUP);
        String serviceName = additionalProperties.get(AzureConstants.AZURE_ENVIRONMENT_SERVICE_NAME);
        if (StringUtils.isAnyBlank(tenantId, clientId, clientSecret, subscriptionId, resourceGroup, serviceName)) {
            log.warn("Azure gateway validation failed due to incomplete required connection properties.");
            errors.add(INCOMPLETE_AZURE_CONFIGURATION);
            return buildValidationResult(errors);
        }
        try {
            HttpClient httpClient = new NettyAsyncHttpClientBuilder().build();
            TokenCredential credential = new ClientSecretCredentialBuilder()
                    .httpClient(httpClient)
                    .tenantId(tenantId)
                    .clientId(clientId)
                    .clientSecret(clientSecret)
                    .authorityHost(AzureEnvironment.AZURE.getActiveDirectoryEndpoint())
                    .build();
            AzureProfile profile = new AzureProfile(tenantId, subscriptionId, AzureEnvironment.AZURE);
            ApiManagementManager manager = ApiManagementManager.configure().withHttpClient(httpClient)
                    .authenticate(credential, profile);
            if (manager.serviceClient().getApiManagementServices().getByResourceGroup(resourceGroup, serviceName)
                    == null) {
                log.warn("Azure gateway validation failed. API Management service not found for provided details.");
                errors.add(INVALID_AZURE_CONFIGURATION);
            }
        } catch (AzureException e) {
            log.error("Azure gateway validation failed while contacting Azure API Management.", e);
            errors.add(INVALID_AZURE_CONFIGURATION);
        } catch (Exception e) {
            log.error("Azure gateway validation failed with an unexpected error.", e);
            errors.add(INVALID_AZURE_CONFIGURATION);
        }
        return buildValidationResult(errors);
    }

    private GatewayEnvironmentValidationResult buildValidationResult(List<String> errors) {
        GatewayEnvironmentValidationResult validationResult = new GatewayEnvironmentValidationResult();
        validationResult.setValid(errors.isEmpty());
        validationResult.setErrors(errors);
        return validationResult;
    }

    /**
     * Returns the type of the gateway.
     */
    @Override
    public String getType() {
        return AzureConstants.AZURE_TYPE;
    }

    /**
     * Returns Azure Gateway feature catalog.
     *
     * @throws APIManagementException If there is an error reading the feature catalog JSON.
     */
    @Override
    public GatewayPortalConfiguration getGatewayFeatureCatalog() throws APIManagementException {
        try (InputStream inputStream = AzureGatewayConfiguration.class.getClassLoader()
                .getResourceAsStream(AzureConstants.GATEWAY_FEATURE_CATALOG_FILENAME)) {

            if (inputStream == null) {
                throw new APIManagementException("Gateway Feature Catalog JSON not found");
            }

            // Initialize Gson
            Gson gson = new Gson();

            InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
            JsonObject jsonObject = JsonParser.parseReader(reader).getAsJsonObject();

            JsonObject gatewayObject = jsonObject.getAsJsonObject(AzureConstants.AZURE_TYPE);

            List<String> apiTypes = gson.fromJson(gatewayObject.get("apiTypes"),
                    new TypeToken<List<String>>() { }.getType());
            JsonObject gatewayFeatures = gatewayObject.get("gatewayFeatures").getAsJsonObject();

            GatewayPortalConfiguration config = new GatewayPortalConfiguration();
            config.setGatewayType(AzureConstants.AZURE_TYPE);
            config.setSupportedAPITypes(apiTypes);
            config.setSupportedFeatures(gatewayFeatures);

            return config;
        } catch (Exception e) {
            throw new APIManagementException("Error occurred while reading Gateway Feature Catalog JSON", e);
        }
    }

    /**
     * Returns the hostname template for Azure API Management.
     *
     * @return The default hostname template.
     */
    @Override
    public String getDefaultHostnameTemplate() {
        return AzureConstants.AZURE_API_EXECUTION_URL_TEMPLATE;
    }
}
