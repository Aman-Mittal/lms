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
Feature: Anyone without a valid credential is turned away, whatever they ask for
  The platform's front door, over HTTP, across every part of the API. A request
  with no credential, or with one that does not verify, is a 401 with a Bearer
  challenge. Presenting a token that was valid but has expired fails because it
  expired and for no other reason. A token's tenant and permission claims are
  what decide what it can see and do.

  Background:
    Given the contract suite is signed in as the demo administrator

  Scenario Outline: <tag>: a request with no token is refused with 401 and a Bearer challenge
    When the contract caller sends "<operation>" without a token
    Then the contract answer is 401 with a Bearer challenge

    Examples:
      | tag            | operation                                                |
      | Authentication | POST /api/v1/auth/logout                                 |
      | Users          | GET /api/v1/users                                        |
      | Access policies| GET /api/v1/access-policies                              |
      | Partners       | GET /api/v1/partners                                     |
      | Vehicles       | GET /api/v1/vehicles                                     |
      | Drivers        | GET /api/v1/drivers                                      |
      | Terminals      | GET /api/v1/terminals                                    |
      | Warehouse      | GET /api/v1/skus                                         |
      | Compliance     | GET /api/v1/compliance/expiring                          |
      | Orders         | POST /api/v1/orders                                      |
      | Planning       | GET /api/v1/loads                                        |
      | LTL rating     | GET /api/v1/ltl/receivables                              |
      | Sourcing       | GET /api/v1/allocations                                  |
      | Trips          | GET /api/v1/trips                                        |
      | Milk runs      | GET /api/v1/milk-run-routes                              |
      | Telematics     | POST /api/v1/telematics/pings                            |
      | Multimodal     | GET /api/v1/itineraries                                  |
      | Settlement     | GET /api/v1/freight-bills                                |
      | Client billing | GET /api/v1/client-billing/margins?period=2026-01-01     |
      | Control tower  | GET /api/v1/exceptions                                   |

  Scenario Outline: <credential> is refused on <operation>
    When the contract caller presents "<credential>" to "<operation>"
    Then the contract answer is 401 with a Bearer challenge

    Examples:
      | credential                 | operation                |
      | a garbage token            | GET /api/v1/partners     |
      | a garbage token            | GET /api/v1/trips        |
      | a three-part garbage token | GET /api/v1/partners     |
      | a three-part garbage token | GET /api/v1/trips        |
      | an unsigned token          | GET /api/v1/partners     |
      | an unsigned token          | GET /api/v1/trips        |
      | an expired token           | GET /api/v1/partners     |
      | an expired token           | GET /api/v1/trips        |
      | a Basic credential         | GET /api/v1/partners     |
      | a Basic credential         | GET /api/v1/trips        |

  Scenario: A token that has expired is refused for that reason alone
    When the contract caller presents "an expired token" to "GET /api/v1/partners"
    Then the contract answer is 401 with a Bearer challenge
    And the contract challenge says the token expired
    When the contract caller presents a freshly minted token with the same claims to list partners
    Then the contract answer is 200

  Scenario: A token carries the tenant, and without one it sees nothing
    Given the contract tenant holds a partner of its own
    When the contract caller reads that partner with a token for the tenant that holds PARTNER_READ
    Then the contract answer is the partner itself
    When the contract caller reads that partner with a token that has no tenant claim
    Then the contract answer is a 404 problem with code "not-found"

  Scenario: A token carries the permissions, and without any it is forbidden
    Given the contract tenant holds a partner of its own
    When the contract caller reads that partner with a token for the tenant that carries no permissions
    Then the contract answer is a 403 problem with code "access-denied"
    And the contract problem does not name a permission

  Scenario Outline: The refusal of an anonymous caller still carries the correlation id
    When the contract caller sends "<method>" to "<path>" without a token and with the correlation id "<id>"
    Then the contract answer echoes the correlation id "<id>"

    Examples:
      | method | path                  | id                      |
      | GET    | /api/v1/partners      | contract-trace-0001     |
      | GET    | /api/v1/trips         | contract-trace-0002     |
