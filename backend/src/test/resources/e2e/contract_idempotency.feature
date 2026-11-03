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
Feature: A retried write is applied once
  Idempotency over HTTP. Retries are normal: a client that never learned the
  outcome sends the same request again with the same Idempotency-Key and gets
  the original answer, not a second resource and not a conflict. The same key
  for a different request is a client defect and is refused. A request that
  failed releases its key so that the corrected request can use it.

  Background:
    Given the contract suite is signed in as the demo administrator

  Scenario: A retry of a partner registration gets the original answer and creates one partner
    When the contract administrator registers the partner "CT-IDEM" with the key "idem-register"
    And the contract administrator registers the partner "CT-IDEM" with the key "idem-register"
    Then answer number 1 was created and carries no replay marker
    And answer number 2 replays answer number 1 with the same status and body
    And exactly one partner exists with the code "CT-IDEM"

  Scenario: A retry of an order is the same order
    When the contract administrator raises the same order twice with the key "idem-order"
    Then the two order answers are one creation and its replay
    And that order exists once, because raising its number again with a new key is a conflict

  Scenario: A retried document attachment attaches one document
    Given the contract administrator has a partner to attach documents to
    When the contract administrator attaches the same document to that partner twice with the key "idem-doc"
    Then the first two document answers are one creation and its replay
    And that partner holds 1 document
    When the contract administrator attaches that document to that partner with no key
    Then that partner holds 2 documents

  Scenario: The same key with a different body is refused and the second body is not applied
    When the contract administrator registers the partner "CT-KEYA" with the key "idem-reuse"
    And the contract administrator registers the partner "CT-KEYB" with the key "idem-reuse"
    Then answer number 1 was created and carries no replay marker
    And answer number 2 is refused as a reused key
    And no partner exists yet with the code "CT-KEYB", so registering it now is a first creation

  Scenario: The same key for a different resource path is refused
    Given the contract administrator has a partner to attach documents to
    When the contract administrator attaches that document to that partner with the key "idem-path"
    And the contract administrator attaches that document to the other partner with the key "idem-path"
    Then the last document answer is refused as a reused key
    And the other partner holds 0 documents

  Scenario: A request refused as invalid releases its key
    When the contract administrator sends an empty object to register a partner with the key "idem-fail"
    And the contract administrator registers the partner "CT-FIXED" with the key "idem-fail"
    Then answer number 1 is refused as a validation failure
    And answer number 2 is a fresh creation, not a replay of the failure

  Scenario: A request refused by the domain releases its key
    When the contract administrator registers the partner "CT-TAKEN" with the key "idem-taken-first"
    And the contract administrator registers the partner "CT-TAKEN" named "Again" with the key "idem-taken-second"
    And the contract administrator registers the partner "CT-TAKEN-2" with the key "idem-taken-second"
    Then answer number 2 is refused as a duplicate code
    And answer number 3 is a fresh creation, not a replay of the failure

  Scenario: A key is private to its tenant
    Given a second tenant with its own administrator
    When the contract administrator registers the partner "CT-TENANT" with the key "idem-tenant"
    And the second tenant registers a partner with the key "idem-tenant" that the demo tenant also used
    Then the second tenant's answer is a fresh creation of its own

  Scenario: Signing in ignores the key
    When the contract caller signs in twice with the key "idem-login"
    Then both sign-ins succeed and neither is a replay

  Scenario: A read ignores the key
    When the contract administrator reads the partners twice with the key "idem-read"
    Then both reads answer 200 and neither is a replay
