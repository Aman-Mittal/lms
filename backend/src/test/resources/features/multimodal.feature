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

Feature: Road to the railhead, rail for the trunk, road to the customer
  Vision document 3.4.

  A shipment's itinerary is an ordered list of legs. A road leg is a trip the
  platform already runs, and moves when the trip is dispatched and delivered.
  A rail leg is a booking for wagons or containers, and moves by terminal
  events -- placed, loaded, departed, arrived, released -- because a rake is
  not tracked by GPS. A leg cannot start before the leg before it has arrived
  and the transfer dwell has passed, and when a leg arrives too late for the
  next one's planned start, the connection is missed and said so. Every leg is
  costed and its carrier paid separately; the itinerary's cost is their sum.

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

  # ------------------------------------------------------------ the walk

  Scenario: A road-rail-road shipment walks end to end and costs the sum of its legs
    Then shipment "SHP-1" is "PLANNED"
    When road trip "FM-1" leaves its origin 48 hours ago
    Then leg 1 of shipment "SHP-1" is "DEPARTED"
    And shipment "SHP-1" is "IN_PROGRESS"
    When road trip "FM-1" delivers 40 hours ago
    Then leg 1 of shipment "SHP-1" is "ARRIVED"
    When rail leg 2 of shipment "SHP-1" is reported "PLACED", "LOADED" and "DEPARTED" at 38, 36 and 34 hours ago
    And rail leg 2 of shipment "SHP-1" is reported "ARRIVED" 14 hours ago
    And rail leg 2 of shipment "SHP-1" is reported "RELEASED" 12 hours ago
    Then leg 2 of shipment "SHP-1" is "RELEASED"
    When road trip "LM-1" leaves its origin 10 hours ago
    And road trip "LM-1" delivers 4 hours ago
    Then leg 3 of shipment "SHP-1" is "ARRIVED"
    And leg 3 of shipment "SHP-1" started on time
    And shipment "SHP-1" is "COMPLETED"
    And shipment "SHP-1" has no missed connection
    And shipment "SHP-1" costs 106000 in total over 3 legs
    And carrier "VENDOR-1" is owed 12000 for shipment "SHP-1"
    And carrier "RAILCO" is owed 85000 for shipment "SHP-1"
    And carrier "VENDOR-2" is owed 9000 for shipment "SHP-1"

  # ----------------------------------------------------------- hand-off

  Scenario: The train may not leave before the goods have been transferred onto it
    # The lorry arrived at the railhead 40 hours ago and the transfer takes
    # four hours, so the earliest the rake can leave is 36 hours ago.
    When road trip "FM-1" leaves its origin 48 hours ago
    And road trip "FM-1" delivers 40 hours ago
    And rail leg 2 of shipment "SHP-1" is reported "PLACED" 39 hours ago
    And rail leg 2 of shipment "SHP-1" is reported "LOADED" 38 hours ago
    And rail leg 2 of shipment "SHP-1" is reported "DEPARTED" 37 hours ago
    Then the multimodal desk refuses with code "handoff-too-early"
    And leg 2 of shipment "SHP-1" is "LOADED"
    When rail leg 2 of shipment "SHP-1" is reported "DEPARTED" 35 hours ago
    Then leg 2 of shipment "SHP-1" is "DEPARTED"

  Scenario: The train may not leave while the first mile is still on the road
    When road trip "FM-1" leaves its origin 48 hours ago
    And rail leg 2 of shipment "SHP-1" is reported "PLACED" 46 hours ago
    And rail leg 2 of shipment "SHP-1" is reported "DEPARTED" 44 hours ago
    Then the multimodal desk refuses with code "leg-illegal-transition"
    When rail leg 2 of shipment "SHP-1" is reported "LOADED" 45 hours ago
    And rail leg 2 of shipment "SHP-1" is reported "DEPARTED" 44 hours ago
    Then the multimodal desk refuses with code "handoff-predecessor-not-arrived"
    And leg 2 of shipment "SHP-1" is "LOADED"

  Scenario: A last-mile lorry sent before the hand-off is recorded as an early start
    # Dispatch is the trip's own gate, so it cannot be refused from here. It
    # is not hidden either.
    When road trip "FM-1" leaves its origin 48 hours ago
    And road trip "FM-1" delivers 40 hours ago
    And rail leg 2 of shipment "SHP-1" is reported "PLACED", "LOADED" and "DEPARTED" at 38, 36 and 34 hours ago
    And rail leg 2 of shipment "SHP-1" is reported "ARRIVED" 14 hours ago
    And road trip "LM-1" leaves its origin 13 hours ago
    Then leg 3 of shipment "SHP-1" is "DEPARTED"
    And leg 3 of shipment "SHP-1" started early

  # -------------------------------------------------- missed connections

  Scenario: A late first mile misses the train, and the miss is recorded once
    # Planned to reach the railhead 40 hours ago; it got there 31 hours ago.
    # With four hours to transfer, the rake booked to leave 34 hours ago is
    # gone -- the soonest anything can leave is 27 hours ago.
    When road trip "FM-1" leaves its origin 48 hours ago
    And road trip "FM-1" delivers 31 hours ago
    Then leg 2 of shipment "SHP-1" missed its connection and can start 27 hours ago at the earliest
    When the delivery of road trip "FM-1" is heard again
    Then the missed connection on leg 2 of shipment "SHP-1" is recorded once

  Scenario: A late train misses the last-mile lorry
    When road trip "FM-1" leaves its origin 48 hours ago
    And road trip "FM-1" delivers 40 hours ago
    And rail leg 2 of shipment "SHP-1" is reported "PLACED", "LOADED" and "DEPARTED" at 38, 36 and 34 hours ago
    And rail leg 2 of shipment "SHP-1" is reported "ARRIVED" 9 hours ago
    Then leg 3 of shipment "SHP-1" missed its connection and can start 7 hours ago at the earliest
    And leg 2 of shipment "SHP-1" is "ARRIVED"

  # ------------------------------------------------------------ planning

  Scenario: An itinerary whose legs do not meet is refused
    When shipment "SHP-2" is planned as an itinerary:
      | mode | from     | to       | departs h ago | arrives h ago | dwell min | trip | units       | booking | carrier | cost  |
      | RAIL | ORIGIN   | RAIL-NGP | 30            | 20            | 0         |      | 2 CONTAINER | RR-1    | RAILCO  | 1000  |
      | RAIL | RAIL-DLI | DEST-A   | 10            | 5             | 0         |      | 2 CONTAINER | RR-2    | RAILCO  | 1000  |
    Then the multimodal desk refuses with code "itinerary-not-contiguous"

  Scenario: An itinerary planned to miss its own connection is refused
    When shipment "SHP-2" is planned as an itinerary:
      | mode | from     | to       | departs h ago | arrives h ago | dwell min | trip | units       | booking | carrier | cost  |
      | RAIL | ORIGIN   | RAIL-NGP | 30            | 20            | 0         |      | 2 CONTAINER | RR-1    | RAILCO  | 1000  |
      | RAIL | RAIL-NGP | DEST-A   | 19            | 5             | 240       |      | 2 CONTAINER | RR-2    | RAILCO  | 1000  |
    Then the multimodal desk refuses with code "itinerary-plan-misses-connection"

  Scenario: A trip already running one leg cannot run another
    When shipment "SHP-2" is planned as an itinerary:
      | mode | from   | to       | departs h ago | arrives h ago | dwell min | trip | units | booking | carrier  | cost |
      | ROAD | ORIGIN | RAIL-NGP | 48            | 40            | 0         | FM-1 |       |         | VENDOR-1 | 500  |
    Then the multimodal desk refuses with code "trip-already-on-itinerary"
