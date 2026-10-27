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

@orderediting
Feature: A sales order can be edited while it is still safe to
  R-07. An order used to be raise, add lines, validate, cancel. A typo in the
  customer or a wrong weight meant cancelling and re-keying.

  A draft is edited freely: its header, and its lines by replacing the set or
  removing one. A validated order that planning has not touched has one
  stricter path, an amendment, which validates it again and records the order
  before and after. Once freight has been cut from an order, or it is cancelled
  or delivered, it cannot be edited at all: consignments were built from demand
  that would no longer match.

  Every edit carries the version the editor read. Two people editing one order
  cannot overwrite each other unseen; the later one is refused and re-reads.

  Background:
    Given a tenant "acme" named "Acme Logistics"
    And tenant "acme" has an organisational unit "/acme/" named "Acme HQ" of type "HQ"
    And the tenant scope is "acme" at "/acme/"
    And a trading partner "CUST"
    And a trading partner "CUST-2"
    And a trading partner "CONS-A"
    And a trading partner "CONS-B"
    And a depot "ORIGIN" at 19.10,72.90
    And a depot "DEST-A" at 19.30,73.10
    And a depot "DEST-B" at 19.50,73.30

  # ------------------------------------------------------------------ drafts

  Scenario: A draft order's customer can be corrected
    Given an order "ED-1" for "CUST" from "ORIGIN"
    And order "ED-1" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And the editor reads order "ED-1"
    When the editor changes the customer of order "ED-1" to "CUST-2"
    Then the edit succeeds
    And the customer of order "ED-1" is "CUST-2"
    And the version of order "ED-1" has moved past the one the editor read
    And the audit trail of order "ED-1" reads "ORDER_RAISED, ORDER_REVISED"

  Scenario: A draft order's lines can be replaced as a set
    Given an order "ED-2" for "CUST" from "ORIGIN"
    And order "ED-2" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "ED-2" has line 2 of 500 kg of GENERAL for "CONS-B" at "DEST-B"
    And the editor reads order "ED-2"
    When the editor replaces the lines of order "ED-2" with line 1 of 2500 kg of GENERAL for "CONS-B" at "DEST-B"
    Then the edit succeeds
    And order "ED-2" carries 1 lines
    And order "ED-2" carries line 1 of 2500 kg
    And the audit entry "ORDER_LINES_REPLACED" of order "ED-2" records 2 lines before and 1 after

  Scenario: A line can be removed from a draft order
    Given an order "ED-3" for "CUST" from "ORIGIN"
    And order "ED-3" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "ED-3" has line 2 of 500 kg of GENERAL for "CONS-B" at "DEST-B"
    And the editor reads order "ED-3"
    When the editor removes line 2 of order "ED-3"
    Then the edit succeeds
    And order "ED-3" carries 1 lines
    And the audit entry "ORDER_LINE_REMOVED" of order "ED-3" records 2 lines before and 1 after

  Scenario: A validated order cannot be edited as a draft
    Given an order "ED-4" for "CUST" from "ORIGIN"
    And order "ED-4" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "ED-4" is validated
    And the editor reads order "ED-4"
    When the editor changes the customer of order "ED-4" to "CUST-2"
    Then the edit is refused with "order-not-editable"
    And the customer of order "ED-4" is "CUST"

  Scenario: Someone without the update permission cannot edit
    Given an order "ED-5" for "CUST" from "ORIGIN"
    And order "ED-5" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And the editor reads order "ED-5"
    And the signed-in user lacks "ORDER_UPDATE"
    When the editor changes the customer of order "ED-5" to "CUST-2"
    Then the edit is refused with "access-denied"
    And the customer of order "ED-5" is "CUST"

  # -------------------------------------------------------------- amendments

  Scenario: A validated order with no consignments can be amended
    Given an order "ED-6" for "CUST" from "ORIGIN"
    And order "ED-6" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "ED-6" has line 2 of 500 kg of GENERAL for "CONS-B" at "DEST-B"
    And order "ED-6" is validated
    And the editor reads order "ED-6"
    When the editor amends order "ED-6" to carry only line 1 of 1200 kg of GENERAL for "CONS-A" at "DEST-A"
    Then the edit succeeds
    And order "ED-6" is "VALIDATED"
    And order "ED-6" carries 1 lines
    And order "ED-6" carries line 1 of 1200 kg
    And the version of order "ED-6" has moved past the one the editor read
    And the audit trail of order "ED-6" reads "ORDER_RAISED, ORDER_VALIDATED, ORDER_AMENDED"
    And the audit entry "ORDER_AMENDED" of order "ED-6" moved status from "VALIDATED" to "VALIDATED"
    And the audit entry "ORDER_AMENDED" of order "ED-6" records 2 lines before and 1 after

  Scenario: A validated order's header can be amended
    Given an order "ED-7" for "CUST" from "ORIGIN"
    And order "ED-7" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "ED-7" is validated
    And the editor reads order "ED-7"
    When the editor amends the customer of order "ED-7" to "CUST-2"
    Then the edit succeeds
    And the customer of order "ED-7" is "CUST-2"
    And order "ED-7" carries 1 lines

  Scenario: An amendment is validated again and a failing one changes nothing
    Given an order "ED-8" for "CUST" from "ORIGIN"
    And order "ED-8" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "ED-8" is validated
    And the editor reads order "ED-8"
    When the editor amends order "ED-8" to carry only line 1 of 800 kg of TOXIC for "CONS-A" at "DEST-A"
    Then the edit is refused with "order-validation-failed"
    And order "ED-8" carries line 1 of 1000 kg
    And the version of order "ED-8" is still the one the editor read
    And the audit trail of order "ED-8" reads "ORDER_RAISED, ORDER_VALIDATED"

  Scenario: An order that is still a draft is edited, not amended
    Given an order "ED-9" for "CUST" from "ORIGIN"
    And order "ED-9" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And the editor reads order "ED-9"
    When the editor amends the customer of order "ED-9" to "CUST-2"
    Then the edit is refused with "order-not-validated"

  Scenario: An order with consignments cannot be amended
    Given an order "ED-10" for "CUST" from "ORIGIN"
    And order "ED-10" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "ED-10" is validated
    And consignments are generated for order "ED-10"
    And the editor reads order "ED-10"
    When the editor amends order "ED-10" to carry only line 1 of 900 kg of GENERAL for "CONS-A" at "DEST-A"
    Then the edit is refused with "order-has-consignments"
    And order "ED-10" carries line 1 of 1000 kg

  Scenario: A cancelled order cannot be amended
    Given an order "ED-11" for "CUST" from "ORIGIN"
    And order "ED-11" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "ED-11" is validated
    And order "ED-11" is cancelled
    And the editor reads order "ED-11"
    When the editor amends the customer of order "ED-11" to "CUST-2"
    Then the edit is refused with "order-not-amendable"

  Scenario: A delivered order cannot be amended
    Given an order "ED-12" for "CUST" from "ORIGIN"
    And order "ED-12" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "ED-12" has been delivered
    And the editor reads order "ED-12"
    When the editor amends the customer of order "ED-12" to "CUST-2"
    Then the edit is refused with "order-not-amendable"

  # -------------------------------------------------------- concurrent edits

  Scenario: Two people editing the same draft cannot overwrite each other
    Given an order "ED-13" for "CUST" from "ORIGIN"
    And order "ED-13" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And the editor reads order "ED-13"
    And a second editor changes the customer of order "ED-13" to "CUST-2"
    When the editor changes the customer of order "ED-13" to "CONS-B"
    Then the edit is refused with "concurrent-modification"
    And the customer of order "ED-13" is "CUST-2"

  Scenario: A stale amendment is refused and changes nothing
    Given an order "ED-14" for "CUST" from "ORIGIN"
    And order "ED-14" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "ED-14" is validated
    And the editor reads order "ED-14"
    And a second editor amends the customer of order "ED-14" to "CUST-2"
    When the editor amends order "ED-14" to carry only line 1 of 900 kg of GENERAL for "CONS-A" at "DEST-A"
    Then the edit is refused with "concurrent-modification"
    And order "ED-14" carries line 1 of 1000 kg
