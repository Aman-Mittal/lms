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

Feature: A tenant keeps its own routing guides
  Vision document 3.5.

  The routing guide says which vendors are offered a lane's freight, and in
  what order. Writing one is a commercial decision with its own permission,
  and the rules that keep a guide unambiguous are enforced where it is
  written: one guide per lane and vehicle type, one vendor per rank, no
  vendor twice, and nobody added who may not currently be given work.

  Background:
    Given a tenant "acme" named "Acme Logistics"
    And tenant "acme" has an organisational unit "/acme/" named "Acme HQ" of type "HQ"
    And the tenant scope is "acme" at "/acme/"
    And a depot "ORIGIN" at 19.100,72.900
    And a depot "DEST-A" at 19.300,73.100
    And a carrier "VENDOR-1"
    And a carrier "VENDOR-2"

  Scenario: Vendors are kept in the order they were ranked
    When the lane from "ORIGIN" to "DEST-A" is given a routing guide
    And "VENDOR-2" is ranked 2 on that guide
    And "VENDOR-1" is ranked 1 on that guide
    Then that guide lists "VENDOR-1" at rank 1 and "VENDOR-2" at rank 2

  Scenario: A lane has one guide, retired ones included
    # Old allocations point at the guide they went out under. A second guide
    # for the same lane would make "which arrangement was this" ambiguous.
    Given the lane from "ORIGIN" to "DEST-A" is given a routing guide
    And that guide is retired
    When the lane from "ORIGIN" to "DEST-A" is given a routing guide
    Then the routing guide desk refuses mentioning "already has a routing guide"

  Scenario: A blacklisted vendor cannot be put on a guide
    Given carrier "VENDOR-2" is blacklisted
    And the lane from "ORIGIN" to "DEST-A" is given a routing guide
    When "VENDOR-2" is ranked 1 on that guide
    Then the routing guide desk refuses mentioning "is not active"

  Scenario: Two vendors cannot share a rank
    Given the lane from "ORIGIN" to "DEST-A" is given a routing guide
    And "VENDOR-1" is ranked 1 on that guide
    When "VENDOR-2" is ranked 1 on that guide
    Then the routing guide desk refuses mentioning "already held"

  Scenario: The same vendor cannot hold two ranks
    Given the lane from "ORIGIN" to "DEST-A" is given a routing guide
    And "VENDOR-1" is ranked 1 on that guide
    When "VENDOR-1" is ranked 2 on that guide
    Then the routing guide desk refuses mentioning "already on this guide"

  Scenario: A retired guide is not changed
    Given the lane from "ORIGIN" to "DEST-A" is given a routing guide
    And that guide is retired
    When "VENDOR-1" is ranked 1 on that guide
    Then the routing guide desk refuses mentioning "retired"

  Scenario: Writing a guide needs its own permission
    # Being allowed to allocate is not being allowed to decide who allocation asks.
    Given the signed-in user lacks "ROUTING_GUIDE_MANAGE"
    When the lane from "ORIGIN" to "DEST-A" is given a routing guide
    Then the routing guide desk refuses it as unauthorised
