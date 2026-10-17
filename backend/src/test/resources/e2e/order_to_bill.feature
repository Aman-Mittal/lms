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

Feature: An order becomes a paid freight bill, over the wire
  The end-to-end walk, through the published API only. Every step is an HTTP
  call made with the client generated from api/openapi.yaml, signed in as the
  demo administrator a fresh deployment seeds -- so this is what a customer
  integrating against the contract would actually experience, including every
  place where the contract and the server could disagree.

  Background:
    Given I am signed in as the demo administrator
    And a customer, a consignee and an active vendor "VEND" are registered
    And depots "ORIGIN" at 19.100,72.900 and "DEST" at 19.300,73.100 are registered
    And lorry "LORRY" with 20000 kg payload and driver "Ravi" are registered
    And "VEND" is ranked first on the routing guide from "ORIGIN" to "DEST"
    And "VEND" charges 4.00 per kg from "ORIGIN" to "DEST"

  Scenario: Order to invoice approval with nobody touching the database
    When an order for 5000 kg from "ORIGIN" to "DEST" is raised and validated
    And its consignment is loaded onto "LORRY" and the load is planned
    And the load is offered and "VEND" accepts it
    And a trip is gated in, weighed and dispatched with "LORRY" and "Ravi"
    And "LORRY" reports positions leaving "ORIGIN" and reaching "DEST"
    Then the trip is "AT_DESTINATION"
    When proof of delivery is accepted
    Then a freight bill of 20000 is raised for the trip
    When "VEND" invoices 20000
    Then the bill is "APPROVED_FOR_PAYMENT"

  Scenario: A theft on the road holds the vendor's payment until a second manager closes it
    Given a trip carrying 5000 kg has reached "DEST"
    When "THEFT" is reported on the trip
    And proof of delivery is accepted
    And "VEND" invoices 20000
    Then the invoice is refused with "settlement-on-hold"
    When the ticket is assigned, investigated and resolved by me
    And a second manager closes the ticket
    And "VEND" invoices 20000
    Then the bill is "APPROVED_FOR_PAYMENT"
