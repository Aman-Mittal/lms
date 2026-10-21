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

Feature: Stock is counted in, stored, picked and handed off, and never edited
  Issue #21.

  A warehouse is a terminal whose category is WAREHOUSE. Goods arrive against
  an advance shipping notice and are counted at the dock, where short, excess
  and damaged quantities are recorded. They are put away into bins that hold a
  fixed number of units, picked against outbound orders, packed into handling
  units and handed off. A cross-docked receipt goes straight from the dock
  into a handling unit without ever touching a bin.

  Inventory is an append-only ledger of movements. The quantity at a location
  is derived from it, the database refuses negative stock and an overfull bin
  whoever asks, and a movement once written is never changed.

  Background:
    Given a tenant "acme" named "Acme Logistics"
    And tenant "acme" has an organisational unit "/acme/" named "Acme HQ" of type "HQ"
    And the tenant scope is "acme" at "/acme/"
    And a warehouse "WH-1" at 19.100,72.900
    And warehouse "WH-1" has a bin "A-01" holding at most 50 units
    And a stock item "SKU-A" described as "Widgets"
    And a stock item "SKU-B" described as "Gadgets"

  # --------------------------------------------------------------- inbound

  Scenario: A receipt records what was short, what was extra and what was damaged
    Given an ASN "ASN-1" into "WH-1" expecting:
      | sku   | quantity |
      | SKU-A | 100      |
      | SKU-B | 40       |
    When ASN "ASN-1" is counted in:
      | line | received | damaged |
      | 1    | 90       | 5       |
      | 2    | 50       | 0       |
    Then the warehouse accepts it
    And ASN "ASN-1" is "RECEIVED"
    And ASN "ASN-1" line 1 shows 10 short, 0 excess and 5 damaged
    And ASN "ASN-1" line 2 shows 0 short, 10 excess and 0 damaged
    # Damaged units are recorded, not stocked.
    And the dock of "WH-1" holds 85 of "SKU-A"
    And the dock of "WH-1" holds 50 of "SKU-B"
    And 2 receipt variances were published for ASN "ASN-1"

  Scenario: Each line counted in other than as notified raises a minor ticket on the ASN
    Given an ASN "ASN-1" into "WH-1" expecting:
      | sku   | quantity |
      | SKU-A | 100      |
      | SKU-B | 40       |
    When ASN "ASN-1" is counted in:
      | line | received | damaged |
      | 1    | 90       | 5       |
      | 2    | 50       | 0       |
    Then the control tower holds 2 "RECEIPT_VARIANCE" tickets about ASN "ASN-1"
    And those tickets are of severity "P3" on no trip
    And those tickets do not suspend settlement
    When the receipt variances of ASN "ASN-1" are delivered to the control tower again
    Then the control tower holds 2 "RECEIPT_VARIANCE" tickets about ASN "ASN-1"

  Scenario: A line the count does not mention arrived not at all
    Given an ASN "ASN-1" into "WH-1" expecting:
      | sku   | quantity |
      | SKU-A | 20       |
      | SKU-B | 30       |
    When ASN "ASN-1" is counted in:
      | line | received | damaged |
      | 1    | 20       | 0       |
    Then ASN "ASN-1" line 1 shows 0 short, 0 excess and 0 damaged
    And ASN "ASN-1" line 2 shows 30 short, 0 excess and 0 damaged
    And 1 receipt variances were published for ASN "ASN-1"

  Scenario: A delivery is counted in once
    # A second count of the same delivery would put the same goods into stock
    # twice.
    Given an ASN "ASN-1" into "WH-1" expecting:
      | sku   | quantity |
      | SKU-A | 10       |
    And ASN "ASN-1" is counted in:
      | line | received | damaged |
      | 1    | 10       | 0       |
    When ASN "ASN-1" is counted in:
      | line | received | damaged |
      | 1    | 10       | 0       |
    Then the warehouse refuses mentioning "already received"
    And the dock of "WH-1" holds 10 of "SKU-A"

  Scenario: Bins belong to warehouses
    Given a depot "SITE" at 19.500,73.500
    When a bin "Z-01" is added to terminal "SITE"
    Then the warehouse refuses mentioning "not a warehouse"

  # --------------------------------------------------------------- putaway

  Scenario: Putaway beyond a bin's capacity is refused, and nothing moves
    Given an ASN "ASN-1" into "WH-1" expecting:
      | sku   | quantity |
      | SKU-A | 80       |
    And ASN "ASN-1" is counted in:
      | line | received | damaged |
      | 1    | 80       | 0       |
    When 60 of "SKU-A" are put away from the dock into bin "A-01"
    Then the warehouse refuses mentioning "exceed its capacity"
    And bin "A-01" of "WH-1" holds 0 of "SKU-A"
    And the dock of "WH-1" holds 80 of "SKU-A"
    When 50 of "SKU-A" are put away from the dock into bin "A-01"
    Then the warehouse accepts it
    And bin "A-01" of "WH-1" holds 50 of "SKU-A"
    And the dock of "WH-1" holds 30 of "SKU-A"

  Scenario: Capacity counts everything in the bin, whatever the SKU
    Given 45 of "SKU-A" have been received into "WH-1" and put away in bin "A-01"
    And an ASN "ASN-2" into "WH-1" expecting:
      | sku   | quantity |
      | SKU-B | 10       |
    And ASN "ASN-2" is counted in:
      | line | received | damaged |
      | 1    | 10       | 0       |
    When 10 of "SKU-B" are put away from the dock into bin "A-01"
    Then the warehouse refuses mentioning "exceed its capacity"
    When 5 of "SKU-B" are put away from the dock into bin "A-01"
    Then the warehouse accepts it

  Scenario: Only what is on the dock can be put away
    When 1 of "SKU-A" are put away from the dock into bin "A-01"
    Then the warehouse refuses mentioning "dock holds 0"

  # ------------------------------------------------------- pick, pack, ship

  Scenario: A pick takes what the bins hold, records the shortfall, and ships the rest
    Given 30 of "SKU-A" have been received into "WH-1" and put away in bin "A-01"
    And an outbound order "OUT-1" from "WH-1" for 50 of "SKU-A"
    When outbound order "OUT-1" is picked
    Then outbound order "OUT-1" is "SHORT_PICKED"
    And outbound order "OUT-1" line 1 was picked 30 with 20 short
    And a pick shortfall of 20 was published for outbound order "OUT-1"
    And bin "A-01" of "WH-1" holds 0 of "SKU-A"
    When outbound order "OUT-1" is packed into handling unit "HU-1"
    Then handling unit "HU-1" is "PACKED" holding 30 of "SKU-A"
    When handling unit "HU-1" is dispatched as "LOAD-9"
    Then the hand-off of handling unit "HU-1" carried 30 of "SKU-A" as "LOAD-9"
    And outbound order "OUT-1" is "DISPATCHED"
    And no stock of "SKU-A" remains in "WH-1"
    And the ledger for "SKU-A" sums to 0

  Scenario: A short pick raises a minor ticket on the order
    Given 30 of "SKU-A" have been received into "WH-1" and put away in bin "A-01"
    And an outbound order "OUT-1" from "WH-1" for 50 of "SKU-A"
    When outbound order "OUT-1" is picked
    Then the control tower holds 1 "PICK_SHORTFALL" ticket about order "OUT-1"
    And that ticket is of severity "P3" on no trip
    And that ticket does not suspend settlement

  Scenario: A pick the bins can fill is not short
    Given 30 of "SKU-A" have been received into "WH-1" and put away in bin "A-01"
    And an outbound order "OUT-1" from "WH-1" for 20 of "SKU-A"
    When outbound order "OUT-1" is picked
    Then outbound order "OUT-1" is "PICKED"
    And no pick shortfall was published for outbound order "OUT-1"
    And bin "A-01" of "WH-1" holds 10 of "SKU-A"

  Scenario: A handling unit is handed off once
    Given 10 of "SKU-A" have been received into "WH-1" and put away in bin "A-01"
    And an outbound order "OUT-1" from "WH-1" for 10 of "SKU-A"
    And outbound order "OUT-1" is picked
    And outbound order "OUT-1" is packed into handling unit "HU-1"
    And handling unit "HU-1" is dispatched as "LOAD-1"
    When handling unit "HU-1" is dispatched as "LOAD-2"
    Then the warehouse refuses mentioning "already dispatched"

  # ------------------------------------------------- the ledger is the truth

  Scenario: Stock cannot go negative, through the service or around it
    Given 10 of "SKU-A" have been received into "WH-1" and put away in bin "A-01"
    When 11 of "SKU-A" are written off from bin "A-01" because "Counted none"
    Then the warehouse refuses mentioning "negative stock"
    And bin "A-01" of "WH-1" holds 10 of "SKU-A"
    When a movement taking 11 of "SKU-A" out of bin "A-01" is written straight to the ledger
    Then the ledger database refuses it mentioning "stock_balance_never_negative"
    And bin "A-01" of "WH-1" holds 10 of "SKU-A"
    When 4 of "SKU-A" are written off from bin "A-01" because "Crushed by a forklift"
    Then the warehouse accepts it
    And bin "A-01" of "WH-1" holds 6 of "SKU-A"

  Scenario: The ledger is append-only and the balance is never written by hand
    Given 10 of "SKU-A" have been received into "WH-1" and put away in bin "A-01"
    When the latest movement of "SKU-A" is edited in the ledger
    Then the ledger database refuses it mentioning "append-only"
    When the movements of "SKU-A" are deleted from the ledger
    Then the ledger database refuses it mentioning "append-only"
    When the balance of "SKU-A" in bin "A-01" is overwritten directly
    Then the ledger database refuses it mentioning "derived from stock_movement"
    And bin "A-01" of "WH-1" holds 10 of "SKU-A"
    And the ledger for "SKU-A" sums to 10

  # ------------------------------------------------------------ cross-dock

  Scenario: A cross-docked receipt goes straight to an outbound unit without putaway
    Given an ASN "ASN-X" into "WH-1" expecting:
      | sku   | quantity |
      | SKU-A | 40       |
    When ASN "ASN-X" is counted in for cross-dock:
      | line | received | damaged |
      | 1    | 40       | 2       |
    Then the warehouse accepts it
    And ASN "ASN-X" went into a handling unit holding 38 of "SKU-A"
    And ASN "ASN-X" line 1 shows 0 short, 0 excess and 2 damaged
    And the dock of "WH-1" holds 0 of "SKU-A"
    And bin "A-01" of "WH-1" holds 0 of "SKU-A"
    When the cross-dock unit of ASN "ASN-X" is dispatched as "TRIP-7"
    Then the hand-off of the cross-dock unit of ASN "ASN-X" carried 38 of "SKU-A" as "TRIP-7"
    And no stock of "SKU-A" remains in "WH-1"
