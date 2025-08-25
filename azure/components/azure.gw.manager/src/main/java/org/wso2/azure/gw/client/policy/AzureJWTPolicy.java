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

package org.wso2.azure.gw.client.policy;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.wso2.azure.gw.client.AzureConstants;
import org.wso2.azure.gw.client.AzureGatewayConfiguration;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.xml.sax.SAXException;
import java.io.IOException;
import java.io.InputStream;
import javax.xml.parsers.DocumentBuilder;

/**
 * This class manages the Azure API Management JWT policy.
 */
public class AzureJWTPolicy extends AzurePolicy {

    String openIdURL;

    public AzureJWTPolicy(String openIdURL) throws APIManagementException {
        this.type = AzurePolicyType.JWT;
        this.openIdURL = openIdURL;
    }

    @Override
    public AzurePolicyType getType() {
        return type;
    }

    @Override
    public void processDocument(DocumentBuilder documentBuilder) throws APIManagementException {
        setOpenIdURLToJWTPolicy(documentBuilder, this.openIdURL);
    }

    private void readJWTPolicy(DocumentBuilder documentBuilder) throws APIManagementException {
        if (this.root != null) {
            return;
        }
        Document jwtPolicyDocument = null;
        try (InputStream inputStream = AzureGatewayConfiguration.class.getClassLoader()
                .getResourceAsStream(AzureConstants.AZURE_JWT_POLICY_FILENAME)) {
            if (inputStream == null) {
                throw new APIManagementException("JWT Policy file not found");
            }
            jwtPolicyDocument = documentBuilder.parse(inputStream);
        } catch (IOException e) {
            throw new APIManagementException("Error reading JWT policy file", e);
        } catch (SAXException e) {
            throw new APIManagementException("Error parsing JWT policy file", e);
        }
        this.root = jwtPolicyDocument.getDocumentElement();
    }

    private void setOpenIdURLToJWTPolicy(DocumentBuilder documentBuilder, String openIdURL)
            throws APIManagementException {
        readJWTPolicy(documentBuilder);

        Element openIdConfig = AzurePolicyUtil.firstChildElementByTagName(this.root, "openid-config");
        if (openIdConfig == null) {
            throw new APIManagementException("JWT policy does not contain openid-config element");
        }
        openIdConfig.setAttribute("url", openIdURL);
    }
}
