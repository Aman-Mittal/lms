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

@audit-trail
Feature: Order, planning and sourcing transitions leave an audit trail
  Roadmap R-04. Finance, billing, warehouse, identity, the control tower, milk
  runs and rail tariffs audited their mutations; orders, consignments, loads
  and allocations changed state with no record of who did it or from what.

  Each lifecycle transition writes one audit_log row in the transition's own
  transaction, named in the ORDER_CANCELLED style, with the state before and
  after. A transition that is refused or rolled back leaves no row, and a
  repeated delivery of the same fact (a completion recorded twice) adds none.

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
    And a lorry "MH-01-AU-0001" with payload 10000 kg and volume 40 m3
    And lorry "MH-01-AU-0001" has an "INSURANCE" certificate valid for 365 days
    And a lorry driver "Ravi" licensed for 365 days
    And a carrier "HAULIER"
    And a carrier "VENDOR-1"
    And a carrier "VENDOR-2"

  Scenario: A delivered order's transitions are audited from raising to fulfilment
    Given an order "AU-1" for "CUST" from "ORIGIN"
    And order "AU-1" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "AU-1" has line 2 of 800 kg of GENERAL for "CONS-B" at "DEST-B"
    And order "AU-1" is validated
    And consignments are generated for order "AU-1"
    And a load "LOAD-AU-1" is opened at "ORIGIN" on lorry "MH-01-AU-0001"
    And the shipment of order "AU-1" for "CONS-A" is put on load "LOAD-AU-1"
    And the shipment of order "AU-1" for "CONS-B" is put on load "LOAD-AU-1"
    And load "LOAD-AU-1" is planned
    And trip "TRIP-AU-1" runs load "LOAD-AU-1" for "HAULIER" on lorry "MH-01-AU-0001" driven by "Ravi"
    When trip "TRIP-AU-1" is completed at its destination
    Then the audit trail of order "AU-1" reads "ORDER_RAISED, ORDER_VALIDATED, ORDER_FULFILLED"
    And the audit trail of the shipment of order "AU-1" for "CONS-A" reads "CONSIGNMENT_GENERATED, CONSIGNMENT_ASSIGNED, CONSIGNMENT_DELIVERED"
    And the audit trail of the shipment of order "AU-1" for "CONS-B" reads "CONSIGNMENT_GENERATED, CONSIGNMENT_ASSIGNED, CONSIGNMENT_DELIVERED"
    And the audit trail of load "LOAD-AU-1" reads "LOAD_OPENED, LOAD_PLANNED, LOAD_AWARDED, LOAD_DISPATCHED, LOAD_COMPLETED"

  Scenario: Each entry carries the state before and after the transition
    Given a planned and awarded load "LOAD-AU-2" carrying order "AU-2" of 1000 kg
    Then the audit entry "LOAD_AWARDED" of load "LOAD-AU-2" moved status from "PLANNED" to "AWARDED"
    And the audit entry "LOAD_PLANNED" of load "LOAD-AU-2" moved status from "DRAFT" to "PLANNED"
    And the audit entry "ORDER_VALIDATED" of order "AU-2" moved status from "DRAFT" to "VALIDATED"
    And the audit entry "LOAD_OPENED" of load "LOAD-AU-2" has no before state

  Scenario: A cancelled order's transitions are audited with the reason
    Given an order "AU-3" for "CUST" from "ORIGIN"
    And order "AU-3" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "AU-3" is validated
    And consignments are generated for order "AU-3"
    And a load "LOAD-AU-3" is opened at "ORIGIN" on lorry "MH-01-AU-0001"
    And the shipment of order "AU-3" for "CONS-A" is put on load "LOAD-AU-3"
    And load "LOAD-AU-3" is planned
    When order "AU-3" is cancelled with the reason "customer withdrew the indent"
    Then the audit trail of order "AU-3" reads "ORDER_RAISED, ORDER_VALIDATED, ORDER_CANCELLED"
    And the audit entry "ORDER_CANCELLED" of order "AU-3" carries the reason "customer withdrew the indent"
    And the audit trail of the shipment of order "AU-3" for "CONS-A" reads "CONSIGNMENT_GENERATED, CONSIGNMENT_ASSIGNED, CONSIGNMENT_CANCELLED"
    And the audit trail of load "LOAD-AU-3" reads "LOAD_OPENED, LOAD_PLANNED, LOAD_CANCELLED"

  Scenario: Cancelling an order twice audits the cancellation once
    Given an order "AU-4" for "CUST" from "ORIGIN"
    And order "AU-4" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "AU-4" is validated
    When order "AU-4" is cancelled
    And order "AU-4" is cancelled
    Then the audit trail of order "AU-4" reads "ORDER_RAISED, ORDER_VALIDATED, ORDER_CANCELLED"

  Scenario: A refused cancellation leaves no entry
    Given an order "AU-5" for "CUST" from "ORIGIN"
    And order "AU-5" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "AU-5" is validated
    And consignments are generated for order "AU-5"
    And a load "LOAD-AU-5" is opened at "ORIGIN" on lorry "MH-01-AU-0001"
    And the shipment of order "AU-5" for "CONS-A" is put on load "LOAD-AU-5"
    And load "LOAD-AU-5" is planned
    And trip "TRIP-AU-5" runs load "LOAD-AU-5" for "HAULIER" on lorry "MH-01-AU-0001" driven by "Ravi"
    When order "AU-5" is cancelled
    Then cancelling the order is refused with "order-work-dispatched"
    And the audit trail of order "AU-5" reads "ORDER_RAISED, ORDER_VALIDATED"
    And the audit trail of the shipment of order "AU-5" for "CONS-A" reads "CONSIGNMENT_GENERATED, CONSIGNMENT_ASSIGNED"
    And the audit trail of load "LOAD-AU-5" reads "LOAD_OPENED, LOAD_PLANNED, LOAD_AWARDED, LOAD_DISPATCHED"

  Scenario: Recording a completion again does not audit it again
    Given an order "AU-6" for "CUST" from "ORIGIN"
    And order "AU-6" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "AU-6" is validated
    And consignments are generated for order "AU-6"
    And a load "LOAD-AU-6" is opened at "ORIGIN" on lorry "MH-01-AU-0001"
    And the shipment of order "AU-6" for "CONS-A" is put on load "LOAD-AU-6"
    And load "LOAD-AU-6" is planned
    And trip "TRIP-AU-6" runs load "LOAD-AU-6" for "HAULIER" on lorry "MH-01-AU-0001" driven by "Ravi"
    And trip "TRIP-AU-6" is completed at its destination
    When the completion of load "LOAD-AU-6" is recorded again
    Then the audit trail of load "LOAD-AU-6" reads "LOAD_OPENED, LOAD_PLANNED, LOAD_AWARDED, LOAD_DISPATCHED, LOAD_COMPLETED"
    And the audit trail of the shipment of order "AU-6" for "CONS-A" reads "CONSIGNMENT_GENERATED, CONSIGNMENT_ASSIGNED, CONSIGNMENT_DELIVERED"
    And the audit trail of order "AU-6" reads "ORDER_RAISED, ORDER_VALIDATED, ORDER_FULFILLED"

  Scenario: The allocation cascade is audited decision by decision
    Given a planned and ranked load "LOAD-AU-7" carrying order "AU-7" of 1000 kg
    And load "LOAD-AU-7" is allocated
    And "VENDOR-1" rejects the offer
    And "VENDOR-2" accepts the offer
    Then the audit trail of the allocation of load "LOAD-AU-7" reads "ALLOCATION_OPENED, ALLOCATION_OFFER_REJECTED, ALLOCATION_AWARDED"
    And the audit trail of load "LOAD-AU-7" reads "LOAD_OPENED, LOAD_PLANNED, LOAD_AWARDED"

  Scenario: An exhausted allocation is audited as exhausted
    Given a planned and ranked load "LOAD-AU-8" carrying order "AU-8" of 1000 kg
    And load "LOAD-AU-8" is allocated
    And "VENDOR-1" rejects the offer
    And "VENDOR-2" rejects the offer
    Then the audit trail of the allocation of load "LOAD-AU-8" reads "ALLOCATION_OPENED, ALLOCATION_OFFER_REJECTED, ALLOCATION_OFFER_REJECTED, ALLOCATION_EXHAUSTED"

  Scenario: A lapsed offer is audited as timed out
    Given a planned and ranked load "LOAD-AU-9" carrying order "AU-9" of 1000 kg
    And load "LOAD-AU-9" is allocated
    And the deadline for the offer to "VENDOR-1" has passed
    When lapsed offers are swept
    Then the audit trail of the allocation of load "LOAD-AU-9" reads "ALLOCATION_OPENED, ALLOCATION_OFFER_TIMED_OUT"

  Scenario: Cancelling an award is audited on both the allocation and the load
    Given a planned and ranked load "LOAD-AU-10" carrying order "AU-10" of 1000 kg
    And load "LOAD-AU-10" is allocated
    And "VENDOR-1" accepts the offer
    When the allocation of load "LOAD-AU-10" is cancelled
    Then the audit trail of the allocation of load "LOAD-AU-10" reads "ALLOCATION_OPENED, ALLOCATION_AWARDED, ALLOCATION_CANCELLED"
    And the audit trail of load "LOAD-AU-10" reads "LOAD_OPENED, LOAD_PLANNED, LOAD_AWARDED, LOAD_AWARD_WITHDRAWN"
    And the audit entry "ALLOCATION_CANCELLED" of the allocation of load "LOAD-AU-10" moved status from "AWARDED" to "CANCELLED"
