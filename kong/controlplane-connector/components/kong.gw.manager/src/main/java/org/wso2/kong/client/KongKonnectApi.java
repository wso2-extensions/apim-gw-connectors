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

import feign.Headers;
import feign.Param;
import feign.RequestLine;

import org.wso2.kong.client.model.KongAPI;
import org.wso2.kong.client.model.KongAPIImplementation;
import org.wso2.kong.client.model.KongAPISpec;
import org.wso2.kong.client.model.KongAcl;
import org.wso2.kong.client.model.KongConsumer;
import org.wso2.kong.client.model.KongConsumerGroup;
import org.wso2.kong.client.model.KongConsumerGroupMembership;
import org.wso2.kong.client.model.KongKeyAuth;
import org.wso2.kong.client.model.KongListResponse;
import org.wso2.kong.client.model.KongPlugin;
import org.wso2.kong.client.model.KongRoute;
import org.wso2.kong.client.model.KongService;
import org.wso2.kong.client.model.PagedResponse;

/**
 * Kong Konnect API interface for managing services and routes.
 * This interface defines methods to interact with the Kong Konnect API for listing, creating,
 * and managing services and routes.
 */
public interface KongKonnectApi {

    // List APIs
    @RequestLine("GET /v3/apis?size={size}")
    @Headers({"Accept: application/json"})
    KongListResponse<KongAPI> listAPIs(@Param("size") int size) throws KongGatewayException;

    // Get one API spec by API ID + Spec ID
    @RequestLine("GET /v3/apis/{apiId}/specifications/{specId}")
    @Headers({"Accept: application/json"})
    KongAPISpec getAPISpec(@Param("apiId") String apiId, @Param("specId") String specId) throws KongGatewayException;

    // List API implementations (api_id -> service mapping)
    @RequestLine("GET /v3/api-implementations?size={size}")
    @Headers({"Accept: application/json"})
    KongListResponse<KongAPIImplementation> listAPIImplementations(@Param("size") int size) throws KongGatewayException;

    // Services

    // Fetch a single service from a given control plane
    @RequestLine("GET /v2/control-planes/{cpId}/core-entities/services/{serviceId}")
    @Headers({"Accept: application/json"})
    KongService getService(@Param("cpId") String controlPlaneId, @Param("serviceId") String serviceId)
            throws KongGatewayException;

    @RequestLine("GET /v2/control-planes/{cpId}/core-entities/services?size={size}")
    @Headers({"Accept: application/json"})
    PagedResponse<KongService> listServices(@Param("cpId") String controlPlaneId, @Param("size") int size)
            throws KongGatewayException;

    // GET /v2/control-planes/{cpId}/core-entities/services/{serviceId}/routes?size={size}
    @RequestLine("GET /v2/control-planes/{cpId}/core-entities/services/{serviceId}/routes?size={size}")
    @Headers({"Accept: application/json", "Content-Type: application/json"})
    PagedResponse<KongRoute> listRoutesByServiceId(@Param("cpId") String controlPlaneId,
                                                   @Param("serviceId") String serviceId, @Param("size") int size)
            throws KongGatewayException;

    // List plugins bound to a specific service
    @RequestLine("GET /v2/control-planes/{cpId}/core-entities/services/{serviceId}/plugins?size={size}")
    @Headers({"Accept: application/json", "Content-Type: application/json"})
    PagedResponse<KongPlugin> listPluginsByServiceId(@Param("cpId") String controlPlaneId,
                                                     @Param("serviceId") String serviceId, @Param("size") int size)
            throws KongGatewayException;

    @RequestLine("GET /v2/control-planes/{cpId}/core-entities/routes/{routeId}/plugins?size={size}")
    @Headers({"Accept: application/json", "Content-Type: application/json"})
    PagedResponse<KongPlugin> listPluginsByRouteId(@Param("cpId") String controlPlaneId,
                                                   @Param("routeId") String routeId, @Param("size") int size)
            throws KongGatewayException;

    // Consumer management

    @RequestLine("POST /v2/control-planes/{cpId}/core-entities/consumers")
    @Headers({"Accept: application/json", "Content-Type: application/json"})
    KongConsumer createConsumer(@Param("cpId") String controlPlaneId, KongConsumer consumer)
            throws KongGatewayException;

    @RequestLine("GET /v2/control-planes/{cpId}/core-entities/consumers/{consumerId}")
    @Headers({"Accept: application/json"})
    KongConsumer getConsumer(@Param("cpId") String controlPlaneId, @Param("consumerId") String consumerId)
            throws KongGatewayException;

    @RequestLine("DELETE /v2/control-planes/{cpId}/core-entities/consumers/{consumerId}")
    @Headers({"Accept: application/json"})
    void deleteConsumer(@Param("cpId") String controlPlaneId, @Param("consumerId") String consumerId)
            throws KongGatewayException;

    // Key-auth credential management

    @RequestLine("POST /v2/control-planes/{cpId}/core-entities/consumers/{consumerId}/key-auth")
    @Headers({"Accept: application/json", "Content-Type: application/json"})
    KongKeyAuth createKeyAuth(@Param("cpId") String controlPlaneId, @Param("consumerId") String consumerId,
                              KongKeyAuth keyAuth) throws KongGatewayException;

    @RequestLine("GET /v2/control-planes/{cpId}/core-entities/consumers/{consumerId}/key-auth?size={size}")
    @Headers({"Accept: application/json"})
    PagedResponse<KongKeyAuth> listKeyAuth(@Param("cpId") String controlPlaneId, @Param("consumerId") String consumerId,
                                           @Param("size") int size) throws KongGatewayException;

    @RequestLine("DELETE /v2/control-planes/{cpId}/core-entities/consumers/{consumerId}/key-auth/{keyAuthId}")
    @Headers({"Accept: application/json"})
    void deleteKeyAuth(@Param("cpId") String controlPlaneId, @Param("consumerId") String consumerId,
                       @Param("keyAuthId") String keyAuthId) throws KongGatewayException;

    // ACL management

    @RequestLine("POST /v2/control-planes/{cpId}/core-entities/consumers/{consumerId}/acls")
    @Headers({"Accept: application/json", "Content-Type: application/json"})
    KongAcl createAcl(@Param("cpId") String controlPlaneId, @Param("consumerId") String consumerId,
                      KongAcl acl) throws KongGatewayException;

    @RequestLine("GET /v2/control-planes/{cpId}/core-entities/consumers/{consumerId}/acls?size={size}")
    @Headers({"Accept: application/json"})
    PagedResponse<KongAcl> listConsumerAcls(@Param("cpId") String controlPlaneId,
                                            @Param("consumerId") String consumerId,
                                            @Param("size") int size) throws KongGatewayException;

    @RequestLine("DELETE /v2/control-planes/{cpId}/core-entities/consumers/{consumerId}/acls/{aclId}")
    @Headers({"Accept: application/json"})
    void deleteConsumerAcl(@Param("cpId") String controlPlaneId, @Param("consumerId") String consumerId,
                           @Param("aclId") String aclId) throws KongGatewayException;

    // Consumer Group management

    @RequestLine("GET /v2/control-planes/{cpId}/core-entities/consumer_groups?size={size}")
    @Headers({"Accept: application/json"})
    PagedResponse<KongConsumerGroup> listConsumerGroups(@Param("cpId") String controlPlaneId,
                                                        @Param("size") int size) throws KongGatewayException;

    @RequestLine("POST /v2/control-planes/{cpId}/core-entities/consumer_groups/{groupId}/consumers")
    @Headers({"Accept: application/json", "Content-Type: application/json"})
    void addConsumerToGroup(@Param("cpId") String controlPlaneId, @Param("groupId") String groupId,
                            KongConsumerGroupMembership membership) throws KongGatewayException;

    @RequestLine("DELETE /v2/control-planes/{cpId}/core-entities/consumer_groups/{groupId}/consumers/{consumerId}")
    @Headers({"Accept: application/json"})
    void removeConsumerFromGroup(@Param("cpId") String controlPlaneId, @Param("groupId") String groupId,
                                 @Param("consumerId") String consumerId) throws KongGatewayException;

}
