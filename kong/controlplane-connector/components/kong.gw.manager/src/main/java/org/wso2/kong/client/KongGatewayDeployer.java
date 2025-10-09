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

package org.wso2.kong.client;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.Environment;
import org.wso2.carbon.apimgt.api.model.GatewayAPIValidationResult;
import org.wso2.carbon.apimgt.api.model.GatewayDeployer;

import java.util.Collections;

/**
 * This class controls the API artifact deployments on the Kong Gateway.
 */
public class KongGatewayDeployer implements GatewayDeployer {
    private static final Log log = LogFactory.getLog(org.wso2.kong.client.KongGatewayDeployer.class);
    private Environment environment;
    private String controlPlaneId;
    private String authToken;

    @Override
    public void init(Environment environment) throws APIManagementException {
        log.debug("Initializing Kong Gateway Deployer for environment: " + environment.getName());
        this.environment = environment;
    }

    @Override
    public String getType() {
        return KongConstants.KONG_TYPE;
    }

    @Override
    public String deploy(API api, String externalReference) throws APIManagementException {
        return null;
    }

    @Override
    public boolean undeploy(String s) throws APIManagementException {
        return true;
    }

    @Override
    public boolean undeploy(String externalReference, boolean delete) throws APIManagementException {
        return true;
    }

    @Override
    public GatewayAPIValidationResult validateApi(API api) throws APIManagementException {
        GatewayAPIValidationResult gatewayAPIValidationResult = new GatewayAPIValidationResult();
        gatewayAPIValidationResult.setValid(true);
        gatewayAPIValidationResult.setErrors(Collections.emptyList());
        return gatewayAPIValidationResult;
    }

    @Override
    public String getAPIExecutionURL(String externalReference) throws APIManagementException {
        return "";
    }

    @Override
    public String getAPIExecutionURL(String externalReference, HttpScheme httpScheme) throws APIManagementException {
        return "";
    }

    @Override
    public void transformAPI(API api) throws APIManagementException {
    }
}
