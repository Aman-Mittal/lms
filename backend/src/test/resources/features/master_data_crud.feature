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

Feature: Fleet compliance and partner lifecycle

Feature: Maintaining partners, vehicles and drivers
  Issue 238. Nothing in master data is deleted: a vehicle or driver is switched
  off with a reason, and an edit is only accepted against the version that was read.

  Background:
    Given a tenant "acme" named "Acme Logistics"
    And tenant "acme" has an organisational unit "/acme/" named "Acme HQ" of type "HQ"
    And the tenant scope is "acme" at "/acme/"
    And a vehicle "MH-04-CR-0001" with gross 16000 kg and tare 6000 kg and volume 40 m3
    And a driver "Meera Nair" with licence "DL-CRUD-1" expiring in 365 days
    And a CUSTOMER partner "CRUD-C1" named "Crud Customer"

  Scenario: An edit made from a stale version is refused and changes nothing
    When lorry "MH-04-CR-0001" is edited to 18000 kg gross
    Then the change succeeded
    When lorry "MH-04-CR-0001" is edited to 20000 kg gross from the version before the last change
    Then the edit is refused as stale
    And lorry "MH-04-CR-0001" is "AVAILABLE" and its gross weight is 18000 kg

  Scenario: A vehicle is switched off with a reason, leaves the default list, and comes back
    When lorry "MH-04-CR-0001" is deactivated without a reason
    Then the change is refused for want of a reason
    When lorry "MH-04-CR-0001" is deactivated because "sold to another operator"
    Then lorry "MH-04-CR-0001" is "INACTIVE" and its gross weight is 16000 kg
    And the default vehicle list no longer shows "MH-04-CR-0001" but the full list does
    And the vehicle audit trail has a "VEHICLE_DEACTIVATED" entry for "MH-04-CR-0001"
    And the audit trail records reasons for "VEHICLE_DEACTIVATED"
    When lorry "MH-04-CR-0001" is reactivated because "bought back"
    Then lorry "MH-04-CR-0001" is "AVAILABLE" and its gross weight is 16000 kg

  Scenario: A driver is switched off and on again
    When driver "DL-CRUD-1" is deactivated because "left the company"
    Then driver "DL-CRUD-1" is "INACTIVE"
    When driver "DL-CRUD-1" is reactivated because "rejoined"
    Then driver "DL-CRUD-1" is "AVAILABLE"

  Scenario: A partner is suspended with a reason and drops out of the default list
    Given partner "CRUD-C1" moves to "PENDING_VERIFICATION"
    And partner "CRUD-C1" moves to "ACTIVE"
    When partner "CRUD-C1" is suspended without a reason
    Then the change is refused for want of a reason
    And partner "CRUD-C1" is "ACTIVE"
    When partner "CRUD-C1" is suspended because "credit review"
    Then partner "CRUD-C1" is "SUSPENDED"
    And the default partner list no longer shows "CRUD-C1"
