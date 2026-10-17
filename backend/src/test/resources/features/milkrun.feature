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

Feature: A milk run collects from several suppliers in turn and delivers to the plant
  Inbound manufacturing logistics.

  A milk-run route is a fixed circuit: an ordered list of supplier pickups,
  each with a window measured from the circuit's departure, ending at a plant.
  Routes are versioned like rate cards, and carry a schedule from which
  circuits are planned ahead -- once per route per day, however many times the
  planner runs. A circuit is carried by a trip; the geofence crossings
  telematics reports for that trip record each supplier reached and left, and
  reaching the plant ends the circuit and prices it: a fixed rate for the run
  plus a fee for every supplier actually visited.

  Background:
    Given a tenant "acme" named "Acme Logistics"
    And tenant "acme" has an organisational unit "/acme/" named "Acme HQ" of type "HQ"
    And the tenant scope is "acme" at "/acme/"
    And a trading partner "CUST"
    And a trading partner "CONS-A"
    And a depot "SUP-1" at 19.100,72.900
    And a depot "SUP-2" at 19.150,73.000
    And a depot "SUP-3" at 19.200,73.000
    And a depot "PLANT" at 19.300,73.100
    And a carrier "VENDOR-1"
    And a lorry "MH-01-TL-0001" with payload 20000 kg and volume 60 m3
    And lorry "MH-01-TL-0001" has an "INSURANCE" certificate valid for 365 days
    And a lorry driver "Ravi" licensed for 365 days

  # ----------------------------------------------------- driven by geofences

  Scenario: An on-time circuit visits every supplier and is priced per stop
    Given a milk run route "MR-NORTH" to "PLANT" that left 6 hours ago, daily, at 5000.00 per run and 750.00 per stop, calling at:
      | supplier | opens after | closes after | dwell |
      | SUP-1    | 0           | 30           | 15    |
      | SUP-2    | 45          | 90           | 15    |
      | SUP-3    | 105         | 150          | 15    |
    And today's circuit on route "MR-NORTH" has been planned
    And a dispatched trip "TRIP-M1" on lorry "MH-01-TL-0001" from "SUP-1" to "PLANT"
    And the circuit on route "MR-NORTH" is carried by trip "TRIP-M1"
    And lorry "MH-01-TL-0001" reports from "SUP-1" 10 minutes into the circuit
    And lorry "MH-01-TL-0001" reports between "SUP-1" and "SUP-2" 30 minutes into the circuit
    And lorry "MH-01-TL-0001" reports from "SUP-2" 60 minutes into the circuit
    And lorry "MH-01-TL-0001" reports between "SUP-2" and "SUP-3" 80 minutes into the circuit
    And lorry "MH-01-TL-0001" reports from "SUP-3" 120 minutes into the circuit
    And lorry "MH-01-TL-0001" reports between "SUP-3" and "PLANT" 150 minutes into the circuit
    And lorry "MH-01-TL-0001" reports from "PLANT" 200 minutes into the circuit
    When the circuit's positions are ingested
    And 1200 kg is recorded as collected at stop 1 of the circuit on route "MR-NORTH"
    Then the circuit on route "MR-NORTH" is "COMPLETED"
    And stop 1 of the circuit on route "MR-NORTH" is "DEPARTED"
    And stop 2 of the circuit on route "MR-NORTH" is "DEPARTED"
    And stop 3 of the circuit on route "MR-NORTH" is "DEPARTED"
    And every stop of the circuit on route "MR-NORTH" was reached on time by geofence
    And the circuit on route "MR-NORTH" was charged 7250.00 for 3 suppliers visited
    And the circuit on route "MR-NORTH" collected 1200 kg in total
    And no milk run alerts were raised for route "MR-NORTH"
    And the completion of the circuit on route "MR-NORTH" was announced with a charge of 7250.00

  Scenario: A supplier the lorry drives past is skipped, and not paid for
    Given a milk run route "MR-NORTH" to "PLANT" that left 6 hours ago, daily, at 5000.00 per run and 750.00 per stop, calling at:
      | supplier | opens after | closes after | dwell |
      | SUP-1    | 0           | 30           | 15    |
      | SUP-2    | 45          | 90           | 15    |
      | SUP-3    | 105         | 150          | 15    |
    And today's circuit on route "MR-NORTH" has been planned
    And a dispatched trip "TRIP-M1" on lorry "MH-01-TL-0001" from "SUP-1" to "PLANT"
    And the circuit on route "MR-NORTH" is carried by trip "TRIP-M1"
    And lorry "MH-01-TL-0001" reports from "SUP-1" 10 minutes into the circuit
    And lorry "MH-01-TL-0001" reports between "SUP-1" and "SUP-3" 60 minutes into the circuit
    And lorry "MH-01-TL-0001" reports from "SUP-3" 120 minutes into the circuit
    And lorry "MH-01-TL-0001" reports between "SUP-3" and "PLANT" 160 minutes into the circuit
    And lorry "MH-01-TL-0001" reports from "PLANT" 200 minutes into the circuit
    When the circuit's positions are ingested
    Then stop 2 of the circuit on route "MR-NORTH" is "SKIPPED"
    And a skipped-supplier alert was raised for stop 2 of the circuit on route "MR-NORTH"
    And stop 3 of the circuit on route "MR-NORTH" is "DEPARTED"
    And the circuit on route "MR-NORTH" is "COMPLETED"
    And the circuit on route "MR-NORTH" was charged 6500.00 for 2 suppliers visited

  Scenario: Reaching a supplier after its window has closed is flagged late
    Given a milk run route "MR-NORTH" to "PLANT" that left 6 hours ago, daily, at 5000.00 per run and 750.00 per stop, calling at:
      | supplier | opens after | closes after | dwell |
      | SUP-1    | 0           | 30           | 15    |
      | SUP-2    | 45          | 90           | 15    |
      | SUP-3    | 105         | 150          | 15    |
    And today's circuit on route "MR-NORTH" has been planned
    And a dispatched trip "TRIP-M1" on lorry "MH-01-TL-0001" from "SUP-1" to "PLANT"
    And the circuit on route "MR-NORTH" is carried by trip "TRIP-M1"
    And lorry "MH-01-TL-0001" reports from "SUP-1" 10 minutes into the circuit
    And lorry "MH-01-TL-0001" reports between "SUP-1" and "SUP-2" 40 minutes into the circuit
    And lorry "MH-01-TL-0001" reports from "SUP-2" 100 minutes into the circuit
    And lorry "MH-01-TL-0001" reports between "SUP-2" and "SUP-3" 125 minutes into the circuit
    And lorry "MH-01-TL-0001" reports from "SUP-3" 140 minutes into the circuit
    And lorry "MH-01-TL-0001" reports between "SUP-3" and "PLANT" 170 minutes into the circuit
    And lorry "MH-01-TL-0001" reports from "PLANT" 200 minutes into the circuit
    When the circuit's positions are ingested
    Then stop 2 of the circuit on route "MR-NORTH" was reached 10 minutes late
    And a late-window alert of 10 minutes was raised for stop 2 of the circuit on route "MR-NORTH"
    And stop 3 of the circuit on route "MR-NORTH" was reached 0 minutes late
    And the circuit on route "MR-NORTH" was charged 7250.00 for 3 suppliers visited

  Scenario: A crossing delivered twice changes nothing the second time
    # The outbox delivers at least once.
    Given a milk run route "MR-NORTH" to "PLANT" that left 6 hours ago, daily, at 5000.00 per run and 750.00 per stop, calling at:
      | supplier | opens after | closes after | dwell |
      | SUP-1    | 0           | 30           | 15    |
      | SUP-2    | 45          | 90           | 15    |
    And today's circuit on route "MR-NORTH" has been planned
    And a dispatched trip "TRIP-M1" on lorry "MH-01-TL-0001" from "SUP-1" to "PLANT"
    And the circuit on route "MR-NORTH" is carried by trip "TRIP-M1"
    And lorry "MH-01-TL-0001" reports from "SUP-1" 10 minutes into the circuit
    And lorry "MH-01-TL-0001" reports between "SUP-1" and "SUP-2" 30 minutes into the circuit
    And lorry "MH-01-TL-0001" reports from "SUP-2" 60 minutes into the circuit
    And lorry "MH-01-TL-0001" reports between "SUP-2" and "PLANT" 90 minutes into the circuit
    And lorry "MH-01-TL-0001" reports from "PLANT" 150 minutes into the circuit
    And the circuit's positions are ingested
    When every geofence crossing of trip "TRIP-M1" is delivered again
    Then the circuit on route "MR-NORTH" is "COMPLETED"
    And the circuit on route "MR-NORTH" was charged 6500.00 for 2 suppliers visited
    And no milk run alerts were raised for route "MR-NORTH"

  # ---------------------------------------------------------- recorded by hand

  Scenario: With the tracker silent, the circuit is recorded by hand under the same rules
    Given a milk run route "MR-NORTH" to "PLANT" that left 6 hours ago, daily, at 5000.00 per run and 750.00 per stop, calling at:
      | supplier | opens after | closes after | dwell |
      | SUP-1    | 0           | 30           | 15    |
      | SUP-2    | 45          | 90           | 15    |
      | SUP-3    | 105         | 150          | 15    |
    And today's circuit on route "MR-NORTH" has been planned
    And a dispatched trip "TRIP-M1" on lorry "MH-01-TL-0001" from "SUP-1" to "PLANT"
    And the circuit on route "MR-NORTH" is carried by trip "TRIP-M1"
    When stop 1 of the circuit on route "MR-NORTH" is recorded as reached 5 minutes into the circuit
    And stop 1 of the circuit on route "MR-NORTH" is recorded as left 25 minutes into the circuit
    And 800 kg is recorded as collected at stop 1 of the circuit on route "MR-NORTH"
    And stop 2 of the circuit on route "MR-NORTH" is skipped because "Supplier closed for stock-take"
    And stop 3 of the circuit on route "MR-NORTH" is recorded as reached 160 minutes into the circuit
    And the circuit on route "MR-NORTH" is recorded as reaching the plant 210 minutes into the circuit
    Then the circuit on route "MR-NORTH" is "COMPLETED"
    And stop 2 of the circuit on route "MR-NORTH" is "SKIPPED"
    And a skipped-supplier alert was raised for stop 2 of the circuit on route "MR-NORTH"
    And a late-window alert of 10 minutes was raised for stop 3 of the circuit on route "MR-NORTH"
    And the circuit on route "MR-NORTH" was charged 6500.00 for 2 suppliers visited
    And the circuit on route "MR-NORTH" collected 800 kg in total

  Scenario: Stops are not recorded on a circuit nobody has started
    Given a milk run route "MR-NORTH" to "PLANT" that left 6 hours ago, daily, at 5000.00 per run and 750.00 per stop, calling at:
      | supplier | opens after | closes after | dwell |
      | SUP-1    | 0           | 30           | 15    |
    And today's circuit on route "MR-NORTH" has been planned
    When stop 1 of the circuit on route "MR-NORTH" is recorded as reached 5 minutes into the circuit
    Then the circuit refuses mentioning "only while it is under way"

  # ------------------------------------------------------------ the schedule

  Scenario: Running the planner twice plans each circuit once
    # The planner runs at every wake of a free instance that sleeps after
    # fifteen idle minutes. Twice must mean once.
    Given a milk run route "MR-WEEK" to "PLANT" leaving at "06:30" in "Asia/Kolkata" on "MON,WED,FRI" from "2030-01-07" at 4000.00 per run and 500.00 per stop, calling at:
      | supplier | opens after | closes after | dwell |
      | SUP-1    | 0           | 30           | 15    |
      | SUP-2    | 45          | 90           | 15    |
    When circuits are planned for 7 days from "2030-01-07"
    And circuits are planned for 7 days from "2030-01-07"
    Then the planner planned 3 circuits and then 0
    And route "MR-WEEK" has circuits on "2030-01-07,2030-01-09,2030-01-11"
    And the circuit on route "MR-WEEK" on "2030-01-07" leaves at "2030-01-07T01:00:00Z"
    And the circuit on route "MR-WEEK" on "2030-01-07" calls at 2 suppliers

  Scenario: A new version of a route takes over from its first day
    Given a milk run route "MR-WEEK" to "PLANT" leaving at "06:30" in "UTC" on "MON,WED,FRI" from "2030-01-07" at 4000.00 per run and 500.00 per stop, calling at:
      | supplier | opens after | closes after | dwell |
      | SUP-1    | 0           | 30           | 15    |
      | SUP-2    | 45          | 90           | 15    |
    And a milk run route "MR-WEEK" to "PLANT" leaving at "07:00" in "UTC" on "WED" from "2030-01-09" at 4500.00 per run and 500.00 per stop, calling at:
      | supplier | opens after | closes after | dwell |
      | SUP-1    | 0           | 30           | 15    |
      | SUP-2    | 45          | 90           | 15    |
      | SUP-3    | 105         | 150          | 15    |
    When circuits are planned for 7 days from "2030-01-07"
    Then route "MR-WEEK" has circuits on "2030-01-07,2030-01-09"
    And the circuit on route "MR-WEEK" on "2030-01-07" calls at 2 suppliers
    And the circuit on route "MR-WEEK" on "2030-01-09" calls at 3 suppliers
    And the circuit on route "MR-WEEK" on "2030-01-09" leaves at "2030-01-09T07:00:00Z"
    And the circuit on route "MR-WEEK" on "2030-01-09" is priced at 4500.00 per run

  Scenario: A version cannot be slipped in under the one already in force
    Given a milk run route "MR-WEEK" to "PLANT" leaving at "06:30" in "UTC" on "MON" from "2030-01-07" at 4000.00 per run and 500.00 per stop, calling at:
      | supplier | opens after | closes after | dwell |
      | SUP-1    | 0           | 30           | 15    |
    When a milk run route "MR-WEEK" to "PLANT" leaving at "06:30" in "UTC" on "MON" from "2030-01-07" at 3000.00 per run and 500.00 per stop, calling at:
      | supplier | opens after | closes after | dwell |
      | SUP-1    | 0           | 30           | 15    |
    Then milk run planning refuses mentioning "a successor cannot start on 2030-01-07"
