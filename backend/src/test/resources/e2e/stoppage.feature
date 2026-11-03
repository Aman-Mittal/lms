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

@stoppage
Feature: A lorry that sits still on the road raises a ticket, over the wire
  Control tower stoppage detection, end to end through the published API only.
  A trip in transit whose lorry reports the same spot for longer than the
  threshold, outside every terminal, is an exception that nobody had to
  report: positions arrive over HTTP and a ticket shows on the board.

  Background:
    Given the demo administrator is signed in at the stoppage desk
    And two sites and a dispatched lorry are registered for the stoppage watch

  Scenario: A lorry parked on the roadside for two hours raises one ticket
    When the lorry reports leaving the origin and then sitting at one spot for 2 hours
    Then the trip is in transit
    And one stoppage ticket is on the board for the trip, located at that spot
    When the lorry reports the same spot again
    Then one stoppage ticket is on the board for the trip, located at that spot
