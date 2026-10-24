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
Feature: Listings page by keyset, bounded, and never skip or repeat
  Pagination over HTTP. A listing returns a page and the cursor of the next;
  walking the cursors visits every resource exactly once, the last page offers
  no cursor, a cursor is stable, a page size above the maximum is clamped and
  a resource registered mid-walk can neither duplicate nor lose another.

  Background:
    Given the contract suite is signed in as the demo administrator

  Scenario Outline: Walking the <kind> two at a time visits each once and ends cleanly
    Given 5 contract <kind> have been registered
    When the contract administrator walks that listing two at a time
    Then every page but the last holds two items, says there is more and offers a cursor
    And the last page holds one or two items, says there is no more and offers no cursor
    And no resource appears twice and every registered one appears exactly once
    And walking with the largest page size yields the same ids in the same order
    And asking again for the cursor of the first page returns the same second page

    Examples:
      | kind      |
      | partners  |
      | terminals |
      | vehicles  |
      | drivers   |

  Scenario Outline: A partners page size of "<limit>" is bounded
    Given 5 contract partners have been registered
    When the contract administrator lists partners with the limit "<limit>"
    Then the page holds as many partners as the smaller of the total and <cap>

    Examples:
      | limit  | cap |
      |        | 50  |
      | 0      | 50  |
      | -3     | 50  |
      | 3      | 3   |
      | 200    | 200 |
      | 201    | 200 |
      | 100000 | 200 |

  Scenario: A partner registered mid-walk neither duplicates nor loses another
    Given 5 contract partners have been registered
    When the contract administrator takes the first page of partners two at a time
    And a new partner is registered before the second page is taken
    Then finishing the walk shows no partner twice and none of those already listed is lost
