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

@abac
Feature: An administrator changes who may do what by writing a rule, over the wire
  Attribute-based access control, end to end through the published API only.
  Rules are data held per tenant: one written by the administrator applies to
  the very next request, can be switched off the same way and can be removed,
  and a rule that names a permission nobody holds is refused when written
  rather than silently never matching.

  Background:
    Given the demo administrator is signed in to manage access policies

  Scenario: A rule takes effect on the next request and goes when it is switched off
    When a rule is added denying partner registration to everyone who has an organisational path
    Then registering a partner is refused as forbidden
    When the rule is switched off
    Then a partner can be registered again
    When the rule is deleted
    Then the rule can no longer be found

  Scenario: A rule is checked when it is written
    Then a rule naming a permission that does not exist is refused
