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

@e2e_planning_flow
Feature: Planning reads, sourcing outcomes, the gate log and the control tower desk, over the wire
  The published API only. An order is planned into consignments and a load and
  tracked by its public reference; a load is offered to vendors down a routing
  guide, where a rejection cascades to the next rank, an award can be cancelled
  and a retired guide stops being used; a trip's gate log lists its gate events
  in order; part-load dockets that arrive at a hub wait on that hub's queue; and
  the control tower summary counts open tickets while a resolved one can be sent
  back and a closed one cannot.

  Background:
    Given the demo administrator is signed in at the planning-flow desk

  Scenario: An order's lines are listed in order and are invisible to everybody else
    Given planning-flow parties, vendors "ALPHA" and "BETA", two depots, a lorry and a driver are registered
    When a planning-flow order with lines of 3000 kg and 2000 kg is raised and validated
    Then the planning-flow order lists both lines in line order with their weights and destination
    And the planning-flow order lines are empty for an unknown order and for another tenant, and refused to anonymous callers

  Scenario: A consignment is tracked by its public reference from planning onto a load
    Given planning-flow parties, vendors "ALPHA" and "BETA", two depots, a lorry and a driver are registered
    When a planning-flow order with lines of 3000 kg and 2000 kg is raised and validated
    And the planning-flow order is planned into a consignment
    Then tracking the planning-flow reference shows status PLANNED for the one consignee and no load
    When the planning-flow consignment is put on a planned load
    Then tracking the planning-flow reference shows status ASSIGNED_TO_LOAD on the planning-flow load
    And the planning-flow load lists exactly its consignment, read as raw HTTP
    And an unknown planning-flow load, and another tenant's view of this one, list no consignments
    And tracking an unknown planning-flow reference is a 404 problem and so is another tenant's lookup

  Scenario: A completed full-truckload trip shows its consignment delivered when tracked
    Given planning-flow parties, vendors "ALPHA" and "BETA", two depots, a lorry and a driver are registered
    And a planning-flow trip is assigned for 5000 kg, the load awarded to "ALPHA"
    Then tracking the planning-flow reference shows status ASSIGNED_TO_LOAD on the planning-flow load
    When the planning-flow lorry is gated in with the remark "Bay 4" and dispatched
    Then tracking the planning-flow reference shows status ASSIGNED_TO_LOAD on the planning-flow load
    When the planning-flow trip arrives and is completed
    Then tracking the planning-flow reference shows status DELIVERED on the planning-flow load
    And the planning-flow load is COMPLETED

  Scenario: A trip's gate log lists its gate events in order and nobody else can read it
    Given planning-flow parties, vendors "ALPHA" and "BETA", two depots, a lorry and a driver are registered
    And a planning-flow trip is assigned for 5000 kg, the load awarded to "ALPHA"
    Then the planning-flow gate log is empty
    When the planning-flow lorry is gated in with the remark "Bay 4" and dispatched
    Then the planning-flow gate log lists gate-in then gate-out at the origin in time order, with the remark "Bay 4"
    When the planning-flow trip arrives and is completed
    Then the planning-flow gate log now ends with a gate-in at the destination
    And the planning-flow gate log is empty for an unknown trip and for another tenant, and refused to anonymous callers

  Scenario: A rejection cascades to the next vendor and the offer trail shows the sequence
    Given planning-flow parties, vendors "ALPHA" and "BETA", two depots, a lorry and a driver are registered
    And the planning-flow load is planned and "ALPHA" is ranked first and "BETA" second
    When the planning-flow load is offered to its vendors
    Then the planning-flow allocation is OFFERED at rank 1 after 1 offers
    And the planning-flow offer trail reads "ALPHA=PENDING"
    When vendor "ALPHA" rejects the planning-flow offer
    Then the planning-flow allocation is OFFERED at rank 2 after 2 offers
    And the planning-flow offer trail reads "ALPHA=REJECTED,BETA=PENDING"
    And vendor "ALPHA" cannot answer the planning-flow offer, it is another vendor's now

  Scenario: When every vendor rejects, the allocation is exhausted and stays exhausted
    Given planning-flow parties, vendors "ALPHA" and "BETA", two depots, a lorry and a driver are registered
    And the planning-flow load is planned and "ALPHA" is ranked first and "BETA" second
    When the planning-flow load is offered to its vendors
    And vendor "ALPHA" rejects the planning-flow offer
    And vendor "BETA" rejects the planning-flow offer
    Then the planning-flow allocation is EXHAUSTED at rank 2 after 2 offers
    And the planning-flow offer trail reads "ALPHA=REJECTED,BETA=REJECTED"
    And a further planning-flow rejection by "BETA" is refused with "no-pending-offer"
    And a further planning-flow acceptance by "BETA" is refused with "no-pending-offer"
    And cancelling the planning-flow allocation is refused with "allocation-illegal-transition"
    And the planning-flow load is PLANNED

  Scenario: The second vendor accepts after the first rejects and is awarded the load
    Given planning-flow parties, vendors "ALPHA" and "BETA", two depots, a lorry and a driver are registered
    And the planning-flow load is planned and "ALPHA" is ranked first and "BETA" second
    When the planning-flow load is offered to its vendors
    And vendor "ALPHA" rejects the planning-flow offer
    And vendor "BETA" accepts the planning-flow offer
    Then the planning-flow allocation is AWARDED at rank 2 after 2 offers
    And the planning-flow offer trail reads "ALPHA=REJECTED,BETA=ACCEPTED"
    And the planning-flow allocation names "BETA" as its awarded vendor
    And the planning-flow load is AWARDED

  Scenario: Cancelling an outstanding offer withdraws it from the vendor
    Given planning-flow parties, vendors "ALPHA" and "BETA", two depots, a lorry and a driver are registered
    And the planning-flow load is planned and "ALPHA" is ranked first and "BETA" second
    When the planning-flow load is offered to its vendors
    And the planning-flow allocation is cancelled
    Then the planning-flow allocation is CANCELLED at rank 1 after 1 offers
    And the planning-flow offer trail reads "ALPHA=CANCELLED"
    And a further planning-flow acceptance by "ALPHA" is refused with "no-pending-offer"
    And cancelling the planning-flow allocation again changes nothing
    And the cancelled planning-flow allocation is listed among cancelled allocations

  Scenario: Cancelling an awarded allocation abandons it and keeps the history of the acceptance
    Given planning-flow parties, vendors "ALPHA" and "BETA", two depots, a lorry and a driver are registered
    And the planning-flow load is planned and "ALPHA" is ranked first and "BETA" second
    When the planning-flow load is offered to its vendors
    And vendor "ALPHA" accepts the planning-flow offer
    Then the planning-flow allocation is AWARDED at rank 1 after 1 offers
    When the planning-flow allocation is cancelled
    Then the planning-flow allocation is CANCELLED at rank 1 after 1 offers
    And the planning-flow offer trail reads "ALPHA=ACCEPTED"
    And a further planning-flow acceptance by "ALPHA" is refused with "no-pending-offer"

  Scenario: A retired routing guide stops being used for new loads
    Given planning-flow parties, vendors "ALPHA" and "BETA", two depots, a lorry and a driver are registered
    And the planning-flow load is planned and "ALPHA" is ranked first on its lane
    Then the planning-flow routing guide is active with "ALPHA" at rank 1
    When the planning-flow load is offered to its vendors
    And the planning-flow routing guide is retired
    Then the planning-flow routing guide is retired but still shows its vendors
    And retiring the planning-flow routing guide again changes nothing
    And a new planning-flow load on that lane can no longer be offered, refused with "no-routing-guide"
    And the retired planning-flow guide takes no new vendor, refused with "routing-guide-retired", and cannot be redefined, refused with "routing-guide-exists"
    And the earlier planning-flow allocation, made under the guide before it was retired, is still readable

  Scenario: Sourcing outcomes are permission checked, tenant isolated and validated
    Given planning-flow parties, vendors "ALPHA" and "BETA", two depots, a lorry and a driver are registered
    And the planning-flow load is planned and "ALPHA" is ranked first and "BETA" second
    When the planning-flow load is offered to its vendors
    Then without the permission, rejecting, cancelling and retiring in sourcing are all forbidden
    And another tenant gets 404 problems for the planning-flow allocation and guide, and no offers
    And unknown planning-flow allocation and guide ids are 404 problems, and an unknown allocation has no offers
    And a planning-flow rejection naming no vendor is a 400 problem with code validation-failed

  Scenario: A docket that arrived at a hub waits on that hub's delivery queue until a delivery load takes it
    Given planning-flow hubs, a delivery site, a customer, a consignee and vendor "GAMMA" are registered
    When a planning-flow docket of 200 kg is booked and its line-haul load awarded to "GAMMA"
    And the planning-flow line haul leaves the origin hub and reaches the destination hub
    Then the planning-flow destination hub lists the docket as arrived and waiting for delivery
    And the planning-flow hub queue is forbidden without load-read permission and empty for another tenant
    When the planning-flow docket is put on a delivery load opened at the destination hub
    Then the planning-flow destination hub no longer lists the docket and it is assigned to delivery

  Scenario: The exception summary counts open tickets and a resolved ticket can be sent back but a closed one cannot
    Given planning-flow parties, vendors "ALPHA" and "BETA", two depots, a lorry and a driver are registered
    And a planning-flow trip is assigned for 5000 kg, the load awarded to "ALPHA"
    When the planning-flow exception summary is read as the baseline
    And a planning-flow THEFT and an ETA_DELAY are raised on the trip
    Then the planning-flow summary shows 1 more open P1, 1 more open P3, 2 more unassigned and 1 more settlement hold
    When the planning-flow THEFT is assigned, investigated and resolved by the administrator
    Then the planning-flow summary shows 1 more open P1, 1 more open P3, 1 more unassigned and 1 more settlement hold
    When the planning-flow resolution is sent back with the reason "Police report missing"
    Then the planning-flow THEFT is under investigation again, with a REOPENED entry carrying "Police report missing"
    When the planning-flow THEFT is resolved again and closed by a second manager
    And the planning-flow ETA_DELAY is worked through to closed by the administrator
    Then the planning-flow summary is back to the baseline
    And reopening the closed planning-flow THEFT is refused with "exception-illegal-transition"

  Scenario: Reopening is refused before resolution, without a reason, without permission and across tenants
    Given planning-flow parties, vendors "ALPHA" and "BETA", two depots, a lorry and a driver are registered
    And a planning-flow trip is assigned for 5000 kg, the load awarded to "ALPHA"
    When a planning-flow ETA_DELAY is raised on the trip
    Then reopening the planning-flow ticket before it is resolved is refused with "exception-illegal-transition"
    When the planning-flow ticket is assigned, investigated and resolved
    Then a planning-flow reopening without a reason is a 400 problem with code validation-failed and an unknown ticket is a 404 problem
    And the planning-flow summary and reopen are permission checked and tenant isolated
    When the planning-flow ticket is closed
