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

import feign.Response;
import feign.codec.ErrorDecoder;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
/**
 * Custom Error Decoder for handling errors from Kong Gateway.
 * This class implements the ErrorDecoder interface to decode error responses
 * from the Kong Gateway and throw a custom exception.
 */
public class KongErrorDecoder implements ErrorDecoder {

    private static final Log log = LogFactory.getLog(KongErrorDecoder.class);

    @Override
    public KongGatewayException decode(String s, Response response) {
        String responseStr = null;
        try {
            if (response.body() != null) {
                try (InputStream is = response.body().asInputStream()) {
                    responseStr = readResponseBody(is);
                }
            }
        } catch (IOException e) {
            if (log.isDebugEnabled()) {
                log.debug("Failed to read Kong error body", e);
            }
        }
        String method = response.request() != null ? response.request().httpMethod().name() : "?";
        String url = response.request() != null ? response.request().url() : "?";
        String msg =
                "HTTP " + response.status() + " from " + method + " " + url +
                        (responseStr != null ? (": " + responseStr) : "");
        return new KongGatewayException(response.status(), msg);
    }

    private String readResponseBody(InputStream inputStream) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[1024];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                builder.append(buffer, 0, read);
            }
        }
        return builder.toString();
    }
}
