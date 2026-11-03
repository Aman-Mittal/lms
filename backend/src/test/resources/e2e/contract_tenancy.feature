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

@e2e_contract
Feature: One tenant cannot see or touch another's data
  Tenancy over HTTP, from the outside. The tenant is in the token, not in the
  request, so there is no parameter to change; asking for another tenant's id
  is asking for something that, as far as the caller can tell, does not exist.

  Background:
    Given the contract suite is signed in as the demo administrator
    And a second tenant with its own administrator

  Scenario Outline: A <kind> belongs to the tenant that created it
    Given the demo tenant owns a contract <kind>
    Then the demo administrator reads that resource
    And the second tenant's administrator gets a 404 not-found for that resource
    And that resource is absent from the second tenant's listing

    Examples:
      | kind     |
      | partner  |
      | terminal |
      | vehicle  |
      | driver   |
      | order    |

  Scenario: Another tenant cannot move a partner along its lifecycle
    Given the demo tenant owns a contract partner
    Then the second tenant's administrator cannot move that partner along its lifecycle

  Scenario: What one tenant registers the other cannot read
    Then a partner the second tenant registers is invisible to the demo administrator
