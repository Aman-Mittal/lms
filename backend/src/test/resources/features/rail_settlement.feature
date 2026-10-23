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

Feature: A released rail leg is paid through settlement
  Vision documents 3.4 and 3.9.

  A rail carrier is paid like any other: through a freight bill, matched
  against what the platform expected it to cost, with the same tolerance, the
  same dispute path and the same approval limit. The difference is the subject.
  A rail leg has no trip and no load, so the bill is about the leg, and the
  expected charge is the cost recorded on the leg when the itinerary was
  planned -- from the rail rate card or the planner's own figure. The bill is
  raised when the leg is released, the last milestone of a rail booking, once
  per leg however often the release is heard.

  Background:
    Given a tenant "acme" named "Acme Logistics"
    And tenant "acme" has an organisational unit "/acme/" named "Acme HQ" of type "HQ"
    And the tenant scope is "acme" at "/acme/"
    And a trading partner "CUST"
    And a trading partner "CONS-A"
    And a depot "ORIGIN" at 19.100,72.900
    And a depot "DEST-A" at 28.500,77.300
    And a railhead "RAIL-NGP" at 21.150,79.090
    And a railhead "RAIL-DLI" at 28.640,77.220
    And a railhead "RAIL-BPL" at 23.260,77.410
    And a carrier "VENDOR-1"
    And a carrier "VENDOR-2"
    And a carrier "RAILCO"
    And a lorry "MH-01-TL-0001" with payload 20000 kg and volume 60 m3
    And lorry "MH-01-TL-0001" has an "INSURANCE" certificate valid for 365 days
    And a lorry "DL-01-TL-0002" with payload 20000 kg and volume 60 m3
    And lorry "DL-01-TL-0002" has an "INSURANCE" certificate valid for 365 days
    And a lorry driver "Ravi" licensed for 365 days
    And a lorry driver "Sunil" licensed for 365 days
    And a road trip "FM-1" on lorry "MH-01-TL-0001" driven by "Ravi" from "ORIGIN" to "RAIL-NGP" for "VENDOR-1"
    And a road trip "LM-1" on lorry "DL-01-TL-0002" driven by "Sunil" from "RAIL-DLI" to "DEST-A" for "VENDOR-2"
    And shipment "SHP-1" is planned as an itinerary:
      | mode | from     | to       | departs h ago | arrives h ago | dwell min | trip | units    | booking | carrier  | cost  |
      | ROAD | ORIGIN   | RAIL-NGP | 48            | 40            | 0         | FM-1 |          |         | VENDOR-1 | 12000 |
      | RAIL | RAIL-NGP | RAIL-DLI | 34            | 14            | 240       |      | 40 WAGON | RR-778  | RAILCO   | 85000 |
      | ROAD | RAIL-DLI | DEST-A   | 10            | 4             | 120       | LM-1 |          |         | VENDOR-2 | 9000  |

  Scenario: Releasing a rail leg raises one freight bill for the rail carrier
    When road trip "FM-1" leaves its origin 48 hours ago
    And road trip "FM-1" delivers 40 hours ago
    And rail leg 2 of shipment "SHP-1" is reported "PLACED", "LOADED" and "DEPARTED" at 38, 36 and 34 hours ago
    Then shipment "SHP-1" has 0 rail freight bills
    When rail leg 2 of shipment "SHP-1" is reported "ARRIVED" 14 hours ago
    Then shipment "SHP-1" has 0 rail freight bills
    When rail leg 2 of shipment "SHP-1" is reported "RELEASED" 12 hours ago
    Then shipment "SHP-1" has 1 rail freight bills
    And the rail freight bill for leg 2 of shipment "SHP-1" is "DRAFT" and totals 85000
    And the rail freight bill for leg 2 of shipment "SHP-1" is payable to carrier "RAILCO"
    And the rail freight bill for leg 2 of shipment "SHP-1" has no trip and no load
    And the rail freight bill for leg 2 of shipment "SHP-1" explains itself as 40 wagons on booking "RR-778"
    And the rail freight bill for leg 2 of shipment "SHP-1" adds up

  Scenario: Road legs raise no rail bill and the whole walk raises exactly one
    When road trip "FM-1" leaves its origin 48 hours ago
    And road trip "FM-1" delivers 40 hours ago
    And rail leg 2 of shipment "SHP-1" is reported "PLACED", "LOADED" and "DEPARTED" at 38, 36 and 34 hours ago
    And rail leg 2 of shipment "SHP-1" is reported "ARRIVED" 14 hours ago
    And rail leg 2 of shipment "SHP-1" is reported "RELEASED" 12 hours ago
    And road trip "LM-1" leaves its origin 10 hours ago
    And road trip "LM-1" delivers 4 hours ago
    Then shipment "SHP-1" is "COMPLETED"
    And shipment "SHP-1" has 1 rail freight bills
    And the rail freight bill for leg 2 of shipment "SHP-1" is "DRAFT" and totals 85000

  Scenario: Hearing the release again raises no second bill
    When road trip "FM-1" leaves its origin 48 hours ago
    And road trip "FM-1" delivers 40 hours ago
    And rail leg 2 of shipment "SHP-1" is reported "PLACED", "LOADED" and "DEPARTED" at 38, 36 and 34 hours ago
    And rail leg 2 of shipment "SHP-1" is reported "ARRIVED" 14 hours ago
    And rail leg 2 of shipment "SHP-1" is reported "RELEASED" 12 hours ago
    And the release of rail leg 2 of shipment "SHP-1" is heard again
    Then shipment "SHP-1" has 1 rail freight bills
    And the release of rail leg 2 of shipment "SHP-1" was announced once

  # ------------------------------------------------------------ matching

  Scenario: An invoice inside the tolerance is approved for payment
    # Two percent of 85000 is 1700; the carrier invoices 500 over.
    Given road trip "FM-1" leaves its origin 48 hours ago
    And road trip "FM-1" delivers 40 hours ago
    And rail leg 2 of shipment "SHP-1" is reported "PLACED", "LOADED" and "DEPARTED" at 38, 36 and 34 hours ago
    And rail leg 2 of shipment "SHP-1" is reported "ARRIVED" 14 hours ago
    And rail leg 2 of shipment "SHP-1" is reported "RELEASED" 12 hours ago
    When "RAILCO" invoices 85500 against the rail freight bill for leg 2 of shipment "SHP-1"
    Then the rail freight bill for leg 2 of shipment "SHP-1" is "APPROVED_FOR_PAYMENT" and totals 85000
    And the rail freight bill for leg 2 of shipment "SHP-1" records a variance of 500

  Scenario: An invoice beyond the tolerance is disputed, and settled only with a reason
    Given road trip "FM-1" leaves its origin 48 hours ago
    And road trip "FM-1" delivers 40 hours ago
    And rail leg 2 of shipment "SHP-1" is reported "PLACED", "LOADED" and "DEPARTED" at 38, 36 and 34 hours ago
    And rail leg 2 of shipment "SHP-1" is reported "ARRIVED" 14 hours ago
    And rail leg 2 of shipment "SHP-1" is reported "RELEASED" 12 hours ago
    When "RAILCO" invoices 90000 against the rail freight bill for leg 2 of shipment "SHP-1"
    Then the rail freight bill for leg 2 of shipment "SHP-1" is "DISPUTED" and totals 85000
    And the rail freight bill for leg 2 of shipment "SHP-1" records a variance of 5000
    When the dispute on the rail freight bill for leg 2 of shipment "SHP-1" is settled with no reason given
    Then settling the rail freight bill is refused mentioning "reason"
    When the dispute on the rail freight bill for leg 2 of shipment "SHP-1" is settled because "Demurrage agreed with the railway"
    Then the rail freight bill for leg 2 of shipment "SHP-1" is "APPROVED_FOR_PAYMENT" and totals 85000

  Scenario: A leg priced from the rail rate card is billed at the card's figure
    # The expected charge is whatever multimodal recorded on the leg; here the
    # card priced it, and the bill carries that figure, not a second opinion.
    Given rail rate card "RC-1" is published for "RAILCO" from "RAIL-NGP" to "RAIL-DLI" from day -30 at "2000" per wagon, "1500" per container, minimum 10000
    And shipment "SHP-3" is planned as an itinerary:
      | mode | from     | to       | departs h ago | arrives h ago | dwell min | trip | units    | booking | carrier | cost |
      | RAIL | RAIL-NGP | RAIL-DLI | 10            | 2             | 0         |      | 40 WAGON | RR-31   | RAILCO  |      |
    When rail leg 1 of shipment "SHP-3" is reported "PLACED", "LOADED" and "DEPARTED" at 12, 11 and 10 hours ago
    And rail leg 1 of shipment "SHP-3" is reported "ARRIVED" 2 hours ago
    And rail leg 1 of shipment "SHP-3" is reported "RELEASED" 1 hours ago
    Then the rail freight bill for leg 1 of shipment "SHP-3" is "DRAFT" and totals 80000
