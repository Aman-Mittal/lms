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

@delivery
Feature: A consignee confirms a delivery with a code, and a claim can follow, over the wire
  Proof of delivery and claims, end to end through the published API only. A
  consignment that has left is delivered when the consignee gives the one-time
  code the desk issued; a claim is then raised against it and decided. Every
  step is a request made with the client generated from the contract, against a
  consignment that a real dispatched trip carries.

  Background:
    Given the demo administrator is signed in at the delivery desk
    And a consignment is in transit for delivery

  Scenario: The right code proves the delivery and a wrong one does not
    When the desk issues a delivery code
    And a wrong code is given for the delivery
    Then the delivery is refused as an invalid code
    When the right code is given by recipient "Asha Rao" with photo "s3://pod/asha.jpg"
    Then the delivery proof is confirmed for "Asha Rao" with photo "s3://pod/asha.jpg"
    And a new code can no longer be issued for the delivery

  Scenario: Three wrong codes lock the code until a new one is issued
    When the desk issues a delivery code
    And three wrong codes are given for the delivery
    Then the right code is still refused
    When the desk issues a delivery code
    And the right code is given by recipient "Asha Rao" without a photo
    Then the delivery proof is confirmed for "Asha Rao" without a photo

  Scenario: Damage is claimed after delivery and decided through to settlement
    Given the delivery has been proved
    When a damage claim of 2500.50 is raised for the consignment
    And the claim is taken under review
    And the claim is approved for 1800
    And the claim is settled
    Then the claim is settled for 1800
    And the consignment's claims list holds exactly that claim

  Scenario: A damage claim is refused before delivery but a loss claim is not
    When a damage claim of 100 is raised for the consignment
    Then the claim is refused because delivery has not been proved
    When a loss claim of 100 is raised for the consignment
    Then the consignment's claims list holds exactly that claim

  Scenario: Another company sees neither the delivery nor the claim
    Given the delivery has been proved
    And a damage claim of 100 is raised for the consignment
    And a second company with its own administrator is signed in for deliveries
    Then the second company is told the consignment does not exist when it asks for a code
    And the second company sees none of the consignment's claims
