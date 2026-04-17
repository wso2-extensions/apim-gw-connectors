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

package org.wso2.apigee.client;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.osgi.service.component.annotations.Component;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.ConfigurationDto;
import org.wso2.carbon.apimgt.api.model.GatewayAgentConfiguration;
import org.wso2.carbon.apimgt.api.model.GatewayMode;
import org.wso2.carbon.apimgt.api.model.GatewayPortalConfiguration;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component(
        name = "apigee.external.gateway.configuration.component",
        immediate = true,
        service = GatewayAgentConfiguration.class
)
public class ApigeeGatewayConfiguration implements GatewayAgentConfiguration {

    private static final Log log = LogFactory.getLog(ApigeeGatewayConfiguration.class);

    @Override
    public String getImplementation() {
        return null;
    }

    @Override
    public String getGatewayDeployerImplementation() {
        return null;
    }

    @Override
    public String getDiscoveryImplementation() {
        return ApigeeFederatedAPIDiscovery.class.getName();
    }

    @Override
    public List<String> getSupportedModes() {
        return Collections.singletonList(GatewayMode.READ_ONLY.getMode());
    }

    /**
     * Returns the three connection-configuration fields that WSO2 APIM renders
     * in the "Add Gateway Environment" dialog for the Apigee type.
     */
    @Override
    public List<ConfigurationDto> getConnectionConfigurations() {
        List<ConfigurationDto> configs = new ArrayList<>();

        // 1. Apigee Organization name  (e.g. "my-gcp-project")
        configs.add(new ConfigurationDto(
                ApigeeConstants.APIGEE_ORGANIZATION,
                "Apigee Organization",
                "input",
                "Google Cloud Apigee Organization (usually the GCP project ID)",
                "",
                true,   // required
                false,  // masked / secret
                Collections.emptyList(),
                false
        ));

        // 2. Apigee Environment  (e.g. "eval", "test", "prod")
        configs.add(new ConfigurationDto(
                ApigeeConstants.APIGEE_ENVIRONMENT,
                "Apigee Environment",
                "input",
                "The Apigee environment to target (e.g. eval, test, prod)",
                "",
                true,   // required
                false,  // masked / secret
                Collections.emptyList(),
                false
        ));

        // 3. GCP Service-Account JSON credentials.
        //    Stored directly as a string and masked (encrypted). Supported in APIM 4.7.0+ via AES.
        configs.add(new ConfigurationDto(
                ApigeeConstants.APIGEE_SERVICE_ACCOUNT_CREDENTIALS,
                "Service Account JSON Credentials",
                "input",
                "The actual GCP service account JSON key content. Start with { and end with }.",
                "",
                true,   // required
                true,   // masked / secret (encrypted via AES in the DB)
                Collections.emptyList(),
                false
        ));

        // 4. API Hostname - the actual domain where Apigee proxies are accessible
        //    Common formats:
        //    - IP-based: 34.49.61.76.nip.io
        //    - Custom domain: api.example.com
        //    - Default Apigee: {org}-{env}.apigee.net (auto-generated if left empty)
        configs.add(new ConfigurationDto(
                ApigeeConstants.APIGEE_API_HOSTNAME,
                "API Hostname",
                "input",
                "Hostname where APIs are accessible (e.g. 34.49.61.76.nip.io or api.example.com). Leave empty to use default {org}-{env}.apigee.net",
                "",
                false,  // optional
                false,  // not masked
                Collections.emptyList(),
                false
        ));

        // 5. API Hub Location - the GCP region where API Hub is deployed
        //    Common values: global, us-west1, us-east1, us-central1, europe-west1, asia-southeast1
        configs.add(new ConfigurationDto(
                ApigeeConstants.APIGEE_API_HUB_LOCATION,
                "API Hub Location",
                "input",
                "API Hub location/region (e.g. us-west1, us-east1, global). Defaults to 'global' if left empty. Only needed if API Hub is enabled.",
                "global",
                false,  // optional
                false,  // not masked
                Collections.emptyList(),
                false
        ));

        return configs;
    }

    /**
     * Returns the gateway type string.  This value is matched by the React UI to
     * display "Apigee Gateway" in the dropdown selector.
     */
    @Override
    public String getType() {
        return ApigeeConstants.APIGEE_TYPE;
    }

    /**
     * Returns the gateway feature catalog loaded from the bundled
     * {@code GatewayFeatureCatalog.json} resource file.
     */
    @Override
    public GatewayPortalConfiguration getGatewayFeatureCatalog() throws APIManagementException {
        try (InputStream inputStream = ApigeeGatewayConfiguration.class.getClassLoader()
                .getResourceAsStream("GatewayFeatureCatalog.json")) {

            if (inputStream == null) {
                throw new APIManagementException("Gateway Feature Catalog JSON not found in bundle resources");
            }

            Gson gson = new Gson();
            InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
            JsonObject jsonObject = JsonParser.parseReader(reader).getAsJsonObject();

            JsonObject gatewayObject = jsonObject.getAsJsonObject(ApigeeConstants.APIGEE_TYPE);

            List<String> apiTypes = gson.fromJson(
                    gatewayObject.get("apiTypes"),
                    new TypeToken<List<String>>() {}.getType()
            );
            JsonObject gatewayFeatures = gatewayObject.get("gatewayFeatures").getAsJsonObject();

            GatewayPortalConfiguration config = new GatewayPortalConfiguration();
            config.setGatewayType(ApigeeConstants.APIGEE_TYPE);
            config.setSupportedAPITypes(apiTypes);
            config.setSupportedFeatures(gatewayFeatures);

            return config;
        } catch (Exception e) {
            throw new APIManagementException("Error occurred while reading Gateway Feature Catalog JSON", e);
        }
    }

    @Override
    public String getDefaultHostnameTemplate() {
        return ApigeeConstants.APIGEE_API_EXECUTION_URL_TEMPLATE;
    }
}
