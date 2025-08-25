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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.wso2.azure.gw.client.AzureConstants;
import org.wso2.azure.gw.client.AzureGatewayConfiguration;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.xml.sax.SAXException;
import java.io.IOException;
import java.io.InputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;


/**
 * This class manages the Azure API Management policies, specifically the base policy.
 */
public class AzurePolicyBuilderFactory {

    private static final Log log = LogFactory.getLog(AzurePolicyBuilderFactory.class);

    private final DocumentBuilder documentBuilder;
    private final Element basePolicyRoot;

    public AzurePolicyBuilderFactory() throws APIManagementException {
        DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
        documentBuilderFactory.setNamespaceAware(false);
        documentBuilderFactory.setIgnoringComments(false);
        documentBuilderFactory.setCoalescing(true);
        documentBuilderFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        documentBuilderFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        try {
            documentBuilderFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (ParserConfigurationException e) {
            if (log.isDebugEnabled()) {
                log.debug("Disabling doctype declaration feature is not supported by the XML parser.", e);
            }
        }
        try {
            documentBuilder = documentBuilderFactory.newDocumentBuilder();
        } catch (Exception e) {
            throw new APIManagementException("Error creating DocumentBuilder", e);
        }

        Document basePolicyDocument = null;
        try (InputStream inputStream = AzureGatewayConfiguration.class.getClassLoader()
                .getResourceAsStream(AzureConstants.AZURE_BASE_POLICY_FILENAME)) {
            if (inputStream == null) {
                throw new APIManagementException("Base Policy file not found");
            }
            basePolicyDocument = documentBuilder.parse(inputStream);
            basePolicyRoot = basePolicyDocument.getDocumentElement();
        } catch (IOException e) {
            throw new APIManagementException("Error reading Base policy file", e);
        } catch (SAXException e) {
            throw new APIManagementException("Error parsing Base policy file", e);
        }
    }

    public AzurePolicyBuilder newPolicyBuilder() {
        return new AzurePolicyBuilder(documentBuilder, (Element) basePolicyRoot.cloneNode(true));
    }
}
