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

Feature: The yard gate, gate passes and a trip's ordered stops
  Issue #223.

  Every vehicle that comes through a gate is in the register, a trip's or a
  visitor's, and is in it once: a second entry while one is open is refused.
  A gate pass can be required to enter. A trip has an ordered list of stops,
  derived from what its load delivers to, that people and geofence crossings
  move; reaching one ahead of an earlier one is recorded and flagged, not refused.

  Background:
    Given a tenant "acme" named "Acme Logistics"
    And tenant "acme" has an organisational unit "/acme/" named "Acme HQ" of type "HQ"
    And the tenant scope is "acme" at "/acme/"
    And a trading partner "CUST"
    And a trading partner "CONS-A"
    And a depot "ORIGIN" at 19.10,72.90
    And a depot "MIDWAY" at 19.20,73.00
    And a depot "DEST-A" at 19.30,73.10
    And a carrier "VENDOR-1"
    And a lorry "MH-01-YD-0001" with payload 10000 kg and volume 40 m3

  Scenario: A lorry enters, cannot enter twice, and leaves
    When lorry "MH-01-YD-0001" enters "ORIGIN" for "MAINTENANCE"
    Then 1 vehicle is inside "ORIGIN"
    When lorry "MH-01-YD-0001" enters "ORIGIN" for "MAINTENANCE"
    Then entering is refused with "gate-entry-already-open"
    And 1 vehicle is inside "ORIGIN"
    When the visit of "MH-01-YD-0001" ends
    Then 0 vehicles are inside "ORIGIN"
    And the visit of "MH-01-YD-0001" is closed with seal "S-9"
    When the visit of "MH-01-YD-0001" ends again
    Then entering is refused with "gate-visit-closed"
    When lorry "MH-01-YD-0001" enters "ORIGIN" for "RETURN"
    Then 1 vehicle is inside "ORIGIN"

  Scenario: A visitor's lorry is known by the number typed
    When visitor lorry "up 32 ab 9999" enters "DEST-A" for "VISITOR"
    And visitor lorry "UP32AB9999" enters "DEST-A" for "VISITOR"
    Then entering is refused with "gate-entry-already-open"
    And 1 vehicle is inside "DEST-A"

  Scenario: Where the unit requires a pass, entry needs a valid one that is used up by it
    Given the unit requires a gate pass to enter
    When lorry "MH-01-YD-0001" enters "ORIGIN" for "MAINTENANCE"
    Then entering is refused with "gate-pass-required"
    And 0 vehicles are inside "ORIGIN"
    Given a gate pass "GP-100" for lorry "MH-01-YD-0001" valid for 4 hours
    When lorry "MH-01-YD-0001" enters "ORIGIN" for "MAINTENANCE" on pass "GP-100"
    Then 1 vehicle is inside "ORIGIN"
    And the visit of "MH-01-YD-0001" used pass "GP-100"
    When the visit of "MH-01-YD-0001" ends
    And lorry "MH-01-YD-0001" enters "ORIGIN" for "MAINTENANCE" on pass "GP-100"
    Then entering is refused with "gate-pass-used"

  Scenario: A trip's stops come from what its load delivers to, in order
    Given an awarded load "LOAD-Y1" of 5000 kg from "ORIGIN" to "CONS-A" dropping at "MIDWAY" then "DEST-A" on lorry "MH-01-YD-0001" for "VENDOR-1"
    And a trip "TRIP-Y1" is raised against load "LOAD-Y1" and assigned lorry "MH-01-YD-0001"
    Then trip "TRIP-Y1" has 3 stops: "PICKUP ORIGIN, DROP MIDWAY, DROP DEST-A"
    And stop 1 of trip "TRIP-Y1" is "PLANNED"

  Scenario: Stops are reached and left by hand, in or out of order
    Given an awarded load "LOAD-Y2" of 5000 kg from "ORIGIN" to "CONS-A" dropping at "MIDWAY" then "DEST-A" on lorry "MH-01-YD-0001" for "VENDOR-1"
    And a trip "TRIP-Y2" is raised against load "LOAD-Y2" and assigned lorry "MH-01-YD-0001"
    When stop 1 of trip "TRIP-Y2" is reached
    And stop 3 of trip "TRIP-Y2" is reached
    Then stop 3 of trip "TRIP-Y2" is "ARRIVED"
    And stop 3 of trip "TRIP-Y2" is flagged out of order
    When stop 2 of trip "TRIP-Y2" is reached
    Then stop 2 of trip "TRIP-Y2" is not flagged out of order
    When stop 2 of trip "TRIP-Y2" is reached
    Then the stop change is refused with "trip-stop-illegal-transition"
    When stop 2 of trip "TRIP-Y2" is left
    And stop 3 of trip "TRIP-Y2" is left
    Then stop 2 of trip "TRIP-Y2" is "DEPARTED"
    And stop 3 of trip "TRIP-Y2" is "COMPLETED"

  Scenario: A stop is skipped with a reason, and the stops after it are not ahead of it
    Given an awarded load "LOAD-Y3" of 5000 kg from "ORIGIN" to "CONS-A" dropping at "MIDWAY" then "DEST-A" on lorry "MH-01-YD-0001" for "VENDOR-1"
    And a trip "TRIP-Y3" is raised against load "LOAD-Y3" and assigned lorry "MH-01-YD-0001"
    When stop 2 of trip "TRIP-Y3" is skipped because "customer closed"
    And stop 1 of trip "TRIP-Y3" is reached
    And stop 3 of trip "TRIP-Y3" is reached
    Then stop 2 of trip "TRIP-Y3" is "SKIPPED"
    And stop 2 of trip "TRIP-Y3" records the reason "customer closed"
    And stop 3 of trip "TRIP-Y3" is not flagged out of order
    When stop 2 of trip "TRIP-Y3" is reached
    Then the stop change is refused with "trip-stop-illegal-transition"
