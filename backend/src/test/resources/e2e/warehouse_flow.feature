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

@warehouse
Feature: Goods are received, put away, picked and handed to transport, over the wire
  Warehousing, end to end through the published API only. An advance shipping
  notice is counted in at the dock with a shortage and some damage, the good
  units are put away to a bin, an order for more than the bin holds is picked
  short, packed into a handling unit and dispatched, and the hand-off becomes a
  consignment ready to go on a lorry. The control tower hears the receipt
  variance and the pick shortfall on its own.

  Background:
    Given the demo administrator is signed in at the warehouse desk
    And a warehouse with a bin of 100 units, a stock item, a consignee and a delivery site are registered

  Scenario: A receipt with a variance is put away, picked short and handed off to planning
    Given an advance shipping notice expects 100 units of the stock item
    When the delivery is counted in with 90 received of which 5 are damaged
    Then the notice is received showing 10 short and 5 damaged
    And the control tower holds a receipt variance ticket about the notice
    When the 85 undamaged units are put away into the bin
    And an outbound order for 90 units is picked, packed and dispatched to the delivery site weighing 420 kg
    Then the order shows 85 picked with 5 short and is back-ordered
    And the control tower holds a pick shortfall ticket about the order
    And the dispatched handling unit became a consignment for the consignee carrying 420 kg, waiting to be loaded
