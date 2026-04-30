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

package org.wso2.azure.gw.client.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.apache.commons.lang3.StringUtils;
import org.wso2.azure.gw.client.AzureConstants;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.API;


import java.net.URISyntaxException;
import java.net.URI;
import java.util.regex.Pattern;

/**
 * Utility class for Azure Gateway operations.
 */
public class GatewayUtil {

    private static final Pattern VALID_PATH_PATTERN = Pattern.compile("^[a-zA-Z0-9-._~%!$&'()*+,;=:@/]*$");

    /**
     * Extracts the endpoint URL from the API's endpoint configuration.
     *
     * @param api The API object containing the endpoint configuration.
     * @return The production endpoint URL.
     * @throws APIManagementException If there is an error while parsing the endpoint configuration.
     */
    public static String getEndpointURL(API api) throws APIManagementException {

        try {
            String endpointConfig = api.getEndpointConfig();
            if (StringUtils.isEmpty(endpointConfig)) {
                return "";
            }

            JsonObject endpointConfigJson =
                    JsonParser.parseString(endpointConfig).getAsJsonObject();

            JsonObject prodEndpoints = endpointConfigJson.has("production_endpoints")
                    && endpointConfigJson.get("production_endpoints").isJsonObject()
                    ? endpointConfigJson.getAsJsonObject("production_endpoints")
                    : null;

            if (prodEndpoints == null) {
                return "";
            }

            String productionEndpoint = prodEndpoints.has("url")
                    && prodEndpoints.get("url").isJsonPrimitive()
                    && !prodEndpoints.get("url").isJsonNull()
                    ? prodEndpoints.get("url").getAsString()
                    : null;

            if (StringUtils.isEmpty(productionEndpoint)) {
                return "";
            }

            return productionEndpoint.endsWith("/")
                    ? productionEndpoint.substring(0, productionEndpoint.length() - 1)
                    : productionEndpoint;

        } catch (JsonSyntaxException e) {
            throw new APIManagementException(
                    "Error while parsing endpoint configuration", e);
        }
    }

    public static String validateAzureAPIName(String apiName) {
        if (!apiName.matches("^[a-zA-Z0-9]+$")) {
            return "API Name can only contain alphanumeric characters.";
        }
        return null;
    }

    /**
     * Validates the Azure API endpoint URL.
     *
     * @param urlString The URL string to validate.
     * @return null if the URL is valid, otherwise an error message.
     */
    public static String validateAzureAPIEndpoint(String urlString) {
        try {
            if (StringUtils.isEmpty(urlString)) {
                return null;
            }
            URI uri = new URI(urlString);

            // Validate scheme (only http and https are allowed)
            String protocol = uri.getScheme();
            if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol) && !"ws".equalsIgnoreCase(protocol) && !"wss".equalsIgnoreCase(protocol)) {
                return "Invalid Endpoint URL";
            }

            // Validate host
            if (uri.getHost() == null || uri.getHost().isEmpty()
                    || uri.getHost().equalsIgnoreCase("localhost")) {
                return "Invalid Endpoint URL";
            }

            // Validate path (no illegal characters)
            if (!VALID_PATH_PATTERN.matcher(uri.getPath()).matches() && uri.getPath() != null) {
                return "Invalid Endpoint URL";
            }
            return null;
        } catch (URISyntaxException | IllegalArgumentException e) {
            return "Invalid Endpoint URL";
        }
    }

    public static String validateAzureAPIContextTemplate(String contextTemplate) {
        if (!contextTemplate.endsWith(AzureConstants.API_CONTEXT_VERSION_PLACEHOLDER)) {
            return "Context templating not supported for Azure APIs.";
        }
        return null;
    }
}
