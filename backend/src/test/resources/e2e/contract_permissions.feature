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
Feature: A caller may do exactly what their permissions allow
  The authorisation matrix over HTTP. A caller holding a permission nothing
  here asks for is turned away with a 403 problem document from every part of
  the API that has a permission to hold, and the refusal neither names the
  permission nor leaves anything behind. Permissions are granular: reading and
  writing are different grants.

  Background:
    Given the contract suite is signed in as the demo administrator
    And a contract caller holding only "AUDIT_READ"

  Scenario Outline: <tag>: <method> <path> is forbidden to a caller holding an unrelated permission
    When that contract caller sends "<method>" to "<path>" with "<body>" as the body
    Then the contract answer is a 403 problem with code "access-denied"
    And the contract problem does not name a permission

    Examples:
      | tag             | method | path                                          | body          |
      | Access policies | GET    | /api/v1/access-policies                       | none          |
      | Access policies | GET    | /api/v1/users/{id}/access-attributes          | none          |
      | Client billing  | GET    | /api/v1/client-billing/clients/{id}/contracts | none          |
      | Compliance      | GET    | /api/v1/compliance/expiring                   | none          |
      | Control tower   | GET    | /api/v1/exceptions                            | none          |
      | Control tower   | GET    | /api/v1/exceptions/summary                    | none          |
      | Drivers         | GET    | /api/v1/drivers                               | none          |
      | LTL rating      | GET    | /api/v1/ltl/receivables                       | none          |
      | Milk runs       | GET    | /api/v1/milk-run-routes                       | none          |
      | Multimodal      | GET    | /api/v1/itineraries                           | none          |
      | Orders          | POST   | /api/v1/orders                                | an order      |
      | Partners        | GET    | /api/v1/partners                              | none          |
      | Partners        | GET    | /api/v1/partners/{id}                         | none          |
      | Partners        | POST   | /api/v1/partners                              | a partner     |
      | Planning        | POST   | /api/v1/loads                                 | a load        |
      | Settlement      | GET    | /api/v1/freight-bills                         | none          |
      | Sourcing        | POST   | /api/v1/allocations                           | an allocation |
      | Telematics      | POST   | /api/v1/telematics/pings                      | a ping batch  |
      | Terminals       | GET    | /api/v1/terminals                             | none          |
      | Terminals       | POST   | /api/v1/terminals/point-radius                | a terminal    |
      | Trips           | POST   | /api/v1/trips                                 | a trip        |
      | Vehicles        | GET    | /api/v1/vehicles                              | none          |
      | Warehouse       | GET    | /api/v1/skus                                  | none          |
      | Warehouse       | GET    | /api/v1/warehouses/{id}/bins                  | none          |

  Scenario: A refused write leaves nothing behind
    Then the contract caller may not register a partner
    And the refused partner "CT-NOPE" was never created, so the administrator can register it

  Scenario: Reading and writing are different grants
    Given a contract caller holding only "PARTNER_READ, TERMINAL_READ"
    Then the contract caller may read partners
    And the contract caller may read terminals
    And the contract caller may not register a partner

  Scenario: A writer who cannot read, and a reader who cannot write
    Given a contract caller holding only "PARTNER_CREATE"
    Then the contract caller may not read partners
    And the contract caller may register the partner "CT-WRITER"
    Given a contract caller holding only "TERMINAL_READ"
    Then the contract caller may read terminals
    And the contract caller may not read partners
