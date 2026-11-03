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

  Scenario Outline: <operation>: a body that cannot be read is a 400 malformed-request, never a 500
    When the contract administrator sends "POST" to "<path>" with "<body>" as the body
    Then the contract answer is a 400 problem with code "malformed-request"
    And the contract problem reveals nothing of the implementation

    Examples:
      | operation          | path                         | body                         |
      | registerPartner    | /api/v1/partners             | malformed JSON               |
      | registerPartner    | /api/v1/partners             | truncated JSON               |
      | registerPartner    | /api/v1/partners             | a bare scalar                |
      | registerPartner    | /api/v1/partners             | an array                     |
      | registerPartner    | /api/v1/partners             | a wrong JSON type            |
      | registerPartner    | /api/v1/partners             | an unknown enum value        |
      | registerPartner    | /api/v1/partners             | a malformed id in the body   |
      | registerPartner    | /api/v1/partners             | none                         |
      | raiseOrder         | /api/v1/orders               | malformed JSON               |
      | raiseOrder         | /api/v1/orders               | none                         |
      | openLoad           | /api/v1/loads                | malformed JSON               |
      | raiseTrip          | /api/v1/trips                | none                         |
      | ingestPings        | /api/v1/telematics/pings     | malformed JSON               |
      | createSku          | /api/v1/skus                 | none                         |
      | login              | /api/v1/auth/login           | malformed JSON               |

  Scenario: A body that does not fit names the field that is wrong and nothing else
    When the contract administrator sends "POST" to "/api/v1/partners" with "an unknown enum value" as the body
    Then the contract answer is a 400 problem with code "malformed-request"
    And the contract problem reports field errors for exactly "partnerType"

  Scenario Outline: <operation>: an empty body on an operation that needs one is a 400, never a 500
    When the contract administrator sends "<method>" to "<path>" with "none" as the body
    Then the contract answer is a 400 problem with code "malformed-request"

    Examples:
      | operation                 | method | path                                          |
      | updateAccessPolicy        | PUT    | /api/v1/access-policies/{id}                  |
      | addOrderLine              | POST   | /api/v1/orders/{id}/lines                     |
      | addRoutingGuideVendor     | POST   | /api/v1/routing-guides/{id}/vendors           |
      | registerDriver            | POST   | /api/v1/drivers                               |
      | registerVehicle           | POST   | /api/v1/vehicles                              |
      | registerPointRadius       | POST   | /api/v1/terminals/point-radius                |
      | createBin                 | POST   | /api/v1/warehouses/{id}/bins                  |
      | putAway                   | POST   | /api/v1/putaways                              |
      | adjustStock               | POST   | /api/v1/stock/adjustments                     |
      | releaseFromQuarantine     | POST   | /api/v1/stock/quarantine/releases             |
      | writeOffQuarantine        | POST   | /api/v1/stock/quarantine/write-offs           |

  Scenario Outline: <operation>: an empty object names every missing field, never a 500
    When the contract administrator sends "<method>" to "<path>" with "empty object" as the body
    Then the contract answer is a 400 problem with code "validation-failed"
    And the contract problem reports field errors for "<fields>"

    Examples:
      | operation                 | method | path                                          | fields                                          |
      | updateAccessPolicy        | PUT    | /api/v1/access-policies/{id}                  | effect, description, condition, enabled         |
      | addOrderLine              | POST   | /api/v1/orders/{id}/lines                     | lineNo, materialCode, materialClass, quantity, uom, deadWeightKg, destinationTerminalId |
      | addRoutingGuideVendor     | POST   | /api/v1/routing-guides/{id}/vendors           | vendorPartnerId, rankNo, responseSlaMinutes     |
      | registerDriver            | POST   | /api/v1/drivers                               | orgUnitId, fullName, phone, licenceNo, licenceClass, licenceExpiresOn |
      | registerVehicle           | POST   | /api/v1/vehicles                              | orgUnitId, registrationNo, category, vehicleType, grossWeightKg, tareWeightKg |
      | registerPointRadius       | POST   | /api/v1/terminals/point-radius                | orgUnitId, code, name, category, centre, radiusMetres |
      | createBin                 | POST   | /api/v1/warehouses/{id}/bins                  | code, capacityUnits                             |
      | putAway                   | POST   | /api/v1/putaways                              | binId, skuId, quantity                          |
      | adjustStock               | POST   | /api/v1/stock/adjustments                     | binId, skuId, quantity, reason                  |
      | releaseFromQuarantine     | POST   | /api/v1/stock/quarantine/releases             | binId, skuId, quantity, reason                  |
      | writeOffQuarantine        | POST   | /api/v1/stock/quarantine/write-offs           | warehouseId, skuId, quantity, reason            |

  Scenario Outline: <operation>: an id that is not a UUID is a 400 invalid-parameter naming the parameter
    When the contract administrator sends "GET" to "<path>"
    Then the contract answer is a 400 problem with code "invalid-parameter"
    And the contract problem reports field errors for "<parameter>"
    And the contract problem reveals nothing of the implementation

    Examples:
      | operation      | path                                        | parameter |
      | getPartner     | /api/v1/partners/not-a-uuid                 | id        |
      | getVehicle     | /api/v1/vehicles/12345                      | id        |
      | getOrder       | /api/v1/orders/not-a-uuid                   | id        |
      | getLoad        | /api/v1/loads/not-a-uuid                    | id        |
      | getTrip        | /api/v1/trips/not-a-uuid                    | id        |
      | getPosition    | /api/v1/telematics/positions/not-a-uuid     | vehicleId |
      | getUser        | /api/v1/users/not-a-uuid                    | id        |

  Scenario Outline: <path>: a query value of the wrong type is a 400 invalid-parameter naming it
    When the contract administrator sends "GET" to "<path>"
    Then the contract answer is a 400 problem with code "invalid-parameter"
    And the contract problem reports field errors for "<parameter>"

    Examples:
      | path                                                  | parameter   |
      | /api/v1/partners?limit=abc                            | limit       |
      | /api/v1/orders?limit=1.5                              | limit       |
      | /api/v1/asns?warehouseId=not-a-uuid                   | warehouseId |
      | /api/v1/stock?warehouseId=not-a-uuid                  | warehouseId |

  Scenario Outline: <operation>: a missing required query parameter is a 400 missing-parameter naming it
    When the contract administrator sends "GET" to "<path>"
    Then the contract answer is a 400 problem with code "missing-parameter"
    And the contract problem reports field errors for exactly "<parameter>"

    Examples:
      | operation           | path                                                   | parameter   |
      | listClientCharges   | /api/v1/client-billing/clients/{id}/charges            | period      |
      | getClientStorage    | /api/v1/client-billing/clients/{id}/storage            | period      |
      | getClientMargins    | /api/v1/client-billing/margins                         | period      |
      | listAsns            | /api/v1/asns                                           | warehouseId |
      | getStock            | /api/v1/stock                                          | warehouseId |
      | getStockMovements   | /api/v1/stock/movements                                | skuId       |
      | listOutboundOrders  | /api/v1/outbound-orders                                | warehouseId |
      | listHandlingUnits   | /api/v1/handling-units                                 | warehouseId |
      | listPickWaves       | /api/v1/pick-waves                                     | warehouseId |

  Scenario Outline: The expiry window <window> is a 400 validation-failed naming it
    When the contract administrator sends "GET" to "/api/v1/compliance/expiring?withinDays=<window>"
    Then the contract answer is a 400 problem with code "validation-failed"
    And the contract problem reports field errors for "withinDays"

    Examples:
      | window |
      | -1     |
      | 3651   |

  Scenario Outline: A path nobody serves is a 404 not-found problem
    When the contract administrator sends "GET" to "<path>"
    Then the contract answer is a 404 problem with code "not-found"
    And the contract problem reveals nothing of the implementation

    Examples:
      | path                          |
      | /api/v1/nope                  |
      | /api/v1/partners/{id}/nope    |
      | /api/v1/itineraries/          |
      | /nothing-here                 |

  Scenario Outline: <method> on <path> is a 405 problem that keeps the Allow header
    When the contract administrator sends "<method>" to "<path>"
    Then the contract answer is a 405 problem with code "method-not-allowed"
    And the contract problem keeps the Allow header listing "<allowed>"

    Examples:
      | method | path                    | allowed   |
      | DELETE | /api/v1/partners        | GET, POST |
      | PUT    | /api/v1/partners        | GET, POST |
      | POST   | /api/v1/partners/{id}   | GET       |
      | DELETE | /api/v1/trips           | GET, POST |

  Scenario: A body of an unsupported media type is a 415 problem
    When the contract administrator posts a plain text body to "/api/v1/partners"
    Then the contract answer is a 415 problem with code "unsupported-media-type"

  Scenario: The unexposed actuator endpoints are a clean 404 for the administrator, not a 500
    Then the actuator path "/actuator/env" is not exposed even to the administrator
    And the actuator path "/actuator/beans" is not exposed even to the administrator
    And the actuator path "/actuator/heapdump" is not exposed even to the administrator
