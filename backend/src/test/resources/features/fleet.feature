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
  Vision document 3.2.1 and 3.2.2.

  Two hard stops the platform must never let through: dispatching a vehicle
  whose statutory documents have lapsed, and giving work to a partner who has
  not passed, or has failed, verification.

  Background:
    Given a tenant "acme" named "Acme Logistics"
    And tenant "acme" has an organisational unit "/acme/" named "Acme HQ" of type "HQ"
    And the tenant scope is "acme" at "/acme/"

  Scenario: A vehicle with valid documents is cleared to dispatch
    Given a vehicle "MH-01-AB-1234" with gross 16000 kg and tare 6000 kg and volume 40 m3
    And vehicle "MH-01-AB-1234" has an "INSURANCE" document expiring in 90 days
    And vehicle "MH-01-AB-1234" has a "REGISTRATION" document that never expires
    When dispatch readiness is checked for vehicle "MH-01-AB-1234" today
    Then the vehicle is cleared to dispatch

  Scenario: An expired insurance certificate blocks dispatch
    Given a vehicle "MH-01-AB-1234" with gross 16000 kg and tare 6000 kg and volume 40 m3
    And vehicle "MH-01-AB-1234" has an "INSURANCE" document expiring in -1 days
    When dispatch readiness is checked for vehicle "MH-01-AB-1234" today
    Then dispatch is blocked
    And a blocking reason mentions "INSURANCE"

  Scenario: A document with no expiry date never blocks dispatch
    # The common case, not the exception: plenty of registrations are permanent.
    # Treating a null expiry as lapsed would ground the entire fleet.
    Given a vehicle "MH-01-AB-1234" with gross 16000 kg and tare 6000 kg and volume 40 m3
    And vehicle "MH-01-AB-1234" has a "REGISTRATION" document that never expires
    When dispatch readiness is checked for vehicle "MH-01-AB-1234" today
    Then the vehicle is cleared to dispatch

  Scenario: A certificate expiring before a future dispatch date blocks that dispatch
    # Valid today is not the question. The question is whether it is valid on the
    # day the vehicle actually leaves.
    Given a vehicle "MH-01-AB-1234" with gross 16000 kg and tare 6000 kg and volume 40 m3
    And vehicle "MH-01-AB-1234" has an "INSURANCE" document expiring in 5 days
    When dispatch readiness is checked for vehicle "MH-01-AB-1234" in 30 days
    Then dispatch is blocked
    And a blocking reason mentions "INSURANCE"

  Scenario: Every blocking reason is reported at once
    # An operator should learn about all the problems in one pass, not discover
    # them one failed dispatch at a time.
    Given a vehicle "MH-01-AB-1234" with gross 16000 kg and tare 6000 kg and volume 40 m3
    And vehicle "MH-01-AB-1234" has an "INSURANCE" document expiring in -1 days
    And vehicle "MH-01-AB-1234" has an "EMISSION" document expiring in -10 days
    When dispatch readiness is checked for vehicle "MH-01-AB-1234" today
    Then dispatch is blocked
    And 2 blocking reasons are reported

  Scenario: An expired driving licence blocks dispatch
    Given a vehicle "MH-01-AB-1234" with gross 16000 kg and tare 6000 kg and volume 40 m3
    And a driver "Ravi Kumar" with licence "DL-9988" expiring in -3 days
    When dispatch readiness is checked for vehicle "MH-01-AB-1234" and driver "Ravi Kumar" today
    Then dispatch is blocked
    And a blocking reason mentions "licence"

  Scenario: A driver over their hours of service blocks dispatch
    Given a vehicle "MH-01-AB-1234" with gross 16000 kg and tare 6000 kg and volume 40 m3
    And a driver "Ravi Kumar" with licence "DL-9988" expiring in 365 days
    And driver "Ravi Kumar" has driven 700 minutes today
    When dispatch readiness is checked for vehicle "MH-01-AB-1234" and driver "Ravi Kumar" today
    Then dispatch is blocked
    And a blocking reason mentions "hours of service"

  Scenario: A vehicle under maintenance cannot dispatch
    Given a vehicle "MH-01-AB-1234" with gross 16000 kg and tare 6000 kg and volume 40 m3
    And vehicle "MH-01-AB-1234" is put into MAINTENANCE
    When dispatch readiness is checked for vehicle "MH-01-AB-1234" today
    Then dispatch is blocked
    And a blocking reason mentions "MAINTENANCE"

  Scenario: Payload capacity excludes the vehicle's own weight
    # Allocating against gross weight would over-load every vehicle by its tare.
    Given a vehicle "MH-01-AB-1234" with gross 16000 kg and tare 6000 kg and volume 40 m3
    Then vehicle "MH-01-AB-1234" has a payload capacity of 10000 kg

  Scenario: A vehicle whose tare exceeds its gross weight is rejected
    When a vehicle "MH-01-BAD-1" with gross 6000 kg and tare 16000 kg and volume 40 m3 is registered
    Then vehicle registration is refused as invalid

  Scenario: A partner must be verified before it can be given work
    Given a VENDOR partner "TRANS-1" named "Transporter One"
    Then partner "TRANS-1" is in status "DRAFT"
    And partner "TRANS-1" cannot transact
    When partner "TRANS-1" moves to "PENDING_VERIFICATION"
    And partner "TRANS-1" moves to "ACTIVE"
    Then partner "TRANS-1" can transact

  Scenario: A suspended partner cannot be given work
    Given a VENDOR partner "TRANS-1" named "Transporter One"
    And partner "TRANS-1" moves to "PENDING_VERIFICATION"
    And partner "TRANS-1" moves to "ACTIVE"
    When partner "TRANS-1" moves to "SUSPENDED"
    Then partner "TRANS-1" cannot transact

  Scenario: Blacklisting is terminal
    # Reinstating a blacklisted partner should be a deliberate, recorded act --
    # not a status edit that leaves no trace of why the block was lifted.
    Given a VENDOR partner "TRANS-1" named "Transporter One"
    And partner "TRANS-1" moves to "BLACKLISTED"
    When partner "TRANS-1" moves to "ACTIVE"
    Then the transition is refused

  Scenario: A vehicle cannot be assigned to an unverified partner
    Given a VENDOR partner "TRANS-1" named "Transporter One"
    When a vehicle "MH-02-CD-5678" owned by "TRANS-1" is registered
    Then vehicle registration is refused because the partner cannot transact
