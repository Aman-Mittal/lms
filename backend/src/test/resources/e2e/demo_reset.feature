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

@demo_reset
Feature: The demo can be put back to its seeded state, over the wire
  Whatever visitors leave in the public demo, an administrator can clear it
  without a redeploy. Every step is a request made with the client generated
  from the contract, against the running demo deployment.

  Scenario: A reset clears a visitor's data and the administrator signs in again
    Given the demo administrator is signed in to reset the demo
    And a visitor has registered a partner in the demo
    When the administrator resets the demo
    Then the reset reports rows deleted and asks the administrator to sign in again
    And the administrator signs in again with the same password
    And the visitor's partner is gone
