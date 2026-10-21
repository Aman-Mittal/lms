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

@clientbilling
Feature: A 3PL bills its client for a month of warehousing, over the wire
  Third-party logistics, end to end through the published API only. The
  provider publishes a client contract, the warehouse reports what it did for
  the client as service usage, and the monthly billing run turns last month's
  usage into one invoice with a line per charge. Resent usage is charged once
  and running the month again returns the same invoice.

  Background:
    Given the demo administrator is signed in at the client-billing desk
    And a client holds a contract charging 12 per pallet-day of storage, 25 per pallet in, 30 per pallet out and 2 per label, with 18 percent tax on warehousing

  Scenario: A month of warehousing becomes one invoice with a line per charge
    # In: 40 x 25 = 1000. Storage: 300 x 12 = 3600. Out: 35 x 30 = 1050.
    # Labels: 200 x 2 = 400.
    When the warehouse reports 40 pallets in, 300 pallet-days stored, 35 pallets out and 200 labels for last month
    And the warehouse resends its report of the 40 pallets in
    And last month is billed for the client
    Then the client has 1 invoice for last month totalling 7139 over 4 lines
    And the invoice has a "STORAGE" line of 3600 and its lines add up to the taxable amount
    And the invoice carries the "HANDLING_IN" line of 1000 too
    And the invoice carries the "HANDLING_OUT" line of 1050 too
    And the invoice carries the "VALUE_ADDED" line of 400 too
    And the invoice shows 1089 of tax on 6050 taxable, and its "STORAGE" line carries 648 of tax at 18 percent
    When last month is billed for the client again
    Then the client has 1 invoice for last month totalling 7139 over 4 lines

  Scenario: A credit note corrects an issued invoice and the statement follows
    # Tax is 18 percent of each line: 180 + 648 + 189 + 72 = 1089, so the
    # invoice is 7139. Crediting 100 of handling in reverses 18 of tax.
    Given the warehouse reports 40 pallets in, 300 pallet-days stored, 35 pallets out and 200 labels for last month
    And last month is billed for the client
    When a credit note of 100 is issued against the "HANDLING_IN" line for "Pallets miscounted at the dock"
    Then the credit note reverses 18 of tax for a total of 118 and the invoice shows 118 credited
    And the credit note is refused when it asks for more than the "HANDLING_IN" line charged
    And the client statement lists the invoice then the credit note and ends at a balance of 7021
