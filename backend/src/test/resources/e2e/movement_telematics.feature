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

@e2e_movement
Feature: Where a lorry is and how far it has come, read back over the wire
  The telematics read side, end to end through the published API only.
  Positions are posted over HTTP along a trip, and the latest fix, the track,
  the fleet map, the geofence crossings, the progress and the route deviations
  are read back and checked against what was posted.

  Background:
    Given the demo administrator is signed in at the telematics read desk
    And an origin, a destination, an idle lorry and a lorry on a dispatched trip are registered for the telematics reads

  Scenario: Pings along a trip give the latest position, the track and the fleet map
    When the carrying lorry reports leaving the origin and moving on, with four bad pings mixed in and the batch out of order
    Then three pings are accepted and four are rejected, each with its reason
    And the carrying lorry's latest position is its newest good fix
    And the track holds the good fixes in time order and none of the bad ones
    When the idle lorry reports twice and then a delayed older fix arrives
    Then the fleet map holds the carrying lorry with its trip and the idle lorry without one, in registration order
    And the fleet map shows the idle lorry at its newest fix and not the delayed one
    When the carrying lorry reports arriving at the destination
    Then the carrying lorry's latest position is the destination fix

  Scenario: Crossings and progress follow the trip from the origin to the destination
    When the carrying lorry reports leaving the origin, passing the midpoint and reaching three-quarters of the way
    Then the crossings show the lorry entering and then leaving the origin and the trip in transit
    And the progress reports the distance still to go and the completion implied by the latest fix
    When the carrying lorry reports arriving at the destination
    Then the crossings end with the lorry entering the destination and the trip is at its destination
    And the progress reports the trip as complete with nothing left to go

  Scenario: A lorry far off its corridor is an open deviation until it comes back
    Given a second lorry is dispatched from the same origin to a second destination for the telematics reads
    When the on-route lorry reports along the corridor and the second lorry reports thirty kilometres beyond the destination
    Then only the second lorry's trip is an open deviation, beyond the corridor, at the fix that opened it
    And the second lorry's progress says it is off route by that distance and the first lorry's says it is not
    And the control tower raises one route deviation ticket for the second lorry's trip and none for the first
    When the second lorry reports back on the corridor
    Then the deviation is no longer open and the second lorry's progress says it is on route

  Scenario: Unknown ids, bad batches, missing permission and another tenant are all refused
    Then a vehicle that never reported and a trip that does not exist are answered as the contract says
    And a batch with no pings and a ping with no time are refused as invalid with a code
    And a caller without the ingest permission is refused as forbidden and the lorry stays unseen
    When the carrying lorry reports leaving the origin, passing the midpoint and reaching three-quarters of the way
    Then another tenant's administrator cannot see the lorry, its trip, its track or its crossings
