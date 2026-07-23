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

/**
 * This class contains the constants used in the Apigee Gateway connector.
 */
public class ApigeeConstants {

    private ApigeeConstants() {
        // Prevent instantiation
    }

    /**
     * The gateway type identifier. This value is used by the WSO2 APIM React UI
     * to render "Apigee Gateway" in the external gateway type dropdown.
     */
    public static final String APIGEE_TYPE = "Apigee";

    // Environment / connection configuration keys
    public static final String APIGEE_ORGANIZATION = "apigee_organization";
    public static final String APIGEE_ORGANIZATION_LEGACY = "organization";
    public static final String APIGEE_ENVIRONMENT = "environment";
    public static final String APIGEE_SERVICE_ACCOUNT_CREDENTIALS = "service_account_credentials";
    public static final String APIGEE_API_HOSTNAME = "api_hostname";
    public static final String APIGEE_API_HUB_LOCATION = "api_hub_location";

    // Google Apigee Management API base URL
    public static final String APIGEE_MGMT_API_BASE = "https://apigee.googleapis.com/v1";

    // OAuth2 scope required by the Apigee Management API
    public static final String APIGEE_OAUTH_SCOPE = "https://www.googleapis.com/auth/cloud-platform";

    // Apigee API execution URL template — {org}-{env}.apigee.net is the classic format
    public static final String APIGEE_API_EXECUTION_URL_TEMPLATE = "{apigee_organization}-{environment}.apigee.net";

    // Apigee API Hub base URL for fetching OpenAPI specs
    public static final String APIGEE_API_HUB_BASE = "https://apihub.googleapis.com/v1";

    // Default API Hub location if not specified in environment configuration
    public static final String DEFAULT_API_HUB_LOCATION = "global";

    // Default API version when the proxy does not carry one
    public static final String DEFAULT_VERSION = "1.0.0";

    // JSON keys used when parsing Apigee management API responses
    public static final String JSON_KEY_PROXIES = "proxies";
    public static final String JSON_KEY_NAME = "name";
    public static final String JSON_KEY_REVISION = "revision";
    public static final String JSON_KEY_META_DATA = "metaData";
    public static final String JSON_KEY_CREATED_AT = "createdAt";
    public static final String JSON_KEY_LAST_MODIFIED_AT = "lastModifiedAt";
    public static final String JSON_KEY_BASE_PATHS = "basepaths";
    public static final String JSON_KEY_DEPLOYMENTS = "deployments";
    public static final String JSON_KEY_API_PROXY = "apiProxy";
    public static final String JSON_KEY_ENVIRONMENT = "environment";

    // Endpoint config keys
    public static final String PRODUCTION_ENDPOINTS = "production_endpoints";
    public static final String SANDBOX_ENDPOINTS = "sandbox_endpoints";
    public static final String URL_PROP = "url";
    public static final String JSON_PAYLOAD_TYPE = "application/json";
}
