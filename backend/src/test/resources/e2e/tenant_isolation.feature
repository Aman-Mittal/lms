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

@isolation
Feature: A second company cannot see the first company's freight, over the wire
  Tenant isolation, end to end through the published API only. One company's
  administrator plans an order onto a load, awards it and raises a trip; a
  second company's administrator, holding every permission, asks for each of
  those by id and is told they do not exist -- 404, not 403, which would
  confirm they do.

  Background:
    Given the demo administrator has an awarded trip with its order, consignment and load
    And a second company with its own administrator is signed in

  Scenario: Another tenant gets 404 for the demo company's order, consignment, load, trip and allocation
    Then the second company is told the demo company's order, consignment, load, trip and allocation do not exist
    And none of the second company's listings contain the demo company's freight

  Scenario: The owner is unaffected and still reads everything
    Then the demo administrator reads the order, consignment, load, trip and allocation by id
