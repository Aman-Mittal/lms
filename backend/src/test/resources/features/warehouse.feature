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

  What a pick cannot fill stays owed as a back-order, and a later pick fills
  it into a further handling unit. Several orders can be picked together as a
  wave, which never allocates a unit twice and attributes what it could not
  fill to each order. Damaged units are quarantined at receipt until an
  adjustment releases them to a bin or writes them off.

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
    # Damaged units are recorded, and quarantined rather than stocked.
    And the dock of "WH-1" holds 85 of "SKU-A"
    And the quarantine of "WH-1" holds 5 of "SKU-A"
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
    # Shipped short is not finished: the other 20 are still owed.
    And outbound order "OUT-1" is "BACK_ORDERED"
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
    And the quarantine of "WH-1" holds 2 of "SKU-A"
    When the cross-dock unit of ASN "ASN-X" is dispatched as "TRIP-7"
    Then the hand-off of the cross-dock unit of ASN "ASN-X" carried 38 of "SKU-A" as "TRIP-7"
    And only the quarantine of "WH-1" still holds "SKU-A"

  # ------------------------------------------- receipt to loaded truck

  Scenario: A unit handed to transport becomes a consignment that is put on a lorry
    # One unit, one full-truckload consignment, for the consignee and to the
    # destination the dispatch named, weighing what the dock scale said.
    Given a trading partner "CONS-A"
    And a depot "DEST-A" at 19.30,73.10
    And a lorry "MH-01-TL-0001" with payload 20000 kg and volume 60 m3
    And 30 of "SKU-A" have been received into "WH-1" and put away in bin "A-01"
    And an outbound order "OUT-1" from "WH-1" for 30 of "SKU-A"
    And outbound order "OUT-1" is picked
    And outbound order "OUT-1" is packed into handling unit "HU-1"
    When handling unit "HU-1" is dispatched as "LOAD-9" to "DEST-A" for "CONS-A" weighing 420 kg
    Then handling unit "HU-1" became one consignment for "CONS-A" at "DEST-A" carrying 420 kg
    And the consignment of handling unit "HU-1" is waiting to be loaded
    # The outbox delivers at least once.
    When the hand-off of handling unit "HU-1" is delivered again
    Then handling unit "HU-1" became one consignment for "CONS-A" at "DEST-A" carrying 420 kg
    When a load "LOAD-9" is opened at warehouse "WH-1" on lorry "MH-01-TL-0001"
    And the consignment of handling unit "HU-1" is put on load "LOAD-9"
    Then load "LOAD-9" holds 1 consignments
    And load "LOAD-9" carries 420 kg

  Scenario: Each unit shipped against one order is its own consignment
    # A short shipment now and the back-order later are two units, two lorry
    # receipts. Deduplication is per unit, never per order.
    Given a trading partner "CONS-A"
    And a depot "DEST-A" at 19.30,73.10
    And 30 of "SKU-A" have been received into "WH-1" and put away in bin "A-01"
    And an outbound order "OUT-1" from "WH-1" for 50 of "SKU-A"
    And outbound order "OUT-1" is picked
    And outbound order "OUT-1" is packed into handling unit "HU-1"
    When handling unit "HU-1" is dispatched as "LOAD-9" to "DEST-A" for "CONS-A" weighing 420 kg
    Then outbound order "OUT-1" is "BACK_ORDERED"
    And outbound order "OUT-1" became 1 consignments
    Given 20 of "SKU-A" have been received into "WH-1" and put away in bin "A-01"
    When outbound order "OUT-1" is picked
    And outbound order "OUT-1" is packed into handling unit "HU-2"
    And handling unit "HU-2" is dispatched as "LOAD-10" to "DEST-A" for "CONS-A" weighing 280 kg
    Then the warehouse accepts it
    And outbound order "OUT-1" is "DISPATCHED"
    And outbound order "OUT-1" became 2 consignments
    And handling unit "HU-1" became one consignment for "CONS-A" at "DEST-A" carrying 420 kg
    And handling unit "HU-2" became one consignment for "CONS-A" at "DEST-A" carrying 280 kg
    When the hand-off of handling unit "HU-1" is delivered again
    And the hand-off of handling unit "HU-2" is delivered again
    Then outbound order "OUT-1" became 2 consignments

  Scenario: A unit that leaves without a destination is not planning's to move
    # Collected by the customer, or on somebody else's lorry.
    Given 10 of "SKU-A" have been received into "WH-1" and put away in bin "A-01"
    And an outbound order "OUT-1" from "WH-1" for 10 of "SKU-A"
    And outbound order "OUT-1" is picked
    And outbound order "OUT-1" is packed into handling unit "HU-1"
    When handling unit "HU-1" is dispatched as "COLLECTED"
    Then handling unit "HU-1" became no consignment

  # ------------------------------------------------------------ back-orders

  Scenario: A back-order is filled by a later pick and shipped as a further unit
    Given 30 of "SKU-A" have been received into "WH-1" and put away in bin "A-01"
    And an outbound order "OUT-1" from "WH-1" for 50 of "SKU-A"
    And outbound order "OUT-1" is picked
    And outbound order "OUT-1" is packed into handling unit "HU-1"
    And handling unit "HU-1" is dispatched as "LOAD-1"
    And outbound order "OUT-1" is "BACK_ORDERED"
    And 25 of "SKU-A" have been received into "WH-1" and put away in bin "A-01"
    When outbound order "OUT-1" is picked
    Then the warehouse accepts it
    And outbound order "OUT-1" is "PICKED"
    And outbound order "OUT-1" line 1 was picked 50 with 0 short
    And a filled back-order of 20 was published for outbound order "OUT-1" line 1
    # The shortfall was the first pick's; filling it publishes no new one.
    And a pick shortfall of 20 was published for outbound order "OUT-1"
    And bin "A-01" of "WH-1" holds 5 of "SKU-A"
    When outbound order "OUT-1" is packed into handling unit "HU-2"
    Then handling unit "HU-2" is "PACKED" holding 20 of "SKU-A"
    When handling unit "HU-2" is dispatched as "LOAD-2"
    Then the hand-off of handling unit "HU-2" carried 20 of "SKU-A" as "LOAD-2"
    And outbound order "OUT-1" is "DISPATCHED"
    And the ledger for "SKU-A" sums to 5

  Scenario: A back-order filled in part stays owed, and a pick that finds nothing is refused
    Given 10 of "SKU-A" have been received into "WH-1" and put away in bin "A-01"
    And an outbound order "OUT-1" from "WH-1" for 30 of "SKU-A"
    And outbound order "OUT-1" is picked
    When outbound order "OUT-1" is picked
    Then the warehouse refuses mentioning "nothing was picked"
    And outbound order "OUT-1" line 1 was picked 10 with 20 short
    Given 5 of "SKU-A" have been received into "WH-1" and put away in bin "A-01"
    When outbound order "OUT-1" is picked
    Then the warehouse accepts it
    And outbound order "OUT-1" is "SHORT_PICKED"
    And outbound order "OUT-1" line 1 was picked 15 with 15 short
    And no filled back-order was published for outbound order "OUT-1"
    When outbound order "OUT-1" is packed into handling unit "HU-1"
    Then handling unit "HU-1" is "PACKED" holding 15 of "SKU-A"

  Scenario: Cancelling a back-order closes the order and takes it off every pick
    Given 30 of "SKU-A" have been received into "WH-1" and put away in bin "A-01"
    And an outbound order "OUT-1" from "WH-1" for 50 of "SKU-A"
    And outbound order "OUT-1" is picked
    When the back-order of outbound order "OUT-1" is cancelled
    Then the warehouse refuses mentioning "only a back-ordered order"
    Given outbound order "OUT-1" is packed into handling unit "HU-1"
    And handling unit "HU-1" is dispatched as "LOAD-1"
    And outbound order "OUT-1" is "BACK_ORDERED"
    When the back-order of outbound order "OUT-1" is cancelled
    Then the warehouse accepts it
    And outbound order "OUT-1" is "BACK_ORDER_CANCELLED"
    And outbound order "OUT-1" line 1 was picked 30 with 20 short
    When 25 of "SKU-A" have been received into "WH-1" and put away in bin "A-01"
    And outbound order "OUT-1" is picked
    Then the warehouse refuses mentioning "cannot be picked"
    When the back-order of outbound order "OUT-1" is cancelled
    Then the warehouse refuses mentioning "only a back-ordered order"

  Scenario: A back-order waiting in a released wave cannot be cancelled until the wave is picked
    Given 5 of "SKU-A" have been received into "WH-1" and put away in bin "A-01"
    And an outbound order "OUT-1" from "WH-1" for 8 of "SKU-A"
    And outbound order "OUT-1" is picked
    And outbound order "OUT-1" is packed into handling unit "HU-1"
    And handling unit "HU-1" is dispatched as "LOAD-1"
    And outbound orders "OUT-1" are released in "WH-1" as wave "W-1"
    When the back-order of outbound order "OUT-1" is cancelled
    Then the warehouse refuses mentioning "waiting in wave"

  Scenario: Waves are listed newest first and narrowed by status
    Given 20 of "SKU-A" have been received into "WH-1" and put away in bin "A-01"
    And an outbound order "OUT-1" from "WH-1" for 5 of "SKU-A"
    And an outbound order "OUT-2" from "WH-1" for 5 of "SKU-A"
    And outbound orders "OUT-1" are released in "WH-1" as wave "W-1"
    And outbound orders "OUT-2" are released in "WH-1" as wave "W-2"
    And wave "W-1" is picked
    Then listing all pick waves of "WH-1" gives "W-2, W-1"
    And listing RELEASED pick waves of "WH-1" gives "W-2"
    And listing PICKED pick waves of "WH-1" gives "W-1"
    And wave "W-2" is listed holding 1 orders

  Scenario: An order owed nothing is not picked again
    Given 30 of "SKU-A" have been received into "WH-1" and put away in bin "A-01"
    And an outbound order "OUT-1" from "WH-1" for 20 of "SKU-A"
    And outbound order "OUT-1" is picked
    When outbound order "OUT-1" is picked
    Then the warehouse refuses mentioning "cannot be picked"
    And bin "A-01" of "WH-1" holds 10 of "SKU-A"

  # ------------------------------------------------------------ wave picking

  Scenario: A wave takes stock by bin code then lot across its orders and attributes its shortfall per order
    Given warehouse "WH-1" has a bin "B-01" holding at most 50 units
    And 6 of "SKU-A" have been received into "WH-1" and put away in bin "B-01"
    And 4 of "SKU-A" have been received into "WH-1" and put away in bin "A-01"
    And 5 of "SKU-B" have been received into "WH-1" and put away in bin "A-01"
    And an outbound order "OUT-1" from "WH-1" for 7 of "SKU-A"
    And an outbound order "OUT-2" from "WH-1" for 7 of "SKU-A"
    And an outbound order "OUT-3" from "WH-1" for 5 of "SKU-B"
    When outbound orders "OUT-1, OUT-2, OUT-3" are released in "WH-1" as wave "W-1"
    Then the warehouse accepts it
    When wave "W-1" is picked
    Then the warehouse accepts it
    And wave "W-1" is "PICKED" with orders "OUT-1, OUT-2, OUT-3"
    And outbound order "OUT-1" took 4 of "SKU-A" from bin "A-01" and 3 from bin "B-01"
    And outbound order "OUT-2" took 0 of "SKU-A" from bin "A-01" and 3 from bin "B-01"
    And outbound order "OUT-1" is "PICKED"
    And outbound order "OUT-2" is "SHORT_PICKED"
    And outbound order "OUT-2" line 1 was picked 3 with 4 short
    And outbound order "OUT-3" is "PICKED"
    And a pick shortfall of 4 in wave "W-1" was published for outbound order "OUT-2"
    And no pick shortfall was published for outbound order "OUT-1"
    And no pick shortfall was published for outbound order "OUT-3"
    # Ten units of SKU-A in the bins, fourteen wanted: ten picked, never more.
    And bin "A-01" of "WH-1" holds 0 of "SKU-A"
    And bin "B-01" of "WH-1" holds 0 of "SKU-A"

  Scenario: A wave fills a back-order alongside new orders and says so
    Given 5 of "SKU-A" have been received into "WH-1" and put away in bin "A-01"
    And an outbound order "OUT-1" from "WH-1" for 8 of "SKU-A"
    And outbound order "OUT-1" is picked
    And 10 of "SKU-A" have been received into "WH-1" and put away in bin "A-01"
    And an outbound order "OUT-2" from "WH-1" for 4 of "SKU-A"
    When outbound orders "OUT-1, OUT-2" are released in "WH-1" as wave "W-1"
    And wave "W-1" is picked
    Then the warehouse accepts it
    And outbound order "OUT-1" line 1 was picked 8 with 0 short
    And a filled back-order of 3 in wave "W-1" was published for outbound order "OUT-1" line 1
    And outbound order "OUT-2" is "PICKED"
    And bin "A-01" of "WH-1" holds 3 of "SKU-A"

  Scenario: A wave is picked once, and its orders are not picked on their own meanwhile
    Given 10 of "SKU-A" have been received into "WH-1" and put away in bin "A-01"
    And an outbound order "OUT-1" from "WH-1" for 5 of "SKU-A"
    And outbound orders "OUT-1" are released in "WH-1" as wave "W-1"
    When outbound order "OUT-1" is picked
    Then the warehouse refuses mentioning "pick the wave instead"
    When outbound orders "OUT-1" are released in "WH-1" as wave "W-2"
    Then the warehouse refuses mentioning "already waiting in wave"
    When wave "W-1" is picked
    Then the warehouse accepts it
    When wave "W-1" is picked
    Then the warehouse refuses mentioning "already picked"
    And bin "A-01" of "WH-1" holds 5 of "SKU-A"

  Scenario: A wave holds orders of one warehouse that still need picking
    Given a warehouse "WH-2" at 19.200,72.950
    And 10 of "SKU-A" have been received into "WH-1" and put away in bin "A-01"
    And an outbound order "OUT-1" from "WH-1" for 5 of "SKU-A"
    And an outbound order "OUT-2" from "WH-2" for 5 of "SKU-A"
    When outbound orders "OUT-1, OUT-2" are released in "WH-1" as wave "W-1"
    Then the warehouse refuses mentioning "another warehouse"
    When outbound orders "OUT-1, OUT-1" are released in "WH-1" as wave "W-1"
    Then the warehouse refuses mentioning "more than once"
    Given outbound order "OUT-1" is picked
    When outbound orders "OUT-1" are released in "WH-1" as wave "W-1"
    Then the warehouse refuses mentioning "only an open or back-ordered order"

  # ------------------------------------------------------------- quarantine

  Scenario: Damaged units are quarantined at receipt, then released to a bin or written off with a reason
    Given an ASN "ASN-1" into "WH-1" expecting:
      | sku   | quantity |
      | SKU-A | 20       |
    When ASN "ASN-1" is counted in:
      | line | received | damaged |
      | 1    | 20       | 6       |
    Then the quarantine of "WH-1" holds 6 of "SKU-A"
    And the dock of "WH-1" holds 14 of "SKU-A"
    When 4 quarantined "SKU-A" in "WH-1" are released to bin "A-01" because "Outer carton only; contents sound"
    Then the warehouse accepts it
    And the quarantine of "WH-1" holds 2 of "SKU-A"
    And bin "A-01" of "WH-1" holds 4 of "SKU-A"
    And the quarantine change was audited as "QUARANTINE_RELEASED" because "Outer carton only; contents sound"
    And a quarantine "RELEASED" of 4 of "SKU-A" was published
    When 2 quarantined "SKU-A" in "WH-1" are written off because "Crushed beyond repair"
    Then the warehouse accepts it
    And the quarantine of "WH-1" holds 0 of "SKU-A"
    And the quarantine change was audited as "QUARANTINE_WRITTEN_OFF" because "Crushed beyond repair"
    And a quarantine "WRITTEN_OFF" of 2 of "SKU-A" was published
    # 14 on the dock and 4 released into the bin; the 2 written off are gone.
    And the ledger for "SKU-A" sums to 18

  Scenario: Quarantine gives up no more than it holds, never without a reason, and never past a bin's capacity
    Given an ASN "ASN-1" into "WH-1" expecting:
      | sku   | quantity |
      | SKU-A | 60       |
    And ASN "ASN-1" is counted in:
      | line | received | damaged |
      | 1    | 60       | 60      |
    When 61 quarantined "SKU-A" in "WH-1" are released to bin "A-01" because "Inspected"
    Then the warehouse refuses mentioning "Quarantine holds 60"
    When 51 quarantined "SKU-A" in "WH-1" are released to bin "A-01" because "Inspected"
    Then the warehouse refuses mentioning "exceed its capacity"
    When 5 quarantined "SKU-A" in "WH-1" are released to bin "A-01" because " "
    Then the warehouse refuses mentioning "needs a reason"
    When 61 quarantined "SKU-A" in "WH-1" are written off because "Scrapped"
    Then the warehouse refuses mentioning "Quarantine holds 60"
    When 5 quarantined "SKU-A" in "WH-1" are written off because ""
    Then the warehouse refuses mentioning "needs a reason"
    And the quarantine of "WH-1" holds 60 of "SKU-A"
    And bin "A-01" of "WH-1" holds 0 of "SKU-A"
    When a movement taking 61 of "SKU-A" out of the quarantine of "WH-1" is written straight to the ledger
    Then the ledger database refuses it mentioning "stock_balance_never_negative"
    And the quarantine of "WH-1" holds 60 of "SKU-A"

  Scenario: Quarantined units are not stock a pick can take
    Given an ASN "ASN-1" into "WH-1" expecting:
      | sku   | quantity |
      | SKU-A | 10       |
    And ASN "ASN-1" is counted in:
      | line | received | damaged |
      | 1    | 10       | 10      |
    And an outbound order "OUT-1" from "WH-1" for 3 of "SKU-A"
    When outbound order "OUT-1" is picked
    Then outbound order "OUT-1" line 1 was picked 0 with 3 short
    And the quarantine of "WH-1" holds 10 of "SKU-A"

  # --------------------------------------------------------------- listings

  Scenario: ASNs, outbound orders and handling units list newest first, a page at a time, by status
    Given an ASN "ASN-1" into "WH-1" expecting:
      | sku   | quantity |
      | SKU-A | 10       |
    And an ASN "ASN-2" into "WH-1" expecting:
      | sku   | quantity |
      | SKU-A | 5        |
    And an ASN "ASN-3" into "WH-1" expecting:
      | sku   | quantity |
      | SKU-B | 5        |
    And ASN "ASN-1" is counted in:
      | line | received | damaged |
      | 1    | 10       | 0       |
    Then listing EXPECTED ASNs of "WH-1" 1 at a time gives "ASN-3, ASN-2"
    And listing RECEIVED ASNs of "WH-1" 1 at a time gives "ASN-1"
    And listing all ASNs of "WH-1" 2 at a time gives "ASN-3, ASN-2, ASN-1"
    Given 10 of "SKU-A" are put away from the dock into bin "A-01"
    And an outbound order "OUT-1" from "WH-1" for 4 of "SKU-A"
    And an outbound order "OUT-2" from "WH-1" for 20 of "SKU-A"
    And an outbound order "OUT-3" from "WH-1" for 1 of "SKU-B"
    And outbound order "OUT-1" is picked
    And outbound order "OUT-2" is picked
    And outbound order "OUT-1" is packed into handling unit "HU-1"
    And outbound order "OUT-2" is packed into handling unit "HU-2"
    And handling unit "HU-2" is dispatched as "LOAD-1"
    Then listing BACK_ORDERED outbound orders of "WH-1" 1 at a time gives "OUT-2"
    And listing OPEN outbound orders of "WH-1" 1 at a time gives "OUT-3"
    And listing all outbound orders of "WH-1" 2 at a time gives "OUT-3, OUT-2, OUT-1"
    And outbound order "OUT-2" is listed with 20 ordered, 6 picked and 14 back-ordered
    And listing PACKED handling units of "WH-1" 1 at a time gives "HU-1"
    And listing all handling units of "WH-1" 1 at a time gives "HU-2, HU-1"

  # ------------------------------------------------ control tower and access

  Scenario: A wave's shortfall raises one control-tower ticket per short order line
    Given 3 of "SKU-A" have been received into "WH-1" and put away in bin "A-01"
    And an outbound order "OUT-1" from "WH-1" for 4 of "SKU-A" and 4 of "SKU-B"
    And an outbound order "OUT-2" from "WH-1" for 5 of "SKU-A"
    When outbound orders "OUT-1, OUT-2" are released in "WH-1" as wave "W-1"
    And wave "W-1" is picked
    Then the warehouse accepts it
    And the control tower holds 2 "PICK_SHORTFALL" tickets about order "OUT-1"
    And the control tower holds 1 "PICK_SHORTFALL" ticket about order "OUT-2"
    # Filling the back-order later is good news, not a new ticket.
    Given 10 of "SKU-A" have been received into "WH-1" and put away in bin "A-01"
    When outbound order "OUT-2" is picked
    Then the warehouse accepts it
    And the control tower holds 1 "PICK_SHORTFALL" ticket about order "OUT-2"

  Scenario: Waves and stock adjustments are filed only in the caller's subtree
    Given tenant "acme" has an organisational unit "/acme/north/" named "North" of type "REGION"
    And tenant "acme" has an organisational unit "/acme/south/" named "South" of type "REGION"
    And a warehouse "WH-SOUTH" under "/acme/south/"
    And warehouse "WH-SOUTH" has a bin "S-01" holding at most 50 units
    And an ASN "ASN-S" into "WH-SOUTH" expecting:
      | sku   | quantity |
      | SKU-A | 10       |
    And ASN "ASN-S" is counted in:
      | line | received | damaged |
      | 1    | 10       | 4       |
    And 6 of "SKU-A" are put away from the dock into bin "S-01"
    And an outbound order "OUT-S" from "WH-SOUTH" for 2 of "SKU-A"
    And the tenant scope is "acme" at "/acme/north/"
    When outbound orders "OUT-S" are released in "WH-SOUTH" as wave "W-S"
    Then the warehouse refuses it as outside the caller's subtree
    When 1 of "SKU-A" are written off from bin "S-01" because "Counted short"
    Then the warehouse refuses it as outside the caller's subtree
    When 2 quarantined "SKU-A" in "WH-SOUTH" are released to bin "S-01" because "Sound"
    Then the warehouse refuses it as outside the caller's subtree
    When 2 quarantined "SKU-A" in "WH-SOUTH" are written off because "Crushed"
    Then the warehouse refuses it as outside the caller's subtree
    And the quarantine of "WH-SOUTH" holds 4 of "SKU-A"
    And bin "S-01" of "WH-SOUTH" holds 6 of "SKU-A"
    Given the tenant scope is "acme" at "/acme/"
    When outbound orders "OUT-S" are released in "WH-SOUTH" as wave "W-S"
    Then the warehouse accepts it
    When 2 quarantined "SKU-A" in "WH-SOUTH" are written off because "Crushed"
    Then the warehouse accepts it

  Scenario: A customer-portal user lists only the ASNs, orders and units carrying their own goods
    Given a trading partner "CUST-A"
    And a stock item "SKU-C" owned by "CUST-A"
    And an ASN "ASN-1" into "WH-1" expecting:
      | sku   | quantity |
      | SKU-A | 5        |
    And an ASN "ASN-2" into "WH-1" expecting:
      | sku   | quantity |
      | SKU-C | 5        |
    And ASN "ASN-1" is counted in:
      | line | received | damaged |
      | 1    | 5        | 0       |
    And ASN "ASN-2" is counted in:
      | line | received | damaged |
      | 1    | 5        | 0       |
    And 5 of "SKU-A" are put away from the dock into bin "A-01"
    And 5 of "SKU-C" are put away from the dock into bin "A-01"
    And an outbound order "OUT-1" from "WH-1" for 5 of "SKU-A"
    And an outbound order "OUT-2" from "WH-1" for 5 of "SKU-C"
    And outbound order "OUT-1" is picked
    And outbound order "OUT-1" is packed into handling unit "HU-1"
    And outbound order "OUT-2" is picked
    And outbound order "OUT-2" is packed into handling unit "HU-2"
    When the signed-in user is a warehouse portal user for "CUST-A"
    Then listing all ASNs of "WH-1" 1 at a time gives "ASN-2"
    And listing all outbound orders of "WH-1" 1 at a time gives "OUT-2"
    And listing all handling units of "WH-1" 1 at a time gives "HU-2"
    When the signed-in user is tenant staff
    Then listing all ASNs of "WH-1" 1 at a time gives "ASN-2, ASN-1"
    And listing all outbound orders of "WH-1" 1 at a time gives "OUT-2, OUT-1"
    And listing all handling units of "WH-1" 1 at a time gives "HU-2, HU-1"
