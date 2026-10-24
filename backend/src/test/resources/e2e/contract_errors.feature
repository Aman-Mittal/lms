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
Feature: Every refusal is a problem document a client can branch on
  The error contract over HTTP, across the API. Refusals are RFC 7807 problem
  documents with a stable code: a request that is not well formed is a 400
  naming the offending fields, an unknown id is a 404, a refusal by the domain
  is a 409. The document names its own request and carries the same code the
  contract documents, so clients branch on the code and never on the prose.

  Background:
    Given the contract suite is signed in as the demo administrator

  Scenario Outline: <operation>: an empty body is refused with 400 and the fields that are missing
    When the contract administrator sends "POST" to "<path>" with "empty object" as the body
    Then the contract answer is a 400 problem with code "validation-failed"
    And the contract problem reports field errors for "<fields>"

    Examples:
      | operation                  | path                                    | fields                                              |
      | registerPartner            | /api/v1/partners                        | orgUnitId, code, legalName, partnerType             |
      | transitionPartner          | /api/v1/partners/{id}/transitions       | target                                              |
      | attachPartnerDocument      | /api/v1/partners/{id}/documents         | documentType, documentNo                            |
      | registerPolygonTerminal    | /api/v1/terminals/polygon               | orgUnitId, code, name, category, ring               |
      | createSku                  | /api/v1/skus                            | code, description                                   |
      | createAsn                  | /api/v1/asns                            | asnNo, warehouseId, lines                           |
      | createOutboundOrder        | /api/v1/outbound-orders                 | orderNo, warehouseId, lines                         |
      | releasePickWave            | /api/v1/pick-waves                      | warehouseId, orderIds                               |
      | raiseOrder                 | /api/v1/orders                          | orgUnitId, customerPartnerId, originTerminalId, orderNo |
      | openLoad                   | /api/v1/loads                           | orgUnitId, loadNo, originTerminalId, vehicleId      |
      | allocateLoad               | /api/v1/allocations                     | loadId                                              |
      | defineRoutingGuide         | /api/v1/routing-guides                  | orgUnitId, originTerminalId, destinationTerminalId, vehicleType |
      | raiseTrip                  | /api/v1/trips                           | tripNo, loadId                                      |
      | publishTariff              | /api/v1/tariffs                         | orgUnitId, code, vendorPartnerId, vehicleType, effectiveFrom |
      | recordFuelSurcharge        | /api/v1/tariffs/fuel-surcharges         | month, surchargePct                                 |
      | raiseException             | /api/v1/exceptions                      | tripId, category, description                       |
      | planItinerary              | /api/v1/itineraries                     | shipmentRef, legs                                   |
      | publishRailTariff          | /api/v1/rail-tariffs                    | code, carrierPartnerId, originTerminalId, effectiveFrom |
      | publishMilkRunRoute        | /api/v1/milk-run-routes                 | code, plantTerminalId, stops, days, departureTime   |
      | publishClientContract      | /api/v1/client-billing/contracts        | orgUnitId, clientPartnerId, code, effectiveFrom     |
      | publishLtlRateCard         | /api/v1/ltl/rate-cards                  | code, originZone, destinationZone, rateBasis, rateAmount, kgPerCft |
      | ingestPings                | /api/v1/telematics/pings                | pings                                               |
      | createAccessPolicy         | /api/v1/access-policies                 | code, action, effect, description, condition        |
      | login                      | /api/v1/auth/login                      | tenantCode, email, password                         |
      | refreshToken               | /api/v1/auth/refresh                    | refreshToken                                        |

  Scenario: Only the field that is wrong is reported
    When the contract administrator registers a partner whose code is blank
    Then the contract answer is a 400 problem with code "validation-failed"
    And the contract problem reports field errors for exactly "code"

  Scenario: Every wrong field is reported at once
    When the contract administrator registers a partner whose code is blank and whose legal name is blank
    Then the contract answer is a 400 problem with code "validation-failed"
    And the contract problem reports field errors for exactly "code, legalName"

  Scenario Outline: <operation>: an unknown id is a 404 that names what was not found
    When the contract administrator sends "GET" to "<path>"
    Then the contract answer is a 404 problem with code "not-found"
    And the contract problem detail names the requested id

    Examples:
      | operation              | path                                    |
      | getPartner             | /api/v1/partners/{id}                   |
      | getVehicle             | /api/v1/vehicles/{id}                   |
      | getDriver              | /api/v1/drivers/{id}                    |
      | getTerminal            | /api/v1/terminals/{id}                  |
      | getOrder               | /api/v1/orders/{id}                     |
      | getConsignment         | /api/v1/consignments/{id}               |
      | getLoad                | /api/v1/loads/{id}                      |
      | getAllocation          | /api/v1/allocations/{id}                |
      | getRoutingGuide        | /api/v1/routing-guides/{id}             |
      | getTrip                | /api/v1/trips/{id}                      |
      | getMilkRunRoute        | /api/v1/milk-run-routes/{id}            |
      | getCircuitRun          | /api/v1/milk-runs/{id}                  |
      | getItinerary           | /api/v1/itineraries/{id}                |
      | getRailTariff          | /api/v1/rail-tariffs/{id}               |
      | getFreightBill         | /api/v1/freight-bills/{id}              |
      | getException           | /api/v1/exceptions/{id}                 |
      | getUser                | /api/v1/users/{id}                      |
      | getAccessPolicy        | /api/v1/access-policies/{id}            |
      | getClientInvoice       | /api/v1/client-billing/invoices/{id}    |
      | getClientCreditNote    | /api/v1/client-billing/credit-notes/{id}|
      | getAsn                 | /api/v1/asns/{id}                       |
      | getOutboundOrder       | /api/v1/outbound-orders/{id}            |
      | getPickWave            | /api/v1/pick-waves/{id}                 |
      | getHandlingUnit        | /api/v1/handling-units/{id}             |
      | getPosition            | /api/v1/telematics/positions/{id}       |

  Scenario: An unknown tracking reference is a 404 that names the reference
    When the contract administrator sends "GET" to "/api/v1/consignments/by-tracking-ref/NO-SUCH-{salt}"
    Then the contract answer is a 404 problem with code "not-found"
    And the contract problem detail names the requested tracking reference

  Scenario: A refusal by the domain is a 409 with its own stable code
    When the contract administrator registers the partner "CT-DUP"
    Then the contract answer is 201 with an id and a Location for that partner
    And registering the partner "CT-DUP" again is refused as a conflict that leaves the first untouched

  Scenario Outline: A problem document carries the correlation id the caller sent
    When the contract administrator sends "<method>" to "<path>" with the correlation id "<id>"
    Then the contract answer echoes the correlation id "<id>"

    Examples:
      | method | path                                | id                  |
      | GET    | /api/v1/partners/{id}               | contract-trace-0101 |
      | POST   | /api/v1/partners                    | contract-trace-0102 |
