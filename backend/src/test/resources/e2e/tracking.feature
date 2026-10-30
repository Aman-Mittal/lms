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

@tracking
Feature: A customer follows a consignment from a link, over the wire
  The desk gives a customer a link; the customer opens it with no account and
  sees a small, safe view of where the consignment is. Everything is a request
  made with the client generated from the contract, against a consignment that a
  real dispatched trip carries.

  Background:
    Given the demo administrator is signed in for tracking
    And a consignment is in transit for tracking

  Scenario: A customer follows the consignment without signing in
    When the desk issues a tracking link
    Then the customer sees the consignment in transit from "TK-ORIGIN" to "TK-DEST" with no proof of delivery
    And the desk's list shows one active link and never the token

  Scenario: The customer sees the vehicle's last position, rounded
    Given the vehicle reports a position of 26.2345 and 73.0678
    When the desk issues a tracking link
    Then the customer sees the position 26.23 and 73.07

  Scenario: The link shows the delivery once the recipient has confirmed it
    Given the desk issues a tracking link
    When the delivery is proved by recipient "Asha Rao"
    Then the customer sees the consignment delivered with a proof of delivery
    And the customer is not told who received it

  Scenario: A revoked link looks like one that never existed
    Given the desk issues a tracking link
    When the desk revokes the tracking link
    Then the customer is told the link is not valid, as for a link nobody issued

  Scenario: An expired link looks like one that never existed
    Given the desk issues a tracking link
    When the tracking link passes its expiry
    Then the customer is told the link is not valid, as for a link nobody issued

  Scenario: Another company cannot manage the links
    Given the desk issues a tracking link
    And a second company with its own administrator is signed in for tracking
    Then the second company is told the consignment does not exist when it issues a link
    And the second company is told the link does not exist when it revokes it
