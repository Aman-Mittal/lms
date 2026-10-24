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

@e2e_money_stock
Feature: Warehouse stock is read, corrected and released, over the wire
  The stock side of a warehouse, through the published API only: balances by
  location, the movement ledger and its paging, a count corrected by an
  adjustment, quarantined units released into a bin or written off, handling
  units from packing to dispatch, and who may do any of it. The warehouse, its
  bins and its stock items belong to each scenario alone, so every number
  asserted is exact.

  Background:
    Given the stock desk is signed in as the demo administrator
    And the stock desk has a warehouse with bins "A" of 100 units, "B" of 20 units and "C" of 100 units
    And the stock desk has 3 stock items owned by one customer

  Scenario: Counted stock is listed by location and a recount corrects it on the ledger
    Given the stock desk receives 100 units of the first item with 10 damaged on a fresh notice
    And the stock desk puts 60 units of the first item away into bin "A"
    Then the stock desk lists bin "A" holding 60 of 100 and bin "B" holding 0 of 20
    And the stock desk lists the first item as 60 in bin "A", 30 on the dock and 10 in quarantine
    And asked for bin "A" alone the stock desk lists one balance of 60 units
    And asked for the second item the stock desk lists no balances
    When the stock desk adjusts bin "A" by -5 because "Cycle count found 5 units broken"
    Then the ledger of the first item leads with an adjustment of -5 in bin "A" carrying the reason, made by the signed-in user
    And the stock desk lists bin "A" holding 55 of 100 and bin "B" holding 0 of 20
    And the stock desk lists the first item as 55 in bin "A", 30 on the dock and 10 in quarantine
    When the stock desk adjusts bin "A" by 10 because "Cycle count found a pallet nobody recorded"
    Then the ledger of the first item leads with an adjustment of 10 in bin "A" carrying the reason, made by the signed-in user
    And the ledger of the first item holds 2 adjustments and the earlier receipt rows are untouched
    And the stock desk lists bin "A" holding 65 of 100 and bin "B" holding 0 of 20

  Scenario: A correction that makes no sense is refused and leaves the stock alone
    Given the stock desk receives 100 units of the first item with 0 damaged on a fresh notice
    And the stock desk puts 65 units of the first item away into bin "A"
    Then adjusting bin "A" by -500 because "Too many" is refused with 409 and code "stock-cannot-go-negative"
    And adjusting bin "A" by 0 because "Nothing changed" is refused with 409 and code "adjustment-empty"
    And adjusting bin "A" by 1 because "" is refused with 400 and code "validation-failed"
    And adjusting bin "A" by 1000 because "A truckload" is refused with 409 and code "bin-over-capacity"
    And adjusting an unknown stock item in bin "A" is refused as not found
    And adjusting an unknown bin is refused as not found
    And the stock desk can read bin "A" as still holding 65 units

  Scenario: The movement ledger pages newest first and keeps every row
    Given the stock desk receives 100 units of the first item with 10 damaged on a fresh notice
    And the stock desk puts 60 units of the first item away into bin "A"
    When the stock desk adjusts bin "A" by -5 because "Cycle count found 5 units broken"
    And the stock desk adjusts bin "A" by 10 because "Cycle count found a pallet nobody recorded"
    Then the ledger of the first item pages two rows at a time, newest first, and holds 6 rows in all
    And the ledger of the second item is empty

  Scenario: Notices are listed newest first, by status and a page at a time
    Given the stock desk receives 100 units of the first item with 0 damaged on a fresh notice
    And the stock desk has also filed a notice for 50 more units that has not arrived
    Then the stock desk lists the two notices newest first with the unreceived one leading
    And the stock desk lists only the received notice when asked for received ones and only the other when asked for expected ones
    And the stock desk pages the notices one at a time and the second page holds the older notice

  Scenario: Bins and stock items are listed by code a page at a time
    Then the stock desk pages the bins two at a time by code, ending with bin "C"
    And the stock desk lists its stock items by code and pages them two at a time

  Scenario: Quarantined units are released into a bin or written off, each on the ledger with its reason
    Given the stock desk receives 50 units of the first item with 8 damaged on a fresh notice
    Then the stock desk lists the first item as 0 in bin "A", 42 on the dock and 8 in quarantine
    When the stock desk releases 5 quarantined units of the first item into bin "A" because "Inspected: boxes dented, contents sound"
    Then the release is two adjustments sharing a reference, 5 out of quarantine and 5 into bin "A", both carrying the reason
    And the stock desk lists the first item as 5 in bin "A", 42 on the dock and 3 in quarantine
    And the stock desk lists bin "A" holding 5 of 100 and bin "B" holding 0 of 20
    When the stock desk writes 3 quarantined units of the first item off because "Crushed beyond sale"
    Then the write-off is one adjustment of 3 out of quarantine carrying the reason and no reference
    And the stock desk lists no quarantine balance for the first item
    And the stock desk lists the first item as 5 in bin "A", 42 on the dock and 0 in quarantine

  Scenario: Quarantine releases and write-offs that cannot be honoured are refused
    Given the stock desk receives 50 units of the first item with 8 damaged on a fresh notice
    Then releasing 9 quarantined units into bin "A" because "Too many" is refused with 409 and code "quarantine-stock-insufficient"
    And writing 9 quarantined units off because "Too many" is refused with 409 and code "quarantine-stock-insufficient"
    And releasing 0 quarantined units into bin "A" because "Nothing" is refused with 400 and code "validation-failed"
    And releasing 1 quarantined units into bin "A" because "" is refused with 400 and code "validation-failed"
    And writing 0 quarantined units off because "Nothing" is refused with 400 and code "validation-failed"
    And writing 1 quarantined units off because "" is refused with 400 and code "validation-failed"
    And releasing or writing off an unknown stock item is refused as not found
    And releasing into an unknown bin is refused as not found
    And the stock desk lists the first item as 0 in bin "A", 42 on the dock and 8 in quarantine

  Scenario: A release that would overfill the bin is refused and the units stay in quarantine
    Given the stock desk receives 50 units of the first item with 30 damaged on a fresh notice
    Then releasing 25 quarantined units into bin "B" because "Sound after inspection" is refused with 409 and code "bin-over-capacity"
    And the stock desk lists the first item as 0 in bin "B", 20 on the dock and 30 in quarantine
    When the stock desk releases 20 quarantined units of the first item into bin "B" because "Sound after inspection"
    Then the stock desk lists bin "A" holding 0 of 100 and bin "B" holding 20 of 20
    And the stock desk lists the first item as 20 in bin "B", 20 on the dock and 10 in quarantine

  Scenario: Packed units are listed, read with their contents, dispatched and paged
    Given the stock desk receives 100 units of the first item with 0 damaged on a fresh notice
    And the stock desk puts 100 units of the first item away into bin "A"
    When the stock desk packs an outbound order of 10 units of the first item
    And the stock desk packs an outbound order of 20 units of the first item
    Then the stock desk lists the two packed handling units newest first, made by packing
    And the stock desk pages the handling units one at a time and the second page holds the older one
    And the first handling unit holds 10 units of the first item in its lot
    When the stock desk dispatches the first handling unit to the delivery site weighing 300 kg
    Then the stock desk lists the first handling unit as dispatched and the second as still packed
    And the first handling unit shows its dispatch reference, destination, consignee and 300 kg
    And asking for a handling unit that does not exist is refused as not found

  Scenario: Stock corrections are held to permission, to the warehouse's unit and to the tenant
    Given the stock desk receives 50 units of the first item with 8 damaged on a fresh notice
    And the stock desk puts 20 units of the first item away into bin "A"
    And a stock desk user who may read stock but not adjust it is provisioned
    Then the user who cannot adjust stock is refused every stock correction as forbidden
    And a user who may not read the warehouse is refused every stock listing as forbidden
    And a user who may adjust stock but belongs to a branch beside the warehouse's unit is refused every correction as forbidden
    And the administrator of another tenant sees none of the warehouse's stock, notices, bins or handling units and cannot correct it
