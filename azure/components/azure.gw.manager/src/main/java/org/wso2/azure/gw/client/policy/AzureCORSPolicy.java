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
import org.wso2.carbon.apimgt.api.model.CORSConfiguration;
import org.xml.sax.SAXException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;

/**
 * This class manages the Azure API Management CORS policy.
 */
public class AzureCORSPolicy extends AzurePolicy {

    CORSConfiguration corsConfiguration;
    Document document;

    public AzureCORSPolicy(CORSConfiguration corsConfiguration)
            throws APIManagementException {
        this.type = AzurePolicyType.CORS;
        this.corsConfiguration = corsConfiguration;
    }

    @Override
    public AzurePolicyType getType() {
        return type;
    }

    @Override
    public void processDocument(DocumentBuilder documentBuilder)
            throws APIManagementException {
        setCORSConfigurationToCORSPolicy(documentBuilder, this.corsConfiguration);
    }

    private void readCORSPolicy(DocumentBuilder documentBuilder) throws APIManagementException {
        if (this.root != null) {
            return;
        }

        document = null;
        try (InputStream inputStream = AzureGatewayConfiguration.class.getClassLoader()
                .getResourceAsStream(AzureConstants.AZURE_CORS_POLICY_FILENAME)) {

            if (inputStream == null) {
                throw new APIManagementException("CORS Policy file not found");
            }
            document = documentBuilder.parse(inputStream);
        } catch (IOException e) {
            throw new APIManagementException("Error reading CORS policy file", e);
        } catch (SAXException e) {
            throw new APIManagementException("Error parsing CORS policy file", e);
        }

        this.root = document.getDocumentElement();
    }

    private void setCORSConfigurationToCORSPolicy(DocumentBuilder documentBuilder, CORSConfiguration corsConfiguration)
            throws APIManagementException {
        readCORSPolicy(documentBuilder);

        // Allowed Origins
        Element allowOrigins = AzurePolicyUtil.firstElementByTagName(this.root,
                AzureConstants.AZURE_CORS_POLICY_ALLOWED_ORIGINS);
        if (allowOrigins == null) {
            throw new APIManagementException("CORS policy does not contain allowed-origins element");
        }
        if (corsConfiguration.isCorsConfigurationEnabled()) {
            for (String origin : corsConfiguration.getAccessControlAllowOrigins()) {
                Element originElement = constructOrigin(origin);
                allowOrigins.appendChild(originElement);
            }
        } else {
            Element originElement = constructOrigin("*");
            allowOrigins.appendChild(originElement);
        }

        // Allowed Methods
        Element allowedMethods = AzurePolicyUtil.firstChildElementByTagName(this.root,
                AzureConstants.AZURE_CORS_POLICY_ALLOWED_METHODS);
        if (allowedMethods == null) {
            throw new APIManagementException("CORS policy does not contain allowed-methods element");
        }
        if (corsConfiguration.isCorsConfigurationEnabled()) {
            for (String method : corsConfiguration.getAccessControlAllowMethods()) {
                Element methodElement = constructMethod(method);
                allowedMethods.appendChild(methodElement);
            }
        } else {
            List<String> list = new ArrayList<>(Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
            for (String method : list) {
                Element methodElement = constructMethod(method);
                allowedMethods.appendChild(methodElement);
            }
        }

        // Allowed Headers
        Element allowedHeaders = AzurePolicyUtil.firstChildElementByTagName(this.root,
                AzureConstants.AZURE_CORS_POLICY_ALLOWED_HEADERS);
        if (allowedHeaders == null) {
            throw new APIManagementException("CORS policy does not contain allowed-headers element");
        }
        if (corsConfiguration.isCorsConfigurationEnabled()) {
            for (String header : corsConfiguration.getAccessControlAllowHeaders()) {
                Element headerElement = constructHeader(header);
                allowedHeaders.appendChild(headerElement);
            }
        } else {
            List<String> list = new ArrayList<>(Arrays.asList("authorization", "Access-Control-Allow-Origin",
                    "Content-Type", "SOAPAction", "apikey", "Internal-Key"));
            for (String header : list) {
                Element headerElement = constructHeader(header);
                allowedHeaders.appendChild(headerElement);
            }
        }
    }

    private Element constructOrigin(String origin) {
        Element originElement = document.createElement("origin");
        originElement.setTextContent(origin);
        return originElement;
    }

    private Element constructMethod(String method) {
        Element methodElement = document.createElement("method");
        methodElement.setTextContent(method);
        return methodElement;
    }

    private Element constructHeader(String header) {
        Element headerElement = document.createElement("header");
        headerElement.setTextContent(header);
        return headerElement;
    }
}
