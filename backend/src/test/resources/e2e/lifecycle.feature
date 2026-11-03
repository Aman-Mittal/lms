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

@lifecycle
Feature: A delivered trip closes its order and a cancelled order releases its freight, over the wire
  The freight lifecycle through the published API only: an order is planned,
  and then either its trip completes (the consignments are delivered, the load
  completes and the order is fulfilled) or the order is cancelled (its
  consignments and its empty load are cancelled), or the cancellation is
  refused because the lorry has already left.

  Background:
    Given the demo administrator is signed in at the lifecycle desk
    And a lifecycle customer, consignee, vendor, two depots and a lorry are registered

  Scenario: A completed full-truckload trip delivers its consignment, completes its load and fulfils the order
    When a lifecycle order of 5000 kg is planned onto a load, awarded and dispatched
    Then the lifecycle order is "FULLY_PLANNED" and its consignment is "ASSIGNED_TO_LOAD"
    When the lifecycle trip is completed
    Then the lifecycle order is "FULFILLED" and its consignment is "DELIVERED"
    And the lifecycle load is "COMPLETED"

  Scenario: Cancelling a planned order cancels its consignment and its empty load
    When a lifecycle order of 5000 kg is planned onto a load
    And the lifecycle order is cancelled for the reason "customer withdrew the indent"
    Then the lifecycle order is "CANCELLED" and its consignment is "CANCELLED"
    And the lifecycle load is "CANCELLED"
    And cancelling the lifecycle order again changes nothing

  Scenario: An order whose lorry has left cannot be cancelled
    When a lifecycle order of 5000 kg is planned onto a load, awarded and dispatched
    And the lifecycle order is cancelled without a reason
    Then the lifecycle cancellation is refused with "order-work-dispatched"
    And the lifecycle order is "FULLY_PLANNED" and its consignment is "ASSIGNED_TO_LOAD"
    And the lifecycle load is "DISPATCHED"
