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
  not tracked by GPS, and runs from one railhead to another. A leg cannot
  start before the leg before it has arrived and the transfer dwell has
  passed, and when a leg arrives too late for the next one's planned start --
  or never arrives at all -- the connection is missed and said so. Every leg
  is costed and its carrier paid separately; the itinerary's cost is their
  sum. A rail leg planned without a cost is priced from the rail carrier's
  rate card in force on its departure day.

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
    When the missed-connection sweep runs
    Then shipment "SHP-1" has no missed connection
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
    And the missed connection on leg 2 of shipment "SHP-1" was announced once

  Scenario: A late train misses the last-mile lorry
    When road trip "FM-1" leaves its origin 48 hours ago
    And road trip "FM-1" delivers 40 hours ago
    And rail leg 2 of shipment "SHP-1" is reported "PLACED", "LOADED" and "DEPARTED" at 38, 36 and 34 hours ago
    And rail leg 2 of shipment "SHP-1" is reported "ARRIVED" 9 hours ago
    Then leg 3 of shipment "SHP-1" missed its connection and can start 7 hours ago at the earliest
    And leg 2 of shipment "SHP-1" is "ARRIVED"

  # ------------------------------------------------ the control tower hears

  Scenario: A missed connection raises one critical ticket, however often it is heard
    # The first mile was late, so the ticket is on its trip -- and a critical
    # ticket on a trip holds that trip's settlement.
    When road trip "FM-1" leaves its origin 48 hours ago
    And road trip "FM-1" delivers 31 hours ago
    Then the control tower holds 1 "MISSED_CONNECTION" ticket about shipment "SHP-1"
    And that ticket is of severity "P2" on trip "FM-1"
    And that ticket suspends settlement
    When the missed connection on leg 2 of shipment "SHP-1" is delivered to the control tower again
    Then the control tower holds 1 "MISSED_CONNECTION" ticket about shipment "SHP-1"

  Scenario: A connection the train missed is critical, but has no trip's money to hold
    When road trip "FM-1" leaves its origin 48 hours ago
    And road trip "FM-1" delivers 40 hours ago
    And rail leg 2 of shipment "SHP-1" is reported "PLACED", "LOADED" and "DEPARTED" at 38, 36 and 34 hours ago
    And rail leg 2 of shipment "SHP-1" is reported "ARRIVED" 9 hours ago
    Then the control tower holds 1 "MISSED_CONNECTION" ticket about shipment "SHP-1"
    And that ticket is of severity "P2" on no trip
    And that ticket does not suspend settlement
    And the board counts 1 open ticket without a trip and 0 holding settlement

  Scenario: A first mile that never reaches the railhead is caught by the sweep, once
    # The lorry was due at the railhead 40 hours ago and has reported nothing
    # since leaving. Nothing arrives, so nothing reacts -- until the sweep
    # notices the rake booked behind it can no longer be made. The train is
    # overdue too, so the last-mile lorry behind it is caught as well.
    When road trip "FM-1" leaves its origin 48 hours ago
    And the missed-connection sweep runs
    Then leg 2 of shipment "SHP-1" missed its connection with leg 1 not yet arrived
    And leg 3 of shipment "SHP-1" missed its connection with leg 2 not yet arrived
    And the missed connection on leg 2 of shipment "SHP-1" was announced once
    # One ticket per missed leg: the train's, and the last-mile lorry's.
    And the control tower holds 2 "MISSED_CONNECTION" tickets about shipment "SHP-1"
    When the missed-connection sweep runs
    Then the missed connection on leg 2 of shipment "SHP-1" was announced once
    And the control tower holds 2 "MISSED_CONNECTION" tickets about shipment "SHP-1"
    # When the lorry does turn up, the earliest start becomes a fact rather
    # than a bound; the miss is not announced again, so no second ticket.
    When road trip "FM-1" delivers 31 hours ago
    Then leg 2 of shipment "SHP-1" missed its connection and can start 27 hours ago at the earliest
    And the missed connection on leg 2 of shipment "SHP-1" was announced once
    And the control tower holds 2 "MISSED_CONNECTION" tickets about shipment "SHP-1"

  Scenario: A train that never arrives raises a ticket on no trip, holding no money
    # The late leg is rail, so the announcement carries no trip: the ticket is
    # about the itinerary alone, and there is no trip's settlement to hold.
    When shipment "SHP-6" is planned as an itinerary:
      | mode | from     | to       | departs h ago | arrives h ago | dwell min | trip | units    | booking | carrier | cost  |
      | RAIL | RAIL-NGP | RAIL-DLI | 30            | 10            | 0         |      | 10 WAGON | RR-61   | RAILCO  | 20000 |
      | RAIL | RAIL-DLI | RAIL-BPL | 8             | 2             | 60        |      | 10 WAGON | RR-62   | RAILCO  | 20000 |
    And the missed-connection sweep runs
    Then leg 2 of shipment "SHP-6" missed its connection with leg 1 not yet arrived
    And the control tower holds 1 "MISSED_CONNECTION" ticket about shipment "SHP-6"
    And that ticket is of severity "P2" on no trip
    And that ticket does not suspend settlement
    When the missed-connection sweep runs
    Then the control tower holds 1 "MISSED_CONNECTION" ticket about shipment "SHP-6"

  Scenario: The sweep leaves a connection alone while there is still time to make it
    # The first train is three hours overdue, but the second is not booked to
    # leave for another day: arriving now and transferring would still make it.
    When shipment "SHP-5" is planned as an itinerary:
      | mode | from     | to       | departs h ago | arrives h ago | dwell min | trip | units    | booking | carrier | cost  |
      | RAIL | RAIL-NGP | RAIL-DLI | 20            | 3             | 0         |      | 10 WAGON | RR-51   | RAILCO  | 20000 |
      | RAIL | RAIL-DLI | RAIL-BPL | -24           | -40           | 60        |      | 10 WAGON | RR-52   | RAILCO  | 20000 |
    And the missed-connection sweep runs
    Then leg 2 of shipment "SHP-5" has not missed its connection

  # ------------------------------------------------------------ railheads

  Scenario: A rail leg runs only from a railhead to a railhead
    When shipment "SHP-2" is planned as an itinerary:
      | mode | from     | to       | departs h ago | arrives h ago | dwell min | trip | units       | booking | carrier | cost |
      | RAIL | ORIGIN   | RAIL-NGP | 30            | 20            | 0         |      | 2 CONTAINER | RR-1    | RAILCO  | 1000 |
    Then the multimodal desk refuses with code "rail-leg-not-at-railhead"
    When shipment "SHP-2" is planned as an itinerary:
      | mode | from     | to       | departs h ago | arrives h ago | dwell min | trip | units       | booking | carrier | cost |
      | RAIL | RAIL-DLI | DEST-A   | 30            | 20            | 0         |      | 2 CONTAINER | RR-1    | RAILCO  | 1000 |
    Then the multimodal desk refuses with code "rail-leg-not-at-railhead"

  # ------------------------------------------------------ rail rate cards

  # Days are counted from today, in UTC; a dash is a unit the card does not
  # price.

  Scenario: A rail leg planned without a cost is priced from the card in force on its departure day
    When rail rate card "RC-1" is published for "RAILCO" from "RAIL-NGP" to "RAIL-DLI" from day -30 at "2000" per wagon, "1500" per container, minimum 10000
    And rail rate card "RC-2" is published for "RAILCO" from "RAIL-NGP" to "RAIL-DLI" from day 3 at "2500" per wagon, "-" per container, minimum 10000
    Then rail rate card "RC-1" is in force until day 2
    And rail rate card "RC-2" is in force with no end
    When shipment "SHP-3" is planned as an itinerary:
      | mode | from     | to       | departs h ago | arrives h ago | dwell min | trip | units    | booking | carrier | cost |
      | RAIL | RAIL-NGP | RAIL-DLI | 10            | 2             | 0         |      | 40 WAGON | RR-31   | RAILCO  |      |
    Then leg 1 of shipment "SHP-3" costs 80000, priced by rail rate card "RC-1"
    When shipment "SHP-4" is planned as an itinerary:
      | mode | from     | to       | departs h ago | arrives h ago | dwell min | trip | units    | booking | carrier | cost |
      | RAIL | RAIL-NGP | RAIL-DLI | -100          | -120          | 0         |      | 40 WAGON | RR-41   | RAILCO  |      |
    Then leg 1 of shipment "SHP-4" costs 100000, priced by rail rate card "RC-2"

  Scenario: A small booking pays the card's minimum, and an explicit cost overrides the card
    When rail rate card "RC-1" is published for "RAILCO" from "RAIL-NGP" to "RAIL-DLI" from day -30 at "2000" per wagon, "1500" per container, minimum 10000
    And shipment "SHP-3" is planned as an itinerary:
      | mode | from     | to       | departs h ago | arrives h ago | dwell min | trip | units       | booking | carrier | cost |
      | RAIL | RAIL-NGP | RAIL-DLI | 10            | 2             | 0         |      | 2 CONTAINER | RR-31   | RAILCO  |      |
    Then leg 1 of shipment "SHP-3" costs 10000, priced by rail rate card "RC-1"
    When shipment "SHP-4" is planned as an itinerary:
      | mode | from     | to       | departs h ago | arrives h ago | dwell min | trip | units    | booking | carrier | cost  |
      | RAIL | RAIL-NGP | RAIL-DLI | 10            | 2             | 0         |      | 40 WAGON | RR-41   | RAILCO  | 70000 |
    Then leg 1 of shipment "SHP-4" costs 70000 as given, priced by no card

  Scenario: A rail leg with no cost is refused when no card prices it
    When rail rate card "RC-1" is published for "RAILCO" from "RAIL-NGP" to "RAIL-DLI" from day 5 at "2000" per wagon, "1500" per container, minimum 0
    And shipment "SHP-3" is planned as an itinerary:
      | mode | from     | to       | departs h ago | arrives h ago | dwell min | trip | units    | booking | carrier | cost |
      | RAIL | RAIL-NGP | RAIL-DLI | 10            | 2             | 0         |      | 40 WAGON | RR-31   | RAILCO  |      |
    Then the multimodal desk refuses with code "rail-leg-no-tariff"
    When rail rate card "RC-W" is published for "RAILCO" from "RAIL-DLI" to "RAIL-NGP" from day -10 at "2000" per wagon, "-" per container, minimum 0
    And shipment "SHP-4" is planned as an itinerary:
      | mode | from     | to       | departs h ago | arrives h ago | dwell min | trip | units       | booking | carrier | cost |
      | RAIL | RAIL-DLI | RAIL-NGP | 10            | 2             | 0         |      | 2 CONTAINER | RR-41   | RAILCO  |      |
    Then the multimodal desk refuses with code "rail-tariff-unit-not-priced"

  Scenario: A rail rate card runs between railheads and cannot be backdated under the one in force
    When rail rate card "RC-1" is published for "RAILCO" from "RAIL-NGP" to "RAIL-DLI" from day -30 at "2000" per wagon, "1500" per container, minimum 0
    And rail rate card "RC-2" is published for "RAILCO" from "RAIL-NGP" to "RAIL-DLI" from day -30 at "2100" per wagon, "1600" per container, minimum 0
    Then the multimodal desk refuses with code "rail-tariff-backdated"
    And rail rate card "RC-1" is in force with no end
    When rail rate card "RC-X" is published for "RAILCO" from "ORIGIN" to "RAIL-NGP" from day 0 at "2000" per wagon, "-" per container, minimum 0
    Then the multimodal desk refuses with code "rail-tariff-not-railhead"

  Scenario: A rail rate card is published only from a railhead in the caller's subtree
    Given tenant "acme" has an organisational unit "/acme/north/" named "North" of type "REGION"
    And tenant "acme" has an organisational unit "/acme/south/" named "South" of type "REGION"
    And a railhead "RAIL-AMB" at 30.380,76.780 under "/acme/north/"
    And a railhead "RAIL-MAS" at 13.080,80.270 under "/acme/south/"
    And the tenant scope is "acme" at "/acme/north/"
    When rail rate card "RC-S" is published for "RAILCO" from "RAIL-MAS" to "RAIL-DLI" from day 0 at "2000" per wagon, "-" per container, minimum 0
    Then the multimodal desk refuses access
    When rail rate card "RC-N" is published for "RAILCO" from "RAIL-AMB" to "RAIL-DLI" from day 0 at "2000" per wagon, "-" per container, minimum 0
    Then rail rate card "RC-N" is in force with no end

  # ------------------------------------------------------------ planning

  Scenario: An itinerary whose legs do not meet is refused
    When shipment "SHP-2" is planned as an itinerary:
      | mode | from     | to       | departs h ago | arrives h ago | dwell min | trip | units       | booking | carrier | cost  |
      | RAIL | RAIL-BPL | RAIL-NGP | 30            | 20            | 0         |      | 2 CONTAINER | RR-1    | RAILCO  | 1000  |
      | RAIL | RAIL-DLI | RAIL-BPL | 10            | 5             | 0         |      | 2 CONTAINER | RR-2    | RAILCO  | 1000  |
    Then the multimodal desk refuses with code "itinerary-not-contiguous"

  Scenario: An itinerary planned to miss its own connection is refused
    When shipment "SHP-2" is planned as an itinerary:
      | mode | from     | to       | departs h ago | arrives h ago | dwell min | trip | units       | booking | carrier | cost  |
      | RAIL | RAIL-BPL | RAIL-NGP | 30            | 20            | 0         |      | 2 CONTAINER | RR-1    | RAILCO  | 1000  |
      | RAIL | RAIL-NGP | RAIL-DLI | 19            | 5             | 240       |      | 2 CONTAINER | RR-2    | RAILCO  | 1000  |
    Then the multimodal desk refuses with code "itinerary-plan-misses-connection"

  Scenario: A road leg's trip must run between the leg's own terminals
    Given a lorry "MH-01-TL-0003" with payload 20000 kg and volume 60 m3
    And lorry "MH-01-TL-0003" has an "INSURANCE" certificate valid for 365 days
    And a lorry driver "Amit" licensed for 365 days
    And a road trip "XM-1" on lorry "MH-01-TL-0003" driven by "Amit" from "ORIGIN" to "DEST-A" for "VENDOR-1"
    When shipment "SHP-2" is planned as an itinerary:
      | mode | from   | to       | departs h ago | arrives h ago | dwell min | trip | units | booking | carrier  | cost |
      | ROAD | ORIGIN | RAIL-NGP | 48            | 40            | 0         | XM-1 |       |         | VENDOR-1 | 500  |
    Then the multimodal desk refuses with code "road-leg-trip-terminals-mismatch"

  Scenario: A trip already running one leg cannot run another
    When shipment "SHP-2" is planned as an itinerary:
      | mode | from   | to       | departs h ago | arrives h ago | dwell min | trip | units | booking | carrier  | cost |
      | ROAD | ORIGIN | RAIL-NGP | 48            | 40            | 0         | FM-1 |       |         | VENDOR-1 | 500  |
    Then the multimodal desk refuses with code "trip-already-on-itinerary"
