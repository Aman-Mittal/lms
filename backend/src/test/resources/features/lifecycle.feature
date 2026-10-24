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

@lifecycle
Feature: A trip's completion and an order's cancellation reach the freight they concern
  FR-CNG-009, FR-ORD-028, FR-ORD-006, FR-ORD-055, FR-CNG-004.

  Two lifecycle gaps, closed together because they are the same fault seen from
  either end: planning and the order disagreed about what had happened to the
  freight. A full truckload's trip completed and its consignments stayed on the
  load for ever, so the order never learned it had been delivered; and an order
  could be cancelled while its consignments and loads carried on as if nothing
  had changed.

  Background:
    Given a tenant "acme" named "Acme Logistics"
    And tenant "acme" has an organisational unit "/acme/" named "Acme HQ" of type "HQ"
    And the tenant scope is "acme" at "/acme/"
    And a trading partner "CUST"
    And a trading partner "CONS-A"
    And a trading partner "CONS-B"
    And a depot "ORIGIN" at 19.10,72.90
    And a depot "DEST-A" at 19.30,73.10
    And a depot "DEST-B" at 19.50,73.30
    And a lorry "MH-01-LC-0001" with payload 10000 kg and volume 40 m3
    And lorry "MH-01-LC-0001" has an "INSURANCE" certificate valid for 365 days
    And a lorry "MH-01-LC-0002" with payload 10000 kg and volume 40 m3
    And lorry "MH-01-LC-0002" has an "INSURANCE" certificate valid for 365 days
    And a lorry driver "Ravi" licensed for 365 days
    And a lorry driver "Sunil" licensed for 365 days
    And a carrier "HAULIER"

  # ------------------------------------------------- full-truckload delivery

  Scenario: A completed full-truckload trip delivers its consignments, completes its load and fulfils the order
    Given an order "FT-1" for "CUST" from "ORIGIN"
    And order "FT-1" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "FT-1" has line 2 of 800 kg of GENERAL for "CONS-B" at "DEST-B"
    And order "FT-1" is validated
    And consignments are generated for order "FT-1"
    And a load "LOAD-FT-1" is opened at "ORIGIN" on lorry "MH-01-LC-0001"
    And the shipment of order "FT-1" for "CONS-A" is put on load "LOAD-FT-1"
    And the shipment of order "FT-1" for "CONS-B" is put on load "LOAD-FT-1"
    And load "LOAD-FT-1" is planned
    When trip "TRIP-FT-1" runs load "LOAD-FT-1" for "HAULIER" on lorry "MH-01-LC-0001" driven by "Ravi"
    Then load "LOAD-FT-1" is "DISPATCHED"
    When trip "TRIP-FT-1" is completed at its destination
    Then the shipment of order "FT-1" for "CONS-A" is "DELIVERED"
    And the shipment of order "FT-1" for "CONS-B" is "DELIVERED"
    And load "LOAD-FT-1" is "COMPLETED"
    And order "FT-1" is "FULFILLED"

  Scenario: Recording the same completion again changes nothing
    Given an order "FT-2" for "CUST" from "ORIGIN"
    And order "FT-2" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "FT-2" is validated
    And consignments are generated for order "FT-2"
    And a load "LOAD-FT-2" is opened at "ORIGIN" on lorry "MH-01-LC-0001"
    And the shipment of order "FT-2" for "CONS-A" is put on load "LOAD-FT-2"
    And load "LOAD-FT-2" is planned
    And trip "TRIP-FT-2" runs load "LOAD-FT-2" for "HAULIER" on lorry "MH-01-LC-0001" driven by "Ravi"
    And trip "TRIP-FT-2" is completed at its destination
    When the completion of load "LOAD-FT-2" is recorded again
    Then the shipment of order "FT-2" for "CONS-A" is "DELIVERED"
    And load "LOAD-FT-2" is "COMPLETED"
    And order "FT-2" is "FULFILLED"

  Scenario: An order is fulfilled only when the last of its loads has been delivered
    Given an order "FT-3" for "CUST" from "ORIGIN"
    And order "FT-3" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "FT-3" has line 2 of 800 kg of GENERAL for "CONS-B" at "DEST-B"
    And order "FT-3" is validated
    And consignments are generated for order "FT-3"
    And a load "LOAD-FT-3A" is opened at "ORIGIN" on lorry "MH-01-LC-0001"
    And a load "LOAD-FT-3B" is opened at "ORIGIN" on lorry "MH-01-LC-0002"
    And the shipment of order "FT-3" for "CONS-A" is put on load "LOAD-FT-3A"
    And the shipment of order "FT-3" for "CONS-B" is put on load "LOAD-FT-3B"
    And load "LOAD-FT-3A" is planned
    And load "LOAD-FT-3B" is planned
    And trip "TRIP-FT-3A" runs load "LOAD-FT-3A" for "HAULIER" on lorry "MH-01-LC-0001" driven by "Ravi"
    And trip "TRIP-FT-3B" runs load "LOAD-FT-3B" for "HAULIER" on lorry "MH-01-LC-0002" driven by "Sunil"
    When trip "TRIP-FT-3A" is completed at its destination
    Then the shipment of order "FT-3" for "CONS-A" is "DELIVERED"
    And the shipment of order "FT-3" for "CONS-B" is "ASSIGNED_TO_LOAD"
    And order "FT-3" is "FULLY_PLANNED"
    When trip "TRIP-FT-3B" is completed at its destination
    Then order "FT-3" is "FULFILLED"

  Scenario: A part-load line haul still ends at the hub and leaves its order unfulfilled
    Given a hub "HUB-BOM" at 19.10,72.90
    And a hub "HUB-DEL" at 28.60,77.20
    And an LTL booking "LT-1" for "CUST" through hub "HUB-BOM" to hub "HUB-DEL"
    And order "LT-1" has line 1 of 200 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "LT-1" is validated
    And consignments are generated for order "LT-1"
    And an LTL load "LOAD-LT-1" is opened at hub "HUB-BOM" on lorry "MH-01-LC-0001"
    And the shipment of order "LT-1" for "CONS-A" is put on load "LOAD-LT-1"
    And load "LOAD-LT-1" is planned
    And trip "TRIP-LT-1" runs load "LOAD-LT-1" for "HAULIER" on lorry "MH-01-LC-0001" driven by "Ravi"
    When trip "TRIP-LT-1" is completed at its destination
    Then the shipment of order "LT-1" for "CONS-A" is "ARRIVED_AT_HUB"
    And load "LOAD-LT-1" is "COMPLETED"
    And order "LT-1" is "FULLY_PLANNED"

  # ----------------------------------------------------------- cancellation

  Scenario: Cancelling an order that was never planned
    Given an order "CX-1" for "CUST" from "ORIGIN"
    And order "CX-1" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "CX-1" is validated
    When order "CX-1" is cancelled
    Then order "CX-1" is "CANCELLED"

  Scenario: Cancelling a planned order cancels its consignments and its empty load
    Given an order "CX-2" for "CUST" from "ORIGIN"
    And order "CX-2" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "CX-2" has line 2 of 800 kg of GENERAL for "CONS-B" at "DEST-B"
    And order "CX-2" is validated
    And consignments are generated for order "CX-2"
    And a load "LOAD-CX-2" is opened at "ORIGIN" on lorry "MH-01-LC-0001"
    And the shipment of order "CX-2" for "CONS-A" is put on load "LOAD-CX-2"
    And the shipment of order "CX-2" for "CONS-B" is put on load "LOAD-CX-2"
    And load "LOAD-CX-2" is planned
    When order "CX-2" is cancelled with the reason "customer withdrew the indent"
    Then order "CX-2" is "CANCELLED"
    And the shipment of order "CX-2" for "CONS-A" is "CANCELLED"
    And the shipment of order "CX-2" for "CONS-B" is "CANCELLED"
    And load "LOAD-CX-2" is "CANCELLED"
    And load "LOAD-CX-2" holds 0 consignments

  Scenario: Cancelling a partly planned order cancels the loose consignment and shrinks the shared load
    Given an order "CX-3" for "CUST" from "ORIGIN"
    And order "CX-3" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "CX-3" has line 2 of 800 kg of GENERAL for "CONS-B" at "DEST-B"
    And order "CX-3" is validated
    And consignments are generated for order "CX-3"
    And an order "CX-3-OTHER" for "CUST" from "ORIGIN"
    And order "CX-3-OTHER" has line 1 of 500 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "CX-3-OTHER" is validated
    And consignments are generated for order "CX-3-OTHER"
    And a load "LOAD-CX-3" is opened at "ORIGIN" on lorry "MH-01-LC-0001"
    And the shipment of order "CX-3" for "CONS-A" is put on load "LOAD-CX-3"
    And the shipment of order "CX-3-OTHER" for "CONS-A" is put on load "LOAD-CX-3"
    When order "CX-3" is cancelled
    Then the shipment of order "CX-3" for "CONS-A" is "CANCELLED"
    And the shipment of order "CX-3" for "CONS-B" is "CANCELLED"
    And the shipment of order "CX-3-OTHER" for "CONS-A" is "ASSIGNED_TO_LOAD"
    And load "LOAD-CX-3" is "DRAFT"
    And load "LOAD-CX-3" carries 500 kg
    And load "LOAD-CX-3" holds 1 consignments

  Scenario: An order whose load has been dispatched cannot be cancelled
    Given an order "CX-4" for "CUST" from "ORIGIN"
    And order "CX-4" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "CX-4" is validated
    And consignments are generated for order "CX-4"
    And a load "LOAD-CX-4" is opened at "ORIGIN" on lorry "MH-01-LC-0001"
    And the shipment of order "CX-4" for "CONS-A" is put on load "LOAD-CX-4"
    And load "LOAD-CX-4" is planned
    And trip "TRIP-CX-4" runs load "LOAD-CX-4" for "HAULIER" on lorry "MH-01-LC-0001" driven by "Ravi"
    When order "CX-4" is cancelled
    Then cancelling the order is refused with "order-work-dispatched"
    And order "CX-4" is "FULLY_PLANNED"
    And the shipment of order "CX-4" for "CONS-A" is "ASSIGNED_TO_LOAD"
    And load "LOAD-CX-4" is "DISPATCHED"

  Scenario: A delivered order cannot be cancelled
    Given an order "CX-5" for "CUST" from "ORIGIN"
    And order "CX-5" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "CX-5" is validated
    And consignments are generated for order "CX-5"
    And a load "LOAD-CX-5" is opened at "ORIGIN" on lorry "MH-01-LC-0001"
    And the shipment of order "CX-5" for "CONS-A" is put on load "LOAD-CX-5"
    And load "LOAD-CX-5" is planned
    And trip "TRIP-CX-5" runs load "LOAD-CX-5" for "HAULIER" on lorry "MH-01-LC-0001" driven by "Ravi"
    And trip "TRIP-CX-5" is completed at its destination
    When order "CX-5" is cancelled
    Then cancelling the order is refused with "order-illegal-transition"
    And the shipment of order "CX-5" for "CONS-A" is "DELIVERED"

  Scenario: Cancelling twice is the same as cancelling once
    Given an order "CX-6" for "CUST" from "ORIGIN"
    And order "CX-6" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "CX-6" is validated
    And consignments are generated for order "CX-6"
    When order "CX-6" is cancelled
    And order "CX-6" is cancelled
    Then order "CX-6" is "CANCELLED"
    And the shipment of order "CX-6" for "CONS-A" is "CANCELLED"

  Scenario: A cancelled consignment cannot be put on a load
    Given an order "CX-7" for "CUST" from "ORIGIN"
    And order "CX-7" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "CX-7" is validated
    And consignments are generated for order "CX-7"
    And order "CX-7" is cancelled
    And a load "LOAD-CX-7" is opened at "ORIGIN" on lorry "MH-01-LC-0001"
    When the shipment of order "CX-7" for "CONS-A" is put on load "LOAD-CX-7"
    Then consolidation is refused with "consignment-not-assignable"
