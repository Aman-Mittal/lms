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

@e2e_sourcing_lifecycle
Feature: Cancelling an award or an order gives the load back, over the wire
  A cancelled award used to leave its load AWARDED, so the load could never be
  offered again. Over HTTP, with the published API only: cancelling an awarded
  allocation returns the load to PLANNED and the next offer is a fresh
  allocation; it is refused (409 load-has-active-trip) while a trip is raised
  against the load; and cancelling an order withdraws the offer on the load it
  empties. The geography stays in the 70 to 74 degrees north band.

  Background:
    Given the demo administrator is signed in at the sourcing-lifecycle desk

  Scenario: A cancelled award returns the load to PLANNED and the load is offered again
    Given sourcing-lifecycle parties, vendor "ALPHA", two depots and a lorry are registered
    And a sourcing-lifecycle load is planned and offered to "ALPHA"
    When vendor "ALPHA" accepts the sourcing-lifecycle offer
    Then the sourcing-lifecycle load has status AWARDED
    When the sourcing-lifecycle allocation is abandoned
    Then the sourcing-lifecycle load has status PLANNED
    And the sourcing-lifecycle allocation has status CANCELLED
    When the sourcing-lifecycle load is sent out for offers again
    Then the sourcing-lifecycle second allocation is a new one, OFFERED to "ALPHA"
    When vendor "ALPHA" accepts the sourcing-lifecycle offer
    Then the sourcing-lifecycle load has status AWARDED
    And the sourcing-lifecycle first allocation is still listed as cancelled

  Scenario: An award cannot be cancelled while a trip is raised against the load
    Given sourcing-lifecycle parties, vendor "ALPHA", two depots and a lorry are registered
    And a sourcing-lifecycle load is planned and offered to "ALPHA"
    And vendor "ALPHA" accepts the sourcing-lifecycle offer
    And a sourcing-lifecycle trip is raised on the load
    Then cancelling the sourcing-lifecycle allocation is refused with "load-has-active-trip"
    And the sourcing-lifecycle load has status AWARDED
    And the sourcing-lifecycle allocation has status AWARDED

  Scenario: Cancelling an order withdraws the offer on the load it empties
    Given sourcing-lifecycle parties, vendor "ALPHA", two depots and a lorry are registered
    And a sourcing-lifecycle load is planned and offered to "ALPHA"
    When the sourcing-lifecycle order is withdrawn
    Then the sourcing-lifecycle load has status CANCELLED
    And the sourcing-lifecycle allocation has status CANCELLED
    And the sourcing-lifecycle offer to "ALPHA" ended as CANCELLED

  Scenario: An order whose awarded load has a trip raised cannot be cancelled
    Given sourcing-lifecycle parties, vendor "ALPHA", two depots and a lorry are registered
    And a sourcing-lifecycle load is planned and offered to "ALPHA"
    And vendor "ALPHA" accepts the sourcing-lifecycle offer
    And a sourcing-lifecycle trip is raised on the load
    Then cancelling the sourcing-lifecycle order is refused with "load-has-active-trip"
    And the sourcing-lifecycle load has status AWARDED
    And the sourcing-lifecycle allocation has status AWARDED
