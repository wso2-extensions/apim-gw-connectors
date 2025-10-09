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

package org.wso2.envoy.client;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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


/**
 * This class contains the configurations related to Envoy Gateway.
 */
@Component(
        name = "envoy.external.gateway.configuration.component",
        immediate = true,
        service = GatewayAgentConfiguration.class
)
public class EnvoyGatewayConfiguration implements GatewayAgentConfiguration {

    @Override
    public String getGatewayDeployerImplementation() {
        return null;
    }

    @Override
    public String getImplementation() {
        // Deprecated method, kept for backward compatibility
        return getGatewayDeployerImplementation();
    }

    @Override
    public List<ConfigurationDto> getConnectionConfigurations() {
        return new ArrayList<>();
    }

    @Override
    public String getType() {
        return EnvoyConstants.ENVOY_TYPE;
    }

    @Override
    public GatewayPortalConfiguration getGatewayFeatureCatalog() throws APIManagementException {
        try (InputStream inputStream = EnvoyGatewayConfiguration.class.getClassLoader()
                .getResourceAsStream("GatewayFeatureCatalog.json")) {

            if (inputStream == null) {
                throw new APIManagementException("Gateway Feature Catalog JSON not found");
            }

            // Initialize Gson
            Gson gson = new Gson();

            InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
            JsonObject jsonObject = JsonParser.parseReader(reader).getAsJsonObject();

            JsonObject gatewayObject = jsonObject.getAsJsonObject(EnvoyConstants.ENVOY_TYPE);

            List<String> apiTypes = gson.fromJson(gatewayObject.get("apiTypes"),
                    new TypeToken<List<String>>() { }.getType());
            JsonObject gatewayFeatures = gatewayObject.get("gatewayFeatures").getAsJsonObject();

            GatewayPortalConfiguration config = new GatewayPortalConfiguration();
            config.setGatewayType(EnvoyConstants.ENVOY_TYPE);
            config.setSupportedAPITypes(apiTypes);
            config.setSupportedFeatures(gatewayFeatures);

            return config;
        } catch (Exception e) {
            throw new APIManagementException("Error occurred while reading Gateway Feature Catalog JSON", e);
        }
    }

    @Override
    public String getDefaultHostnameTemplate() {
        return "";
    }

    @Override
    public List<String> getSupportedModes() {
        return Collections.singletonList(
                GatewayMode.READ_ONLY.getMode()
        );
    }
}
