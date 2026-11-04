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

@order-close
Feature: An order is closed by hand, line by line, or within the tenant's tolerance
  Besides cancelling it and having it delivered, an order can be closed. A
  normal close needs every line planned and its freight delivered. A short
  close gives up on what is not yet planned, but waits until freight already
  cut has left. A forced close, held to its own right, ends the order whatever
  its freight is doing. Both of those need a reason. One line can be closed in
  the same ways, and the order follows once nothing else is open. A tenant may
  set a tolerance under which a line counts as delivered, and may turn
  automatic fulfilment off. Consignments cut from an order are never touched
  by closing it.

  Background:
    Given a tenant "acme" named "Acme Logistics"
    And tenant "acme" has an organisational unit "/acme/" named "Acme HQ" of type "HQ"
    And the tenant scope is "acme" at "/acme/"
    And a trading partner "CUST"
    And a trading partner "CONS-A"
    And a trading partner "CONS-B"
    And a depot "ORIGIN" at 19.10,72.90
    And a depot "DEST-A" at 19.30,73.10
    And a depot "DEST-B" at 19.50,73.30
    And a lorry "MH-01-OC-0001" with payload 10000 kg and volume 40 m3
    And lorry "MH-01-OC-0001" has an "INSURANCE" certificate valid for 365 days
    And a lorry driver "Ravi" licensed for 365 days
    And a carrier "HAULIER"

  Scenario: Nothing planned yet: a normal close says why not, a short close gives up the rest
    Given an order "OC-1" for "CUST" from "ORIGIN"
    And order "OC-1" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "OC-1" is validated
    When order "OC-1" is closed NORMAL
    Then the close is refused with "order-not-complete" mentioning "line 1 is not planned"
    When order "OC-1" is closed SHORT
    Then the close is refused with "close-reason-required" mentioning "must say why"
    When order "OC-1" is closed SHORT because "customer needs no more"
    Then order "OC-1" is "CLOSED"
    And order "OC-1" reads closed SHORT because "customer needs no more"
    And line 1 of order "OC-1" reads closed SHORT
    And order "OC-1" announced "OrderRaised, OrderValidated, OrderClosed"
    And the history of order "OC-1" reads newest first "ORDER_CLOSED, ORDER_VALIDATED, ORDER_RAISED"
    When consignments are generated for order "OC-1"
    Then 0 consignments are created

  Scenario: A short close waits for cut freight to leave; a forced one does not, and needs its right
    Given an order "OC-2" for "CUST" from "ORIGIN"
    And order "OC-2" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "OC-2" is validated
    And consignments are generated for order "OC-2"
    When order "OC-2" is closed SHORT because "remainder not wanted"
    Then the close is refused with "order-freight-pending" mentioning "line 1"
    Given the signed-in user lacks "ORDER_FORCE_CLOSE"
    When order "OC-2" is closed FORCE because "customer insolvent"
    Then the close is denied for want of a right
    Given the signed-in user lacks "NOTHING"
    When order "OC-2" is closed FORCE because "customer insolvent"
    Then order "OC-2" is "CLOSED"
    And order "OC-2" reads closed FORCE because "customer insolvent"
    And the shipment of order "OC-2" for "CONS-A" is "PLANNED"

  Scenario: An order that has ended cannot be closed, and the refusal says how it ended
    Given an order "OC-3" for "CUST" from "ORIGIN"
    And order "OC-3" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    When order "OC-3" is closed FORCE because "too early"
    Then the close is refused with "order-not-validated" mentioning "draft"
    Given order "OC-3" is validated
    And order "OC-3" is closed FORCE because "first time"
    When order "OC-3" is closed FORCE because "second time"
    Then the close is refused with "order-already-closed" mentioning "CLOSED"
    Given an order "OC-4" for "CUST" from "ORIGIN"
    And order "OC-4" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "OC-4" is cancelled
    When order "OC-4" is closed FORCE because "after cancelling"
    Then the close is refused with "order-is-cancelled" mentioning "cancelled"

  Scenario: Closing one line leaves the rest to be planned and delivered, and the order follows
    Given an order "OC-5" for "CUST" from "ORIGIN"
    And order "OC-5" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "OC-5" has line 2 of 800 kg of GENERAL for "CONS-B" at "DEST-B"
    And order "OC-5" is validated
    When line 2 of order "OC-5" is closed SHORT because "second drop cancelled"
    Then order "OC-5" is "VALIDATED"
    And line 2 of order "OC-5" reads closed SHORT
    When line 2 of order "OC-5" is closed FORCE because "again"
    Then the close is refused with "order-line-already-closed" mentioning "Line 2"
    When consignments are generated for order "OC-5"
    Then 1 consignments are created
    And order "OC-5" is "FULLY_PLANNED"
    Given a load "LOAD-OC-5" is opened at "ORIGIN" on lorry "MH-01-OC-0001"
    And the shipment of order "OC-5" for "CONS-A" is put on load "LOAD-OC-5"
    And load "LOAD-OC-5" is planned
    And trip "TRIP-OC-5" runs load "LOAD-OC-5" for "HAULIER" on lorry "MH-01-OC-0001" driven by "Ravi"
    When trip "TRIP-OC-5" is completed at its destination
    Then order "OC-5" is "FULFILLED"
    And the history of order "OC-5" reads newest first "ORDER_FULFILLED, ORDER_FULLY_PLANNED, ORDER_LINE_CLOSED, ORDER_VALIDATED, ORDER_RAISED"

  Scenario: Closing the last open line closes the order the same way
    Given an order "OC-6" for "CUST" from "ORIGIN"
    And order "OC-6" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "OC-6" has line 2 of 800 kg of GENERAL for "CONS-B" at "DEST-B"
    And order "OC-6" is validated
    When line 1 of order "OC-6" is closed SHORT because "first drop cancelled"
    And line 2 of order "OC-6" is closed FORCE because "second drop cancelled"
    Then order "OC-6" is "CLOSED"
    And order "OC-6" reads closed FORCE because "second drop cancelled"
    And line 1 of order "OC-6" reads closed SHORT
    And line 2 of order "OC-6" reads closed FORCE
    And order "OC-6" announced "OrderRaised, OrderValidated, OrderClosed"

  Scenario: With automatic fulfilment off, a delivered order waits for a person to close it
    Given the tenant's orders are not fulfilled automatically
    And an order "OC-7" for "CUST" from "ORIGIN"
    And order "OC-7" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "OC-7" is validated
    And consignments are generated for order "OC-7"
    And a load "LOAD-OC-7" is opened at "ORIGIN" on lorry "MH-01-OC-0001"
    And the shipment of order "OC-7" for "CONS-A" is put on load "LOAD-OC-7"
    And load "LOAD-OC-7" is planned
    And trip "TRIP-OC-7" runs load "LOAD-OC-7" for "HAULIER" on lorry "MH-01-OC-0001" driven by "Ravi"
    When trip "TRIP-OC-7" is completed at its destination
    Then order "OC-7" is "FULLY_PLANNED"
    When order "OC-7" is closed NORMAL
    Then order "OC-7" is "CLOSED"
    And order "OC-7" reads closed NORMAL
    And order "OC-7" announced "OrderRaised, OrderValidated, OrderClosed"

  Scenario Outline: A split shipment closes the order once what is still on the road is within the tolerance
    Given the tenant's close tolerance is <tolerance> percent
    And an order "OC-8" for "CUST" from "ORIGIN"
    And order "OC-8" has line 1 of 10000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "OC-8" is validated
    And consignments are generated for order "OC-8"
    And the shipment of order "OC-8" is split into parts of 9600 kg and 400 kg
    And a load "LOAD-OC-8" is opened at "ORIGIN" on lorry "MH-01-OC-0001"
    And both parts of the shipment of order "OC-8" are put on load "LOAD-OC-8"
    When the 9600 kg part of order "OC-8" is delivered on proof
    Then order "OC-8" is "<status>"
    And the 400 kg part of order "OC-8" is still "ASSIGNED_TO_LOAD"

    Examples:
      | tolerance | status        |
      | 0         | FULLY_PLANNED |
      | 3         | FULLY_PLANNED |
      | 4         | CLOSED        |
      | 5         | CLOSED        |

  Scenario: A tolerance closes the order as AUTO, and a close by hand ignores it
    Given the tenant's close tolerance is 5 percent
    And an order "OC-9" for "CUST" from "ORIGIN"
    And order "OC-9" has line 1 of 10000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "OC-9" is validated
    And consignments are generated for order "OC-9"
    And the shipment of order "OC-9" is split into parts of 9600 kg and 400 kg
    And a load "LOAD-OC-9" is opened at "ORIGIN" on lorry "MH-01-OC-0001"
    And both parts of the shipment of order "OC-9" are put on load "LOAD-OC-9"
    When order "OC-9" is closed NORMAL
    Then the close is refused with "order-not-complete" mentioning "not yet delivered"
    When the 9600 kg part of order "OC-9" is delivered on proof
    Then order "OC-9" reads closed AUTO because "What is still on the road is within the close tolerance"
    And order "OC-9" announced "OrderRaised, OrderValidated, OrderClosed"

  Scenario: A close tolerance outside 0 to 100 is refused
    When the tenant's close tolerance is set to 101 percent
    Then the close is refused with "close-tolerance-out-of-range" mentioning "0 to 100"
