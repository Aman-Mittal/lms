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

@credit
Feature: A customer's standing and credit limit gate its orders and its billing
  A customer that has been suspended or blacklisted gets no new or changed
  orders. A customer with a credit limit gets no more business once its
  exposure -- open orders at the contract rate, charges not yet invoiced and
  invoices not yet paid -- would go past that limit, unless someone holding
  the override right lets one named action through. Past the soft limit the
  action goes ahead with a warning. The limit itself changes only when a
  second person approves the request.

  Background:
    Given a tenant "acme" named "Acme Logistics"
    And tenant "acme" has an organisational unit "/acme/" named "Acme HQ" of type "HQ"
    And the tenant scope is "acme" at "/acme/"
    And a trading partner "CUST"
    And a trading partner "CONS-A"
    And a depot "ORIGIN" at 19.10,72.90
    And a depot "DEST-A" at 19.30,73.10
    And a client contract for "CUST" charging 5 per kg

  # ------------------------------------------------------- maker and checker

  Scenario: A credit limit takes effect only when someone else approves it
    When "asha" asks for a credit limit of 1000 with a soft limit of 800 for "CUST"
    Then "CUST" has no credit limit yet
    When "asha" approves the credit limit change for "CUST"
    Then the credit action is refused with "credit-limit-change-self-approval"
    And "CUST" has no credit limit yet
    When "ravi" approves the credit limit change for "CUST"
    Then the credit action succeeds
    And the credit limit of "CUST" is 1000 with a soft limit of 800
    And "CUST" has a "CREDIT_LIMIT_CHANGED" audit entry

  Scenario: A soft limit above the hard limit is refused, and one request is open at a time
    When "asha" asks for a credit limit of 1000 with a soft limit of 1001 for "CUST"
    Then the credit action is refused with "credit-soft-limit-above-limit"
    When "asha" asks for a credit limit of 1000 with a soft limit of 1000 for "CUST"
    Then the credit action succeeds
    When "asha" asks for a credit limit of 2000 with a soft limit of 1500 for "CUST"
    Then the credit action is refused with "credit-limit-change-pending"
    When "ravi" rejects the credit limit change for "CUST"
    Then "CUST" has no credit limit yet

  # ------------------------------------------------------------- the gate

  Scenario: An order is refused past the hard limit, and an override lets it through once
    Given "CUST" has an approved credit limit of 1000 with a soft limit of 600
    And an order "CR-1" for "CUST" from "ORIGIN"
    And order "CR-1" has line 1 of 100 kg of GENERAL for "CONS-A" at "DEST-A"
    And an order "CR-2" for "CUST" from "ORIGIN"
    And order "CR-2" has line 1 of 100 kg of GENERAL for "CONS-A" at "DEST-A"
    When order "CR-1" is put through validation
    Then the credit action succeeds
    And the credit position of "CUST" is "WITHIN" with exposure 500
    When order "CR-2" is put through validation
    Then the credit action succeeds
    And the credit position of "CUST" is "SOFT_EXCEEDED" with exposure 1000
    And "CUST" has a "CREDIT_SOFT_LIMIT_EXCEEDED" audit entry
    Given an order "CR-3" for "CUST" from "ORIGIN"
    When a line of 20 kg is offered to order "CR-3"
    Then the credit action is refused with "credit-limit-exceeded"
    When order "CR-2" is amended to one line of 120 kg
    Then the credit action is refused with "credit-limit-exceeded"
    When a credit override up to 1100 is granted for order "CR-2"
    And order "CR-2" is amended to one line of 120 kg
    Then the credit action succeeds
    And the credit position of "CUST" is "HARD_EXCEEDED" with exposure 1100
    And "CUST" has a "CREDIT_LIMIT_OVERRIDDEN" audit entry
    When order "CR-2" is amended to one line of 140 kg
    Then the credit action is refused with "credit-limit-exceeded"
    When order "CR-2" is amended to one line of 60 kg
    Then the credit action succeeds
    And the credit position of "CUST" is "SOFT_EXCEEDED" with exposure 800

  Scenario: A customer with no credit limit is not held to one
    Given an order "NL-1" for "CUST" from "ORIGIN"
    And order "NL-1" has line 1 of 5000 kg of GENERAL for "CONS-A" at "DEST-A"
    When order "NL-1" is put through validation
    Then the credit action succeeds
    And the credit position of "CUST" is "NO_LIMIT" with exposure 25000

  # -------------------------------------------------------------- standing

  Scenario: A suspended customer's order is not validated and takes no new lines
    Given an order "SU-1" for "CUST" from "ORIGIN"
    And order "SU-1" has line 1 of 100 kg of GENERAL for "CONS-A" at "DEST-A"
    And customer "CUST" is suspended
    When order "SU-1" is put through validation
    Then the credit action is refused with "customer-not-trading"
    And order "SU-1" is "DRAFT"
    When a line of 20 kg is offered to order "SU-1"
    Then the credit action is refused with "customer-not-trading"
