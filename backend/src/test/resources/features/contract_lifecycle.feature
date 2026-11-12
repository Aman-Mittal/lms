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

Feature: A client contract is drafted, checked and verified by somebody else before it applies
  Issue #216.

  A contract version starts as a draft that its maker edits freely. Before
  verification can be asked for it must pass the tenant's completeness
  checklist, and the refusal names every rule it fails. Verification goes
  through the approvals module: the maker may not approve their own draft,
  approving puts the contract in force (closing the version it succeeds), and
  rejecting it sends it back to draft with the reason. A contract that has
  been verified is never edited again, and every step is in the audit trail.

  Background:
    Given a tenant "acme" named "Acme Logistics"
    And tenant "acme" has an organisational unit "/acme/" named "Acme HQ" of type "HQ"
    And the tenant scope is "acme" at "/acme/"
    And a trading partner "CLIENT-A"

  Scenario: A draft is verified by somebody else and then applies
    Given "Maya" drafts contract "L-1" for "CLIENT-A" starting 10 days ago with a transport rate of 5
    Then contract "L-1" stands DRAFT
    And contract "L-1" is in force on no day
    When "Maya" asks for contract "L-1" to be verified
    Then contract "L-1" stands PENDING_VERIFICATION
    When "Maya" approves the verification of contract "L-1"
    Then the contract step is refused with "approval-self-decision"
    When "Noor" approves the verification of contract "L-1"
    Then contract "L-1" stands ACTIVE
    And contract "L-1" is in force today
    And contract "L-1" was audited as "CLIENT_CONTRACT_ACTIVATED"

  Scenario: Verification is refused until the draft is complete, naming every failing rule
    Given "Maya" drafts contract "L-2" for "CLIENT-A" starting 10 days ago with a transport rate of 0
    When "Maya" asks for contract "L-2" to be verified
    Then the contract step is refused with "contract-checklist-incomplete"
    And the refusal names rule "TRANSPORT_RATE"
    And contract "L-2" stands DRAFT

  Scenario: A rejection returns the draft with the reason
    Given "Maya" drafts contract "L-3" for "CLIENT-A" starting 10 days ago with a transport rate of 5
    And "Maya" asks for contract "L-3" to be verified
    When "Noor" rejects the verification of contract "L-3" because "Rate sheet missing"
    Then contract "L-3" stands DRAFT
    And contract "L-3" carries the rejection reason "Rate sheet missing"
    And contract "L-3" was audited as "CLIENT_CONTRACT_VERIFICATION_REJECTED"

  Scenario: Where the tenant asks for no verification, asking puts the contract in force
    Given the tenant asks for no verification of contracts
    And "Maya" drafts contract "L-4" for "CLIENT-A" starting 10 days ago with a transport rate of 5
    When "Maya" asks for contract "L-4" to be verified
    Then contract "L-4" stands ACTIVE
    And contract "L-4" was audited as "CLIENT_CONTRACT_ACTIVATED"

  Scenario: A successor activated by approval closes the version in force
    Given "Maya" drafts contract "L-5" for "CLIENT-A" starting 30 days ago with a transport rate of 5
    And "Maya" asks for contract "L-5" to be verified
    And "Noor" approves the verification of contract "L-5"
    And "Maya" drafts contract "L-6" for "CLIENT-A" starting 10 days ago with a transport rate of 6
    When "Maya" asks for contract "L-6" to be verified
    And "Noor" approves the verification of contract "L-6"
    Then contract "L-5" ended 11 days ago
    And contract "L-6" is in force today
