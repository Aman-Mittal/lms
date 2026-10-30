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

@allocation-rules
Feature: Tenant-written allocation rules decide who a load is offered to, over the wire
  A company says which vendors it prefers or will not use, by lane, weight band
  and kind of goods. When a load is offered, the first matching rule by priority
  shapes who is asked, the offer records which rule it was, and a dry run says
  the same thing beforehand. Every step is a request through the generated
  client, against a real planned load. Each rule is tied to its scenario's own
  lane, so the scenarios cannot reach one another's loads.

  Background:
    Given the demo administrator is signed in at the allocation-rule desk
    And allocation-rule vendors "ALPHA", "BETA" and "GAMMA" are ranked 1, 2 and 3 on a lane served by a lorry

  Scenario: With no matching rule the guide's own order stands
    Given an allocation-rule load of 4000 kg of GENERAL goods is planned
    When the allocation-rule dry run is asked for the load
    Then the allocation-rule dry run names no rule and offers first to "ALPHA"
    When the allocation-rule load is offered
    Then the allocation-rule offer is with "ALPHA"
    And the allocation-rule allocation records that no rule decided it

  Scenario: A preference puts its vendors first, in its order, and the cascade then resumes
    Given the allocation-rule rule "Prefer gamma then beta" exists:
      | action | priority | vendors     |
      | PREFER | 10       | GAMMA, BETA |
    And an allocation-rule load of 4000 kg of GENERAL goods is planned
    When the allocation-rule dry run is asked for the load
    Then the allocation-rule dry run names rule "Prefer gamma then beta" and offers first to "GAMMA"
    When the allocation-rule load is offered
    Then the allocation-rule offer is with "GAMMA"
    And the allocation-rule allocation records rule "Prefer gamma then beta" as PREFER
    When "GAMMA" rejects the allocation-rule offer
    Then the allocation-rule offer is with "BETA"
    When "BETA" rejects the allocation-rule offer
    Then the allocation-rule offer is with "ALPHA"

  Scenario: An exclusion is never asked, and when the rest decline the allocation is exhausted
    Given the allocation-rule rule "Never alpha" exists:
      | action  | priority | vendors |
      | EXCLUDE | 10       | ALPHA   |
    And an allocation-rule load of 4000 kg of GENERAL goods is planned
    When the allocation-rule load is offered
    Then the allocation-rule offer is with "BETA"
    When "BETA" rejects the allocation-rule offer
    Then the allocation-rule offer is with "GAMMA"
    When "GAMMA" rejects the allocation-rule offer
    Then the allocation-rule allocation is EXHAUSTED
    And alpha was never offered the allocation-rule load

  Scenario: The lowest priority number that matches decides, alone
    Given the allocation-rule rule "Heavy goes to gamma" exists:
      | action | priority | minWeightKg | vendors |
      | PREFER | 10       | 5000        | GAMMA   |
    And the allocation-rule rule "Everything else to beta" exists:
      | action | priority | vendors |
      | PREFER | 20       | BETA    |
    And an allocation-rule load of 4000 kg of GENERAL goods is planned
    When the allocation-rule load is offered
    Then the allocation-rule offer is with "BETA"
    And the allocation-rule allocation records rule "Everything else to beta" as PREFER

  Scenario: A heavy load meets the weight band, and the band includes its lower end
    Given the allocation-rule rule "Heavy goes to gamma" exists:
      | action | priority | minWeightKg | vendors |
      | PREFER | 10       | 5000        | GAMMA   |
    And the allocation-rule rule "Everything else to beta" exists:
      | action | priority | vendors |
      | PREFER | 20       | BETA    |
    And an allocation-rule load of 5000 kg of GENERAL goods is planned
    When the allocation-rule load is offered
    Then the allocation-rule offer is with "GAMMA"
    And the allocation-rule allocation records rule "Heavy goes to gamma" as PREFER

  Scenario: A rule about kinds of goods applies only to loads carrying them
    Given the allocation-rule rule "Food to gamma" exists:
      | action | priority | materialClass | vendors |
      | PREFER | 10       | FOOD          | GAMMA   |
    And an allocation-rule load of 1000 kg of FOOD goods is planned
    When the allocation-rule load is offered
    Then the allocation-rule offer is with "GAMMA"

  Scenario: A retired rule is not applied to the next load
    Given the allocation-rule rule "Prefer gamma" exists:
      | action | priority | vendors |
      | PREFER | 10       | GAMMA   |
    And the allocation-rule rule "Prefer gamma" is retired
    And an allocation-rule load of 4000 kg of GENERAL goods is planned
    When the allocation-rule dry run is asked for the load
    Then the allocation-rule dry run names no rule and offers first to "ALPHA"

  Scenario: Retiring a rule mid-cascade does not change the allocation it decided
    Given the allocation-rule rule "Prefer gamma then beta" exists:
      | action | priority | vendors     |
      | PREFER | 10       | GAMMA, BETA |
    And an allocation-rule load of 4000 kg of GENERAL goods is planned
    When the allocation-rule load is offered
    And the allocation-rule rule "Prefer gamma then beta" is retired
    And "GAMMA" rejects the allocation-rule offer
    Then the allocation-rule offer is with "BETA"
    And the allocation-rule allocation records rule "Prefer gamma then beta" as PREFER
