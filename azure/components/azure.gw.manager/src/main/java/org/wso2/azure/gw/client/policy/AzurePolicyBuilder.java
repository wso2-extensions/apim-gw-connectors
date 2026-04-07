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

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.wso2.azure.gw.client.AzureConstants;
import org.wso2.azure.gw.client.policy.policies.AzureCORSPolicy;
import org.wso2.azure.gw.client.policy.policies.AzurePolicy;
import org.wso2.carbon.apimgt.api.APIManagementException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/**
 * This class is responsible for building Azure API Management policies.
 */
public class AzurePolicyBuilder {
    private final DocumentBuilder documentBuilder;
    private Element basePolicyRoot;
    private List<AzurePolicy> inboundPolicies;
    private List<AzurePolicy> outboundPolicies;
    private List<AzurePolicy> onErrorPolicies;

    protected AzurePolicyBuilder(DocumentBuilder documentBuilder, Element basePolicyRoot) {
        this.documentBuilder = documentBuilder;
        this.basePolicyRoot = basePolicyRoot;
        this.inboundPolicies = new ArrayList<>();
        this.outboundPolicies = new ArrayList<>();
        this.onErrorPolicies = new ArrayList<>();
    }

    public AzurePolicyBuilder addPolicy(AzurePolicy policy, String direction) throws APIManagementException {
        if (direction.equalsIgnoreCase(AzureConstants.POLICY_DIRECTION_REQUEST)) {
            inboundPolicies.add(policy);
        } else if (direction.equalsIgnoreCase(AzureConstants.POLICY_DIRECTION_RESPONSE)) {
            outboundPolicies.add(policy);
        } else if (direction.equalsIgnoreCase(AzureConstants.POLICY_DIRECTION_FAULT)) {
            onErrorPolicies.add(policy);
        } else {
            throw new APIManagementException(direction + " flow policies are not supported by Azure APIs.");
        }
        policy.processDocument(documentBuilder);
        return this;
    }

    public String build() throws APIManagementException {
        if (basePolicyRoot == null) {
            throw new APIManagementException("Base policy is not initialized");
        }

        // Add inbound policies
        Element inbound = AzurePolicyUtil.firstChildElementByTagName(basePolicyRoot, "inbound");
        if (inbound == null) {
            throw new APIManagementException("Base policy does not contain inbound element");
        }

        // CORS policies should be added first
        for (AzurePolicy policy : inboundPolicies) {
            if (policy instanceof AzureCORSPolicy) {
                Node imported = basePolicyRoot.getOwnerDocument().importNode(policy.getRoot(), true);
                inbound.appendChild(imported);
            }
        }

        for (AzurePolicy policy : inboundPolicies) {
            if (!(policy instanceof AzureCORSPolicy)) {
                Node imported = basePolicyRoot.getOwnerDocument().importNode(policy.getRoot(), true);
                inbound.appendChild(imported);
            }
        }

        // Add outbound policies
        Element outbound = AzurePolicyUtil.firstChildElementByTagName(basePolicyRoot, "outbound");
        if (outbound == null) {
            throw new APIManagementException("Base policy does not contain outbound element");
        }

        for (AzurePolicy policy : outboundPolicies) {
            Node imported = basePolicyRoot.getOwnerDocument().importNode(policy.getRoot(), true);
            outbound.appendChild(imported);
        }

        // Add on-error policies
        Element onError = AzurePolicyUtil.firstChildElementByTagName(basePolicyRoot, "on-error");
        if (onError == null) {
            throw new APIManagementException("Base policy does not contain on-error element");
        }

        for (AzurePolicy policy : onErrorPolicies) {
            Node imported = basePolicyRoot.getOwnerDocument().importNode(policy.getRoot(), true);
            onError.appendChild(imported);
        }

        try {
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            StringWriter stringWriter = new StringWriter();
            transformer.transform(new DOMSource(basePolicyRoot), new StreamResult(stringWriter));
            return stringWriter.toString();
        } catch (TransformerConfigurationException e) {
            throw new APIManagementException("Error configuring transformer for policy", e);
        } catch (Exception e) {
            throw new APIManagementException("Error transforming policy to string", e);
        }
    }
}
