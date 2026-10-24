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
Feature: A disputed freight bill is resolved on the record and a fuel surcharge reaches the next rating, over the wire
  Settlement through the published API only. A vendor's inflated invoice is
  disputed and then settled in the vendor's favour with a reason that stays on
  the bill; the fuel surcharge recorded for a month is applied to the linehaul
  of trips rated afterwards and leaves bills already raised exactly as they
  were. The trips run in a month of their own, months back.

  Background:
    Given the settlement desk is signed in as the demo administrator
    And the settlement desk has an active vendor, a customer and a consignee
    And the settlement desk has lane "A" from 47.10,12.10 to 47.30,12.30 where the vendor charges 4 per kg
    And the settlement desk has lane "B" from 47.50,12.10 to 47.70,12.30 where the vendor charges 4 per kg

  @e2e_money_fuel
  Scenario: A recorded fuel surcharge changes the next rating and no bill already raised
    Given the settlement desk's "FIRST" trip carrying 5000 kg ran on lane "A" 3 months ago on day 12 and was delivered
    Then the settlement desk finds a draft freight bill of 20000 for the "FIRST" trip with no fuel surcharge, made of one base freight line
    When the settlement desk records a fuel surcharge of 10 percent for the month of those trips
    And the administrator of another tenant records a fuel surcharge of 50 percent for that month in its own books
    And the settlement desk's "SECOND" trip carrying 5000 kg then ran on lane "B" 3 months ago on day 13 and was delivered
    Then the settlement desk finds a draft freight bill of 22000 for the "SECOND" trip including 2000 of fuel surcharge at 10 percent of the linehaul
    And the bill of the "FIRST" trip still carries its original 20000 and no fuel surcharge
    And recording the fuel surcharge for that month again at 5 percent corrects the same record

  Scenario: A fuel surcharge needs a month and a percentage and the right to manage tariffs
    Then recording a fuel surcharge without a month is refused with 400 and code "validation-failed"
    And recording a fuel surcharge without a percentage is refused with 400 and code "validation-failed"
    And a user who may read freight bills but not manage tariffs is refused recording a fuel surcharge as forbidden

  Scenario: A disputed bill is resolved with a reason and then settles
    Given the settlement desk's "DISPUTED" trip carrying 5000 kg ran on lane "A" 2 months ago on day 12 and was delivered
    And the settlement desk's "PLAIN" trip carrying 5000 kg then ran on lane "B" 2 months ago on day 13 and was delivered
    Then the settlement desk finds a draft freight bill of 20000 for the "DISPUTED" trip with no fuel surcharge, made of one base freight line
    And the settlement desk finds a draft freight bill of 20000 for the "PLAIN" trip with no fuel surcharge, made of one base freight line
    When the vendor's invoice of 25000 for the "DISPUTED" trip is matched against its bill
    Then the bill of the "DISPUTED" trip is disputed with the claim of 25000 and a variance of 5000, explained by the match
    And resolving the bill of the "DISPUTED" trip because "" is refused with 400 and code "validation-failed"
    And resolving the bill of the "PLAIN" trip because "Nothing to resolve" is refused with 409 and code "bill-not-disputed"
    And resolving a bill that does not exist is refused as not found
    And a user who may read freight bills but not approve them is refused resolving the dispute of the "DISPUTED" trip's bill as forbidden
    And the administrator of another tenant finds no lines for the bill of the "DISPUTED" trip, cannot read it and cannot resolve it
    And asking for the lines of a bill that does not exist lists none
    When the settlement desk resolves the dispute of the "DISPUTED" trip's bill because "Detention agreed with the vendor on a call"
    Then the bill of the "DISPUTED" trip is approved for payment, keeps the claim of 25000 and records the reason "Detention agreed with the vendor on a call"
    And resolving the bill of the "DISPUTED" trip because "Again" is refused with 409 and code "bill-not-disputed"
