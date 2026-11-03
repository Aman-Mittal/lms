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
Feature: A 3PL's clients, their charges, storage and portal access, over the wire
  Client billing reads and the client portal, through the published API only.
  A client's contract versions and filed charges are listed, storage is
  computed from the warehouse ledger once and billed from it, a portal user is
  confined to one client's records and refused the margin view, and the margin
  view shows the 3PL what each invoice earned.

  Background:
    Given the billing desk is signed in as the demo administrator

  Scenario: Charges are filed under their month, paged, invoiced and late ones move to the next month
    Given the billing desk has client "A" under a contract charging 12 per pallet-day, 25 per pallet in, 30 per pallet out and 2 per label, with 18 percent tax on warehousing
    And the billing desk has client "B" under a contract charging 12 per pallet-day, 25 per pallet in, 30 per pallet out and 2 per label, with 18 percent tax on warehousing
    When the billing desk records for client "A" 40 pallets in, 300 pallet-days stored, 35 pallets out and 200 labels last month
    Then the billing desk lists 4 charges of last month for client "A" with the quantities reported, none of them invoiced
    And the billing desk pages client "A"'s charges of last month two at a time and sees each of the 4 once
    And the billing desk lists no charges of last month for client "B"
    When the billing desk bills last month for client "A"
    Then the billing desk lists the same 4 charges for client "A" now carrying the invoice
    When the billing desk reports 7 more pallets in for client "A" dated last month after the month was billed
    Then the billing desk lists that late charge for client "A" under the current month, marked late and dated last month

  Scenario: A client's contract versions are listed newest first, the earlier closed by its successor
    Given the billing desk has client "A" under a contract charging 12 per pallet-day, 25 per pallet in, 30 per pallet out and 2 per label, with 18 percent tax on warehousing
    Then the billing desk lists client "A"'s one contract with its rates, tax and the label service, still open
    When the billing desk publishes a successor contract for client "A" from 45 days ago charging 15 per pallet-day
    Then the billing desk lists client "A"'s two contracts newest first, the earlier closed the day before the successor starts
    And the billing desk lists no contracts for a client that does not exist

  Scenario: Storage is computed once from the warehouse ledger and billed from it
    Given the billing desk has client "A" under a contract charging 12 per pallet-day, 25 per pallet in, 30 per pallet out and 2 per label, with 18 percent tax on warehousing
    And the billing desk has client "B" with no contract at all
    And the billing desk has a warehouse holding 25 units of a stock item owned by client "A" from day 1 to day 10 of last month
    And the billing desk also holds 400 units of another stock item owned by client "B" over the same days
    Then last month's storage for client "A" has not been computed yet
    When the billing desk computes last month's storage for client "A"
    Then the computation shows 250 unit-days and 30 pallet-days over every day of last month, with 3 pallets on the first day
    And computing it again answers with the same computation and client "A" still has one storage charge of 30 pallet-days
    And reading client "A"'s storage for last month returns the computation again with 250 unit-days
    And storage recorded by hand for client "A" in a month computed from the ledger is refused
    And storage for the current month is refused for client "A" because the month has not ended
    And storage for client "B" is refused because it holds no contract
    And the invoice for last month of client "A" carries a storage line of 360 on 30 pallet-days and totals "424.80" with tax

  Scenario: Storage cannot be computed from the ledger over storage already recorded by hand
    Given the billing desk has client "A" under a contract charging 12 per pallet-day, 25 per pallet in, 30 per pallet out and 2 per label, with 18 percent tax on warehousing
    When the billing desk records for client "A" 40 pallets in, 300 pallet-days stored, 35 pallets out and 200 labels last month
    Then computing storage from the ledger for client "A" is refused because storage was already recorded by hand

  Scenario: A portal user sees only its own client and never the margins
    Given the billing desk has client "A" under a contract charging 12 per pallet-day, 25 per pallet in, 30 per pallet out and 2 per label, with 18 percent tax on warehousing
    And the billing desk has client "B" under a contract charging 12 per pallet-day, 25 per pallet in, 30 per pallet out and 2 per label, with 18 percent tax on warehousing
    And the billing desk records for client "A" 40 pallets in, 300 pallet-days stored, 35 pallets out and 200 labels last month
    And the billing desk records for client "B" 10 pallets in, 100 pallet-days stored, 5 pallets out and 50 labels last month
    And the billing desk bills last month for client "A"
    And the billing desk bills last month for client "B"
    And a portal user holding the billing and margin read permissions is provisioned but not yet bound to any client
    Then before it is bound the portal user can read the invoices of both client "A" and client "B"
    When the billing desk binds the portal user to client "A"
    Then the portal user sees client "A"'s invoice, statement, charges, contract and storage but is refused client "B"'s with access denied
    And the portal user is refused the margin view as access denied although it holds the margin permission
    When the billing desk binds the portal user to client "B"
    Then the portal user sees client "B"'s invoice, statement, charges, contract and storage but is refused client "A"'s with access denied

  Scenario: Binding a portal user is refused for what is not a client, without a user, and without the right
    Given the billing desk has client "A" under a contract charging 12 per pallet-day, 25 per pallet in, 30 per pallet out and 2 per label, with 18 percent tax on warehousing
    And a portal user holding the billing and margin read permissions is provisioned but not yet bound to any client
    Then binding a user to a partner that is not a customer is refused with 409 and code "client-not-a-customer"
    And binding a user without naming one is refused with 400 and code "validation-failed"
    And a billing user who may only read is refused binding a portal user as forbidden
    And a billing user holding no billing permission is refused contracts, charges, storage, its computation, margins and portal binding as forbidden
    And a billing user who may read billing but not margins is refused the margin view as forbidden

  Scenario: The margin view shows what each invoice earned, a page at a time, net of credit notes
    Given the billing desk has client "A" under a contract charging 12 per pallet-day, 25 per pallet in, 30 per pallet out and 2 per label, with 18 percent tax on warehousing
    And the billing desk has client "B" under a contract charging 12 per pallet-day, 25 per pallet in, 30 per pallet out and 2 per label, with 18 percent tax on warehousing
    And the billing desk records for client "A" 40 pallets in, 300 pallet-days stored, 35 pallets out and 200 labels last month
    And the billing desk records for client "B" 10 pallets in, 100 pallet-days stored, 5 pallets out and 50 labels last month
    And the billing desk bills last month for client "A"
    And the billing desk bills last month for client "B"
    Then the billing desk lists margins for last month showing the whole invoice as receivable and no carrier cost for clients "A" and "B"
    And the billing desk pages the margins of last month one at a time and reaches both client "A" and client "B"
    When the billing desk credits 100 against client "A"'s "HANDLING_IN" line for "Pallets miscounted at the dock"
    Then the margin of client "A" shows 100 credited and a receivable reduced by it

  Scenario: Another tenant's administrator sees nothing of this tenant's clients
    Given the billing desk has client "A" under a contract charging 12 per pallet-day, 25 per pallet in, 30 per pallet out and 2 per label, with 18 percent tax on warehousing
    And the billing desk records for client "A" 40 pallets in, 300 pallet-days stored, 35 pallets out and 200 labels last month
    And the billing desk bills last month for client "A"
    Then the administrator of another tenant sees no contracts, charges, storage or margins of client "A"
