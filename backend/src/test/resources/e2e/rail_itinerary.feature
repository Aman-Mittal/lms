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

@rail
Feature: A road, rail and road shipment is planned and walked, over the wire
  Multimodal freight, end to end through the published API only. The
  shipment's itinerary is three legs: a lorry to the first railhead, a train
  between two railheads, a lorry to the consignee. Road legs follow their
  trips; the rail leg moves by milestones reported over the API and, planned
  without a cost, is priced from the rail carrier's rate card. Every leg is
  costed and its carrier paid separately, so the itinerary reports its cost by
  carrier.

  Background:
    Given the demo administrator is signed in at the rail desk
    And a corridor of two railheads with a first-mile and a last-mile site is registered
    And two road carriers and a rail carrier are active
    And the rail carrier charges 2000 per wagon between the two railheads
    And trips are assigned for the first mile to the railhead and the last mile from it
    And the shipment is planned as road, rail and road with the rail leg priced from the rate card

  Scenario: A road-rail-road shipment walks end to end and is costed by carrier
    When the first-mile lorry leaves the origin 48 hours ago and delivers to the railhead 40 hours ago
    And the train is reported placed, loaded and departed, then arrived and released
    And the last-mile lorry leaves the second railhead 10 hours ago and delivers 4 hours ago
    Then the itinerary is completed over 3 legs with no missed connection
    And the rail leg was priced at 80000 from the rate card
    And the cost by carrier is 12000 for the first road carrier, 80000 for rail and 9000 for the second road carrier

  Scenario: Releasing the train raises the rail carrier's freight bill, matched like any other
    When the first-mile lorry leaves the origin 48 hours ago and delivers to the railhead 40 hours ago
    And the train is reported placed, loaded and departed, then arrived and released
    Then a rail freight bill of 80000 is published for the rail carrier and the rail leg
    When the rail carrier invoices 81000 against it
    Then the rail freight bill is approved for payment
