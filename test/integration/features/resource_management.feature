# --------------------------------------------------------------------
# Copyright (c) 2025, WSO2 LLC. (http://wso2.com) All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
# -----------------------------------------------------------------------
Feature: Manage gateway custom resources via integration workflow
  # Run these scenarios only in environments with a reachable Kubernetes cluster and network access.
  # Enable the suite by exporting RUN_INTEGRATION_TESTS=true before running `go test`.

  @sample
  Scenario: Apply sample manifests, invoke a secured endpoint, and clean up
    Given I apply custom resources from "testdata/manifests/test-1/phase-1"
    And I wait for 5 seconds
    When I send a GET request to "https://am.wso2.com/api/am/publisher/v4/apis" with username "admin" and password "admin"
    Then the response status code should be 200
    And the response should contain key "count" with value "1"
    When I apply custom resources from "testdata/manifests/test-1/phase-2"
    And I wait for 5 seconds
    When I send a GET request to "https://am.wso2.com/api/am/publisher/v4/apis" with username "admin" and password "admin"
    Then the response status code should be 200
    And the response should contain key "count" with value "2"
    Then I delete custom resources from "testdata/manifests/test-1/delete"
