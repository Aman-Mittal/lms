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
Feature: Milk-run routes and skipped stops, read and driven over the wire
  A milk-run route is published in versions, read back by id, listed by code
  and paged, and its stops read in circuit order. A stop of a generated
  circuit is skipped with a reason: the stop, the circuit's outcome and price,
  and the control tower ticket all follow. Everything goes through the
  published API only.

  Background:
    Given the demo administrator is signed in at the milk-run route desk
    And two supplier sites, a plant and an active carrier are registered for the route reads

  Scenario: Route versions, their stops and the route list read back as published
    When a daily route and a Monday-Wednesday-Friday route are published, and the daily one gets a successor from today
    Then each route reads back by id with its plant, schedule, price and validity
    And the successor closed the first version the day before and is itself in force
    And the stops of each version read back in circuit order with their windows and terminals
    And the route list holds the versions in force by code and not the superseded one, and pages by cursor

  Scenario: Routes that break the rules, unknown ids, missing permission and another tenant are refused
    When a daily route and a Monday-Wednesday-Friday route are published, and the daily one gets a successor from today
    Then routes that break the rules are refused with their codes and change nothing
    And a route with no stops, no code or a negative rate is refused as invalid with a code
    And a route that is not there is answered as not found and has no stops
    And callers without the read or plan permission are refused as forbidden
    And another tenant's administrator cannot read the routes or their stops and cannot use these terminals

  Scenario: A skipped supplier is a skipped stop, a ticket, and a cheaper circuit
    Given a daily route is published calling at both suppliers and its circuit for the day is generated
    Then skipping a stop of the circuit before it has started is refused as a conflict and changes nothing
    When a dispatched trip carries the circuit
    Then a skip with no reason, for a stop the circuit does not have, or for a circuit that does not exist is refused
    And a caller who may only read, and another tenant's administrator, cannot skip a stop
    When the second supplier is skipped with a reason
    Then the second stop is skipped with the reason, the first is still waiting and the circuit is under way
    And skipping the same stop again is refused as a conflict
    And the control tower raises one skipped-stop ticket for the circuit naming the route, the stop and the reason
    When the first supplier is visited, collected from and left, and the circuit reaches the plant
    Then the circuit is completed having visited one supplier and skipped one, charged the run rate plus one stop fee
    And a stop that was visited and a stop of a completed circuit can no longer be skipped

  Scenario: A supplier already reached cannot be skipped while the circuit is under way
    Given a daily route is published calling at both suppliers and its circuit for the day is generated
    When a dispatched trip carries the circuit
    Then a stop that was visited cannot be skipped while the circuit is under way
