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

/**
 * This class contains the constants used in AWS client.
 */
public class AWSConstants {
    public static final String AWS_TYPE = "AWS";
    public static final String AWS_ID_PATTERN = "Id=([a-zA-Z0-9]+)";
    public static final String AWS_API_EXECUTION_URL_TEMPLATE = "{apiId}.execute-api.{region}.amazonaws.com";

    // Environment related constants
    public static final String AWS_ENVIRONMENT_REGION = "region";
    public static final String AWS_ENVIRONMENT_ACCESS_KEY = "access_key";
    public static final String AWS_ENVIRONMENT_SECRET_KEY = "secret_key";
    public static final String AWS_API_STAGE = "stage";

    // Authorizer related constants
    public static final String AWS_OPERATION_POLICY_NAME = "awsOAuth2";
    public static final String OPERATION_POLICY_ARN_PARAMETER = "lambdaARN";
    public static final String OPERATION_POLICY_ROLE_PARAMETER = "invokeRoleArn";
    public static final String OPERATION_POLICY_API = "API";
    public static final String OPEN_API_VERSION = "oas30";
    public static final String JSON_PAYLOAD_TYPE = "application/json";

    public static final String PRODUCTION_ENDPOINTS = "production_endpoints";
    public static final String SANDBOX_ENDPOINTS = "sandbox_endpoints";
    public static final String URL_PROP = "url";
    public static final String DEFAULT_VERSION = "1.0.0";
}
