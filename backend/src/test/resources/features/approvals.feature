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

@approvals
Feature: One approvals module decides every flow that needs a person to agree first
  A flow asks the approvals module whether its tenant's policy wants an
  approval. When it does, the action is not taken: a request is raised with
  the action frozen in it, and approving the request takes the action then,
  checked again at that moment. The requester may not decide their own
  request, and a decision the subject no longer allows is refused with the
  owning module's own reason and leaves the request open. Cancelling an order
  needs no approval unless the tenant switches it on.

  Background:
    Given a tenant "acme" named "Acme Logistics"
    And tenant "acme" has an organisational unit "/acme/" named "Acme HQ" of type "HQ"
    And the tenant scope is "acme" at "/acme/"
    And a trading partner "CUST"
    And a depot "ORIGIN" at 19.10,72.90

  Scenario: Without the tenant's switch an order is cancelled at once
    Given an order "AP-1" for "CUST" from "ORIGIN"
    When "Asha" asks to cancel order "AP-1"
    Then order "AP-1" now stands CANCELLED
    And no approval was asked for order "AP-1"

  Scenario: With the switch, cancelling waits for somebody else's approval
    Given the tenant requires approval to cancel an order
    And an order "AP-2" for "CUST" from "ORIGIN"
    When "Asha" asks to cancel order "AP-2"
    Then order "AP-2" now stands DRAFT
    And the cancellation of order "AP-2" awaits approval
    When "Asha" asks to cancel order "AP-2"
    Then the approval step is refused with "approval-already-open"
    When "Asha" approves the cancellation of order "AP-2"
    Then the approval step is refused with "approval-self-decision"
    When "Bilal" approves the cancellation of order "AP-2"
    Then order "AP-2" now stands CANCELLED
    And the cancellation request of order "AP-2" is APPROVED

  Scenario: A rejection leaves the order as it was
    Given the tenant requires approval to cancel an order
    And an order "AP-3" for "CUST" from "ORIGIN"
    When "Asha" asks to cancel order "AP-3"
    And "Bilal" rejects the cancellation of order "AP-3" because "customer confirmed by phone"
    Then order "AP-3" now stands DRAFT
    And the cancellation request of order "AP-3" is REJECTED
