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

/**
 * This class contains the constants used in Envoy client.
 */
public class EnvoyConstants {
    public static final String ENVOY_TYPE = "Envoy";
    public static final String ENVOY_ADMIN_URL = "admin_url";
    public static final String ENVOY_CONTROL_PLANE_ID = "control_plane_id";
    public static final String ENVOY_AUTH_TOKEN = "auth_key";

    // API endpoint configuration property names
    public static final String ENVOY_API_UUID = "uuid";
    public static final String ENVOY_API_CONTEXT = "context";
    public static final String ENVOY_API_VERSION = "version";
    public static final String ENVOY_GATEWAY_HOST = "host";
    public static final String ENVOY_GATEWAY_HTTP_CONTEXT = "httpContext";
    public static final String ENVOY_GATEWAY_HTTP_PORT = "httpPort";
    public static final String ENVOY_GATEWAY_HTTPS_PORT = "httpsPort";

    public static final String HTTPS_PROTOCOL = "https";
    public static final String HTTP_PROTOCOL = "http";
    public static final String PROTOCOL_SEPARATOR = "://";
    public static final String HOST_PORT_SEPARATOR = ":";
    public static final String CONTEXT_SEPARATOR = "/";
    public static final int DEFAULT_HTTPS_PORT = 443;
    public static final int DEFAULT_HTTP_PORT = 80;

    // Commonly used default values and headers
    public static final String AUTHORIZATION_HEADER = "Authorization";
    public static final String BEARER_PREFIX = "Bearer ";
    public static final int DEFAULT_API_LIST_LIMIT = 1000;
    public static final int DEFAULT_PLUGIN_LIST_LIMIT = 100;
    public static final int DEFAULT_SERVICE_LIST_LIMIT = 1000;
    public static final int DEFAULT_ROUTE_LIST_LIMIT = 1000;
    public static final String DEFAULT_API_PROVIDER = "admin";
    public static final String DEFAULT_API_VERSION = "v1";
    public static final String DEFAULT_TIER = "Unlimited";
    public static final String DEFAULT_GATEWAY_VENDOR = "external";
    public static final String DEFAULT_VHOST = "example.com";
}
