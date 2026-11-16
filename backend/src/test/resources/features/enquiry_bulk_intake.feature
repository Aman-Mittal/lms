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

@enquiry
Feature: A customer's enquiry is quoted, decided, converted to a draft order, and closed at its deadline
  FR-ORD-029, FR-ORD-030, FR-ORD-031, FR-ORD-002.

  An enquiry has lines, each of which is quoted and then won, partly won or lost.
  Its won lines become one draft order, once however often conversion is asked
  for. Its deadline is fixed when it is made; when it passes, the lines still
  open are cancelled and the lines already won are kept. Orders can also arrive
  in bulk, each item with its own idempotency key and its own result.

  Background:
    Given a tenant "acme" named "Acme Logistics"
    And tenant "acme" has an organisational unit "/acme/" named "Acme HQ" of type "HQ"
    And the tenant scope is "acme" at "/acme/"
    And a trading partner "CUST"
    And a depot "ORIGIN" at 19.100,72.900
    And a depot "DEST-A" at 19.300,73.100
    And a depot "DEST-B" at 19.500,73.300

  Scenario: Won lines become one draft order, and converting again returns it
    Given an enquiry "E1" for "CUST" from "ORIGIN" with lines to "DEST-A" and "DEST-B", 10 EA of 1000 kg each
    When line 1 of enquiry "E1" is decided as WON
    And line 2 of enquiry "E1" is decided as LOST because "a cheaper carrier"
    And enquiry "E1" is converted
    And enquiry "E1" is converted again
    Then enquiry "E1" is "CONVERTED"
    And both conversions of enquiry "E1" name the same draft order with 1 line
    And enquiry "E1" has been audited as "ENQUIRY_CONVERTED" once

  Scenario: An enquiry that is not yet decided is not converted
    Given an enquiry "E2" for "CUST" from "ORIGIN" with lines to "DEST-A" and "DEST-B", 10 EA of 1000 kg each
    When line 1 of enquiry "E2" is decided as WON
    And enquiry "E2" is converted
    Then the enquiry step is refused with "enquiry-not-decided"

  Scenario: A line that is lost says why
    Given an enquiry "E3" for "CUST" from "ORIGIN" with lines to "DEST-A" and "DEST-B", 10 EA of 1000 kg each
    When line 1 of enquiry "E3" is decided as LOST without a reason
    Then the enquiry step is refused with "enquiry-lost-reason-required"

  Scenario: Past its deadline an enquiry loses its open lines and keeps its won ones
    Given an enquiry "E4" for "CUST" from "ORIGIN" with lines to "DEST-A" and "DEST-B", 10 EA of 1000 kg each
    And line 1 of enquiry "E4" is decided as WON
    And a quote of 900 is issued on line 2 of enquiry "E4"
    When the deadline of enquiry "E4" has passed
    And the enquiry deadline sweep runs
    Then line 1 of enquiry "E4" is "WON"
    And line 2 of enquiry "E4" is "CANCELLED"
    And the quote on line 2 of enquiry "E4" is "REVOKED"
    And enquiry "E4" is "PARTIAL"

  Scenario: With nothing won, an enquiry past its deadline is cancelled
    Given an enquiry "E5" for "CUST" from "ORIGIN" with lines to "DEST-A" and "DEST-B", 10 EA of 1000 kg each
    When the deadline of enquiry "E5" has passed
    And the enquiry deadline sweep runs
    Then enquiry "E5" is "CANCELLED"
    And line 1 of enquiry "E5" is "CANCELLED"

  Scenario: One bad item of a bulk batch never fails the others, and a repeat is a duplicate
    When a batch of orders for "CUST" from "ORIGIN" is submitted:
      | key     | destination | quantity |
      | bulk-1  | DEST-A      | 2        |
      | bulk-2  | NOWHERE     | 2        |
      | bulk-3  | DEST-B      | 5        |
    Then item 1 of the batch is "CREATED"
    And item 2 of the batch is "FAILED"
    And item 3 of the batch is "CREATED"
    When the same batch is submitted again
    Then item 1 of the batch is "DUPLICATE"
    And item 2 of the batch is "FAILED"
    And item 3 of the batch is "DUPLICATE"
    And the tenant holds 2 bulk upload orders
