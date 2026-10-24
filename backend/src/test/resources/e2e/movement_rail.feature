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
Feature: Rail rate cards and the itinerary list, read back over the wire
  A rail carrier's rate card is published for a railhead pair, a successor
  closes it the day before it starts, rail shipments are priced from the card
  in force on their departure day, and the itinerary list shows each shipment's
  derived status. Everything goes through the published API only.

  Background:
    Given the demo administrator is signed in at the rail rate card desk
    And two railheads, a plain site, an active rail carrier and a dormant one are registered for the rate cards

  Scenario: A successor card closes the first, prices later shipments, and the lists show it all
    When the rail carrier publishes a rate card of 500 per wagon and 700 per container between the railheads from ten days ago
    Then the first card reads back by id with its lane, rates and no end date
    When the rail carrier publishes a successor of 600 per wagon from three days ago
    Then the successor closed the first card the day before it starts and is itself in force
    And the rate card list shows the successor before the first card, each once, and pages by cursor
    When three rail-only shipments are planned for six days ago, yesterday and thirty hours ago with no cost given
    Then each rail leg was priced from the card in force on its departure day
    When the second shipment's train is reported placed and the third is driven to arrival
    Then the itinerary list shows the shipments newest first with their derived status, cost and leg count
    And the itinerary list pages by cursor, newest first
    And a cursor the listings never issued is refused with a code

  Scenario: Cards that break the rules, unknown ids, missing permission and another tenant are refused
    When the rail carrier publishes a rate card of 500 per wagon and 700 per container between the railheads from ten days ago
    And the rail carrier publishes a successor of 600 per wagon from three days ago
    And three rail-only shipments are planned for six days ago, yesterday and thirty hours ago with no cost given
    Then a successor that does not start after the incumbent is refused as backdated and changes nothing
    And cards that break the rules are refused with their codes
    And a card with no name or a negative rate is refused as invalid with a code
    And a card, a plan or a shipment that is not there is answered as not found
    And callers lacking the permission are refused as forbidden
    And another tenant's administrator cannot read the cards or the shipments and cannot publish against these railheads
