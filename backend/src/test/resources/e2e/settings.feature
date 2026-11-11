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

@settings-e2e
Feature: A person sees who they are and keeps their own preferences, over the wire
  The profile page's two reads and one write, end to end through the
  published API: who the caller is, their preferences folded over the
  tenant's, and saving them under the version last read. A customer's own
  profile then wins over the tenant's for that customer, and says so.

  Background:
    Given the demo administrator is signed in to their profile page

  Scenario: The administrator reads who they are and saves their preferences
    When the administrator asks who they are
    Then they are told their own name, tenant, unit, roles and permissions
    When the administrator chooses the dark theme and 25 rows a page
    Then their preferences read back as theirs, and the other preferences as the platform's
    And saving again with the version they first read is refused with "concurrent-modification"
    And setting a tenant rule as a personal preference is refused with "setting-scope-not-allowed"

  Scenario: A customer's own close tolerance is the one in force for it
    When the administrator gives a new customer its own close tolerance of 3 percent
    Then the close tolerance in force for that customer is 3 percent, from its own profile
    And the profile's history records who created it and with what
