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

@consignment_maintenance
Feature: Correcting and cancelling one consignment, over the wire
  What a consignment carries is fixed when it is cut from its order, but what
  is said about it -- who to call at the consignee, their reference, the
  client's reference, the delivery window -- can be corrected while it waits
  to be loaded. A consignment, or one order line, can also be cancelled
  without cancelling the whole order. Everything is a request made with the
  client generated from the contract.

  Background:
    Given the demo administrator is signed in to maintain consignments
    And an order for two consignees has been cut into two consignments

  Scenario: The consignee's contact is corrected, but the weight cannot be
    When the planner gives the first consignment a contact, a client reference and a delivery window
    Then the consignment reads back with them, at a new version
    And its history shows the amendment
    When the planner tries to change the first consignment's weight
    Then the amendment is refused as "consignment-contents-fixed" naming "totalDeadWeightKg"

  Scenario: A consignment on a load is cancelled, the load shrinks, and its line is planned again
    Given both consignments are on one load
    And a webhook receiver is listening for consignment cancellations
    When the planner cancels the first consignment because "Consignee closed for stocktake"
    Then the load carries only the second consignment's weight
    And the order is partially planned, with the first consignment's line waiting again
    And the receiver is told of the cancellation with the reason
    When the planner cancels the first consignment again
    Then the cancellation is refused as "consignment-already-cancelled"
    When the order is planned again
    Then a new consignment carries the freed line

  Scenario: Cancelling one order line cancels the consignment it is on
    When the planner cancels the order's first line
    Then the first consignment is cancelled and the second is untouched
    And the order counts one line

  Scenario: Many consignments are cancelled at once, each answered on its own
    Given the second consignment has already been cancelled
    When the planner cancels both consignments together
    Then the first is cancelled and the second is refused as "consignment-already-cancelled"
