# Copyright 2026 Aman Mittal
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

@e2e_contract
Feature: The served contract describes the API that is actually served
  The OpenAPI document is public, is the assembled contract and names every
  part of the API; every operation it documents is routed by the running
  application; and the operational endpoints beside the API are as closed as
  they should be.

  Background:
    Given the contract suite is signed in as the demo administrator

  Scenario: The contract is public and well formed
    When the contract caller fetches the served contract without a token
    Then the contract document is served publicly and is an OpenAPI 3.1 description of the platform
    And every operation in the contract document has a unique operationId
    And only GET is public on the contract path

  Scenario Outline: The contract declares the <tag> part of the API
    When the contract caller fetches the served contract without a token
    Then the contract document declares the tag "<tag>" and at least one operation uses it

    Examples:
      | tag             |
      | Authentication  |
      | Users           |
      | Access policies |
      | Partners        |
      | Vehicles        |
      | Drivers         |
      | Terminals       |
      | Warehouse       |
      | Compliance      |
      | Orders          |
      | Planning        |
      | LTL rating      |
      | Sourcing        |
      | Trips           |
      | Milk runs       |
      | Telematics      |
      | Multimodal      |
      | Settlement      |
      | Client billing  |
      | Control tower   |

  Scenario: Every documented operation is routed by the running application
    When the contract administrator probes every documented operation
    Then every probed operation is routed: none answers 405, a bare 404 or a server error

  Scenario Outline: Readiness is public and nothing else operational is
    Then the health endpoint "<health>" is public and reports UP

    Examples:
      | health                       |
      | /actuator/health             |
      | /actuator/health/readiness   |
      | /actuator/health/liveness    |

  Scenario Outline: The actuator path <path> is closed to anonymous callers and not exposed at all
    Then the actuator path "<path>" is refused without a token

    Examples:
      | path                |
      | /actuator           |
      | /actuator/info      |
      | /actuator/env       |
      | /actuator/metrics   |
      | /actuator/beans     |
      | /actuator/heapdump  |
      | /actuator/loggers   |

  Scenario Outline: The sensitive actuator endpoint <path> is not exposed even to the administrator
    Then the actuator path "<path>" is not exposed even to the administrator

    Examples:
      | path                |
      | /actuator/env       |
      | /actuator/metrics   |
      | /actuator/beans     |
      | /actuator/heapdump  |
      | /actuator/loggers   |

  Scenario: The administrator sees only health and info
    Then the administrator sees only the health and info actuator endpoints

  Scenario: Documentation viewers that are not part of the platform are not served anonymously
    Then neither "/v3/api-docs" nor "/swagger-ui.html" is served without a token
