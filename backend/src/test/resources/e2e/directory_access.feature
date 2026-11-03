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

@e2e_directory
Feature: Users, their access attributes and their sessions, over the wire
  The user directory is bounded by organisational unit, an administrator
  sets the attributes a user's next token will carry and a rule written as
  data reads them, and a session lives and dies by its refresh token: it is
  rotated on use, revoked by logout, and refused for a suspended or locked
  user. Everything here goes through the published API; only the provisioning
  of users and a second tenant is done in-process.

  Background:
    Given the access directory is open to the demo administrator, a second tenant and a branch unit with a user of its own

  Scenario: The user directory is bounded by organisational unit and shows what each user may do
    Then the user directory lists the whole tenant to its administrator but only the branch to the branch user
    And a user reads back by id with name, status, path, unit and last sign-in
    And an unknown user and another tenant's user are 404 problems and a request with no token is unauthorised
    And the permissions of a user are exactly those its roles grant

  Scenario: Access attributes are persisted, validated, permissioned and carried by the next token
    Then a user's access attributes start out unrestricted
    When the administrator restricts the branch user to a region, two freight modes, a partner and an approval limit
    Then the attributes read back exactly as set
    And the branch user's current token does not carry them but a refreshed one and a new sign-in both do
    And setting the attributes again replaces the whole set so a field left out is cleared
    And a malformed region, a negative limit and a partner that does not exist are 400 problems that change nothing
    And attributes of an unknown user and of another tenant's user are 404 problems for both reading and setting
    And reading or setting attributes is refused as 403 to users without the access-policy permission

  @directory_policy
  Scenario: A user's region changes what the user may do, from the next token and by a rule that is only data
    When two rules are written for partner registration: one denying anyone in the branch user's region, one switched off
    Then the rules are listed in code order with their action, effect, condition and switch, and filterable by action
    And the branch user, who carries no region yet, registers a partner under its own unit
    When the administrator puts the branch user in that region
    Then the token the branch user already holds still registers a partner because attributes apply from the next token
    And after a refresh the branch user is refused as forbidden, while the administrator and the branch user's reads are not
    When the administrator switches the denying rule off
    Then the very same token registers a partner again, and switching the rule back on refuses it again
    And clearing the branch user's region and refreshing lifts the refusal

  Scenario: A session is refreshed with rotation and ended by logout
    When the branch user signs in
    Then refreshing exchanges the refresh token for a new pair that works
    And the refresh token that was exchanged is refused as unauthorised, while its successor still works
    And a refresh token that never existed is unauthorised and a blank one is a 400 problem naming the field
    And logging out revokes that refresh token, leaves other sessions alone and is harmless to repeat

  Scenario: A suspended user cannot refresh or sign in until reinstated
    Then suspending the branch user refuses its refresh token and its sign-in, and reinstating restores both

  Scenario: Repeated wrong passwords lock an account without revealing which part was wrong
    Then five wrong passwords lock the branch user out even against the right one, and the refusal never says which part was wrong
