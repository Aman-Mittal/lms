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

Feature: Coordinates move the trip
  Vision document 3.7.

  This is the claim the whole platform makes: a batch of positions arrives, and
  without anybody pressing anything a trip moves from DISPATCHED to IN_TRANSIT
  to AT_DESTINATION. Every context built so far -- terminals with real
  geofences, the trip state machine, the ports between them -- exists so that
  these scenarios can be true.

  The positions below are timed hours apart. That is not decoration: the sanity
  filter rejects a jump that implies an impossible speed, so a test that moved
  a lorry thirty kilometres in one second would be testing the filter rather
  than the geofences.

  Background:
    Given a tenant "acme" named "Acme Logistics"
    And tenant "acme" has an organisational unit "/acme/" named "Acme HQ" of type "HQ"
    And the tenant scope is "acme" at "/acme/"
    And a trading partner "CUST"
    And a trading partner "CONS-A"
    And a depot "ORIGIN" at 19.100,72.900
    And a depot "DEST-A" at 19.300,73.100
    And a carrier "VENDOR-1"
    And a lorry "MH-01-TL-0001" with payload 10000 kg and volume 40 m3
    And lorry "MH-01-TL-0001" has an "INSURANCE" certificate valid for 365 days
    And a lorry driver "Ravi" licensed for 365 days
    And a dispatched trip "TRIP-T1" on lorry "MH-01-TL-0001" from "ORIGIN" to "DEST-A"

  Scenario: Leaving the origin puts the trip in transit
    Given a position for "MH-01-TL-0001" at 19.1000,72.9000 4 hours ago
    And a position for "MH-01-TL-0001" at 19.2000,73.0000 3 hours ago
    When the positions are ingested
    Then 2 positions were accepted
    And trip "TRIP-T1" is "IN_TRANSIT"
    And trip "TRIP-T1" has 2 geofence crossings
    And trip "TRIP-T1" crossed "ORIGIN" as "EXITED"

  Scenario: Entering the destination marks the trip arrived
    # The full loop. Nobody touches the trip -- three coordinates do it.
    Given a position for "MH-01-TL-0001" at 19.1000,72.9000 4 hours ago
    And a position for "MH-01-TL-0001" at 19.2000,73.0000 3 hours ago
    And a position for "MH-01-TL-0001" at 19.3000,73.1000 2 hours ago
    When the positions are ingested
    Then trip "TRIP-T1" is "AT_DESTINATION"
    And trip "TRIP-T1" crossed "DEST-A" as "ENTERED"

  Scenario: Passing a terminal that is not the destination changes nothing
    # A hub or toll plaza on the way is a crossing worth recording and not a
    # state change. Telematics cannot tell the difference; the trip can.
    Given a depot "WAYPOINT" at 19.2000,73.0000
    And a position for "MH-01-TL-0001" at 19.1000,72.9000 4 hours ago
    And a position for "MH-01-TL-0001" at 19.2000,73.0000 3 hours ago
    When the positions are ingested
    Then trip "TRIP-T1" is "IN_TRANSIT"
    And trip "TRIP-T1" crossed "WAYPOINT" as "ENTERED"

  Scenario: A vehicle sitting on a boundary does not flap
    # Consecutive points inside the same geofence are one entry, not one per
    # ping. Without the presence set, a parked lorry would generate a crossing
    # every thirty seconds forever.
    Given a position for "MH-01-TL-0001" at 19.1000,72.9000 4 hours ago
    And a position for "MH-01-TL-0001" at 19.1001,72.9001 3 hours ago
    And a position for "MH-01-TL-0001" at 19.1002,72.9002 2 hours ago
    When the positions are ingested
    Then trip "TRIP-T1" has 1 geofence crossings
    And trip "TRIP-T1" is "DISPATCHED"

  Scenario: One bad point does not lose the good ones alongside it
    # A device reporting a lost fix must not cost the other twenty-nine points
    # in the same flush.
    Given a position for "MH-01-TL-0001" at 19.1000,72.9000 4 hours ago
    And a position for "MH-01-TL-0001" at 0.0000,0.0000 3 hours ago
    And a position for "MH-01-TL-0001" at 19.2000,73.0000 2 hours ago
    When the positions are ingested
    Then 2 positions were accepted
    And 1 position was rejected mentioning "no lock"
    And trip "TRIP-T1" is "IN_TRANSIT"

  Scenario: A lost fix cannot advance the trip
    # The reason the filter exists. Null island is not inside any terminal, so
    # on its own it would read as leaving the origin -- and the customer would
    # be told their goods were on the road.
    Given a position for "MH-01-TL-0001" at 19.1000,72.9000 4 hours ago
    When the positions are ingested
    And a position for "MH-01-TL-0001" at 0.0000,0.0000 3 hours ago
    And the positions are ingested
    Then trip "TRIP-T1" is "DISPATCHED"

  Scenario: Points are processed in device order, not arrival order
    # A device flushing a buffer sends what it has. Processing a later point
    # before an earlier one would make the jump filter compare against the
    # future and the geofence state flap.
    Given a position for "MH-01-TL-0001" at 19.3000,73.1000 2 hours ago
    And a position for "MH-01-TL-0001" at 19.2000,73.0000 3 hours ago
    And a position for "MH-01-TL-0001" at 19.1000,72.9000 4 hours ago
    When the positions are ingested
    Then 3 positions were accepted
    And trip "TRIP-T1" is "AT_DESTINATION"

  Scenario: Straying far from the corridor raises one alert
    Given a position for "MH-01-TL-0001" at 19.1000,72.9000 6 hours ago
    And a position for "MH-01-TL-0001" at 19.1000,74.5000 4 hours ago
    And a position for "MH-01-TL-0001" at 19.1000,74.6000 2 hours ago
    When the positions are ingested
    Then trip "TRIP-T1" has 1 open route deviation
    And the open deviation is more than 100000 m off route

  Scenario: Returning to the corridor closes the alert
    Given a position for "MH-01-TL-0001" at 19.1000,72.9000 8 hours ago
    And a position for "MH-01-TL-0001" at 19.1000,74.5000 6 hours ago
    And a position for "MH-01-TL-0001" at 19.2000,73.0000 3 hours ago
    When the positions are ingested
    Then trip "TRIP-T1" has 0 open route deviations

  Scenario: A vehicle on no trip still has its position kept
    # A lorry reports whether or not it is working. Discarding those points
    # would lose the approach to the yard, which is the stretch a dispatcher
    # actually wants to see.
    Given a lorry "MH-01-TL-0002" with payload 10000 kg and volume 40 m3
    And a position for "MH-01-TL-0002" at 19.5000,73.5000 2 hours ago
    When the positions are ingested
    Then 1 position was accepted
    And lorry "MH-01-TL-0002" was last seen at 19.5000,73.5000

  Scenario: Progress is reported against the destination
    Given a position for "MH-01-TL-0001" at 19.1000,72.9000 4 hours ago
    And a position for "MH-01-TL-0001" at 19.2000,73.0000 3 hours ago
    When the positions are ingested
    Then trip "TRIP-T1" reports progress of about 50 percent
    And trip "TRIP-T1" has an estimated time of arrival

  Scenario: Retention collapses a finished track and keeps its shape
    # What makes a 1 GB database survive a fleet. The raw points go; the line
    # they described stays.
    Given a straight run of 200 positions for "MH-01-TL-0001" between 19.1000,72.9000 and 19.3000,73.1000
    When the positions are ingested
    And the track for trip "TRIP-T1" is compacted
    Then trip "TRIP-T1" has no raw positions left
    And the stored track for trip "TRIP-T1" has fewer than 20 points
    And the stored track for trip "TRIP-T1" is longer than 25000 m

  Scenario: A position for a vehicle on nobody's register is refused
    # Row-level security stops another tenant reading the row, but the foreign
    # key to `vehicle` is checked by the system and does not consult the
    # policy -- so without an explicit check a caller could file positions
    # against somebody else's lorry.
    Given a position for an unregistered vehicle 2 hours ago
    When the positions are ingested
    Then 1 position was rejected mentioning "no such vehicle"
