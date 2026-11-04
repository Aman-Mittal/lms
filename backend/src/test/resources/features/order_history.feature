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

@order-history
Feature: Orders are numbered, announce their lifecycle and can be read back
  An order raised without a number takes the next one from its tenant's
  sequence for the year, ORD-<year>-<six digits>; a number the caller states is
  kept and must be unused. Raising, validating, amending, cancelling and
  fulfilling an order are each published as an event, once, through the
  publication registry. Every transition the audit trail records can be read
  back for that record, newest first and a page at a time, only by its own
  tenant.

  Background:
    Given a tenant "acme" named "Acme Logistics"
    And tenant "acme" has an organisational unit "/acme/" named "Acme HQ" of type "HQ"
    And the tenant scope is "acme" at "/acme/"
    And a trading partner "CUST"
    And a trading partner "CUST-2"
    And a trading partner "CONS-A"
    And a depot "ORIGIN" at 19.10,72.90
    And a depot "DEST-A" at 19.30,73.10
    And a lorry "MH-01-OH-0001" with payload 10000 kg and volume 40 m3
    And lorry "MH-01-OH-0001" has an "INSURANCE" certificate valid for 365 days
    And a lorry driver "Ravi" licensed for 365 days
    And a carrier "HAULIER"

  Scenario: Orders raised without a number take the tenant's sequence in turn
    Given an order "N-1" raised without a number for "CUST" from "ORIGIN"
    And an order "N-2" raised with a blank number for "CUST" from "ORIGIN"
    Then order "N-1" is numbered as this year's number 1
    And order "N-2" is numbered as this year's number 2
    And the raising of order "N-1" announced the number it was given

  Scenario: A number already used by hand is skipped, and a stated number must be unused
    Given an order "H-1" raised by hand as this year's number 1 for "CUST" from "ORIGIN"
    And an order "N-3" raised without a number for "CUST" from "ORIGIN"
    Then order "N-3" is numbered as this year's number 2
    When another order is raised by hand under the number of order "N-3"
    Then the second raise is refused with "order-no-taken"

  Scenario: Amending and cancelling an order announce each step once and read back newest first
    Given a tenant "globex" named "Globex Freight"
    And tenant "globex" has an organisational unit "/globex/" named "Globex HQ" of type "HQ"
    And an order "EV-1" for "CUST" from "ORIGIN"
    And order "EV-1" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "EV-1" is validated
    And the editor reads order "EV-1"
    When the editor amends the customer of order "EV-1" to "CUST-2"
    And order "EV-1" is cancelled with the reason "customer withdrew the indent"
    And order "EV-1" is cancelled
    Then order "EV-1" announced "OrderRaised, OrderValidated, OrderAmended, OrderCancelled"
    And the cancellation of order "EV-1" announced the reason "customer withdrew the indent" and the status it left
    And the history of order "EV-1" reads newest first "ORDER_CANCELLED, ORDER_AMENDED, ORDER_VALIDATED, ORDER_RAISED"
    And the newest history entry of order "EV-1" moved status from "VALIDATED" to "CANCELLED"
    And walking the history of order "EV-1" one entry at a time reads the same
    And tenant "globex" sees no history for order "EV-1"

  Scenario: A delivered order announces its fulfilment and its load's history reads back
    Given an order "EV-2" for "CUST" from "ORIGIN"
    And order "EV-2" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "EV-2" is validated
    And consignments are generated for order "EV-2"
    And a load "LOAD-EV-2" is opened at "ORIGIN" on lorry "MH-01-OH-0001"
    And the shipment of order "EV-2" for "CONS-A" is put on load "LOAD-EV-2"
    And load "LOAD-EV-2" is planned
    And trip "TRIP-EV-2" runs load "LOAD-EV-2" for "HAULIER" on lorry "MH-01-OH-0001" driven by "Ravi"
    When trip "TRIP-EV-2" is completed at its destination
    Then order "EV-2" announced "OrderRaised, OrderValidated, OrderFulfilled"
    And the history of order "EV-2" reads newest first "ORDER_FULFILLED, ORDER_FULLY_PLANNED, ORDER_VALIDATED, ORDER_RAISED"
    And the history of load "LOAD-EV-2" reads newest first "LOAD_COMPLETED, LOAD_DISPATCHED, LOAD_AWARDED, LOAD_PLANNED, LOAD_OPENED"
