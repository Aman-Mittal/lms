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

@bulkload
Feature: One vehicle carries a whole order's consignments, over the wire
  A large order to many consignees becomes a consignment per consignee. The
  planner finds them together by their order, puts every one of them on one
  load in a single request, and the load is planned, awarded, dispatched and
  delivered like any other. A batch with anything wrong in it boards nothing
  and names what is wrong. Everything is a request made with the client
  generated from the contract.

  Background:
    Given the demo administrator is signed in at the bulk-load desk

  Scenario: 150 consignments are put on one load in one request and delivered
    Given a bulk-load order with 150 lines of 100 kg, each to its own consignee, is raised and validated
    When the bulk-load order is planned into consignments
    Then the bulk-load queue for the order lists 150 consignments
    When every bulk-load consignment is put on one load in a single request
    Then the bulk load carries 150 consignments weighing 15000 kg in the order they were given
    When the bulk load is planned, awarded, dispatched and its trip completed
    Then every bulk-load consignment is DELIVERED

  Scenario: A batch holding a consignment that is already on another load boards nothing
    Given a bulk-load order with 3 lines of 100 kg, each to its own consignee, is raised and validated
    When the bulk-load order is planned into consignments
    And the first bulk-load consignment is put on another load
    And every bulk-load consignment is put on one load in a single request
    Then the bulk-load request is refused as "load-bulk-assignment-refused" naming only the first consignment
    And the bulk load carries nothing
