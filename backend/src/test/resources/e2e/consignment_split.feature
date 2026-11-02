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

@consignment_split
Feature: One consignment carried by many vehicles, over the wire
  Bulk freight for one consignee is cut as one consignment, and no lorry can
  carry two thousand tonnes. The planner splits it into parts, each with its
  own lorry receipt, and every part goes out on its own load. Everything is a
  request made with the client generated from the contract.

  Background:
    Given the demo administrator is signed in to split consignments
    And an order for 2000 tonnes of coil to one consignee has been cut into one consignment

  Scenario: Two thousand tonnes go out as a hundred parts on a hundred loads
    Given no lorry can take the whole consignment
    When the planner splits it into 100 parts of 20 tonnes
    Then the parts are numbered "-001" to "-100" under the consignment's reference
    And every part is on a load of its own, 100 loads in all
    When 3 of the parts are carried by trips that are dispatched and delivered
    Then the consignment reads split, with 3 of 100 parts delivered
    And a customer's tracking link on the consignment shows it in transit with 3 of 100 parts delivered
    And the order is not yet fulfilled

  Scenario: Parts that do not add up are refused with the difference, and nothing is split
    When the planner splits it into parts weighing 1000 tonnes and 999.5 tonnes
    Then the split is refused as "split-weights-do-not-add-up" naming a difference of "-500" kg
    And the consignment is still planned with no parts

  Scenario: A consignment is split once
    Given the planner splits it into 2 parts of 1000 tonnes
    When the planner splits it into 2 parts of 1000 tonnes
    Then the split is refused as "consignment-not-splittable"

  Scenario: Asking for the parts of a consignment that does not exist is a plain not-found
    Then the parts of a consignment nobody created are not found

  Scenario: A split is announced to a webhook subscriber
    Given a webhook receiver is listening for consignment splits
    When the planner splits it into 2 parts of 1000 tonnes
    Then the receiver is told of the split with both part references
