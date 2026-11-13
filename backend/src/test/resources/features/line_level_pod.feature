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

@pod-lines
Feature: A proof of delivery is a draft fed line by line, then submitted
  TC-CNG-011, FR-CNG-015, FR-CNG-016, FR-CNG-017, FR-CNG-018, FR-CNG-019.

  Starting a proof makes one draft however often it is asked. The draft is fed
  with what was received against each order line, with the times the vehicle
  reported and unloaded, and then submitted, with a code or keyed from paper.
  Times must run in order: reporting, unloading start, unloading end.

  Background:
    Given a tenant "acme" named "Acme Logistics"
    And tenant "acme" has an organisational unit "/acme/" named "Acme HQ" of type "HQ"
    And the tenant scope is "acme" at "/acme/"
    And a trading partner "CUST"
    And a trading partner "CONS-A"
    And a depot "ORIGIN" at 19.100,72.900
    And a depot "DEST-A" at 19.300,73.100
    And a carrier "VENDOR-1"
    And a lorry "MH-01-TL-0001" with payload 20000 kg and volume 60 m3
    And lorry "MH-01-TL-0001" has an "INSURANCE" certificate valid for 365 days
    And a lorry driver "Ravi" licensed for 365 days
    And a rate card "WEST-2026" from "ORIGIN" to "DEST-A" for "VENDOR-1" effective 30 days ago:
      | free hours         | 2 |
      | detention per hour | 0 |
      | additional drop    | 0 |
      | minimum charge     | 0 |
    And the card charges 4.00 per kg for any weight

  Scenario: A draft is started once, fed over time, and keyed from paper
    Given an order "LP-1" for "CUST" from "ORIGIN"
    And order "LP-1" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "LP-1" is validated
    And consignments are generated for order "LP-1"
    And a load "LOAD-LP-1" is opened at "ORIGIN" on lorry "MH-01-TL-0001"
    And the shipment of order "LP-1" for "CONS-A" is put on load "LOAD-LP-1"
    And load "LOAD-LP-1" is planned
    When trip "TRIP-LP-1" runs load "LOAD-LP-1" for "VENDOR-1" on lorry "MH-01-TL-0001" driven by "Ravi"
    And the proof of delivery for the shipment of order "LP-1" for "CONS-A" is started
    And the proof of delivery for the shipment of order "LP-1" for "CONS-A" is started
    Then the shipment of order "LP-1" for "CONS-A" has 1 proof of delivery record
    And the proof of delivery for the shipment of order "LP-1" for "CONS-A" is "PENDING"
    When 1 received and 0 short is fed for line 1 of the proof for the shipment of order "LP-1" for "CONS-A"
    Then the proof for the shipment of order "LP-1" for "CONS-A" shows 1 received and 0 short on line 1
    When paper is keyed by the receiving clerk for the shipment of order "LP-1" for "CONS-A"
    Then the proof of delivery for the shipment of order "LP-1" for "CONS-A" is "SUBMITTED"
    And the proof for the shipment of order "LP-1" for "CONS-A" is a paper proof
    And the shipment of order "LP-1" for "CONS-A" is "DELIVERED"

  Scenario: Unloading times must run in order
    Given an order "LP-2" for "CUST" from "ORIGIN"
    And order "LP-2" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "LP-2" is validated
    And consignments are generated for order "LP-2"
    And a load "LOAD-LP-2" is opened at "ORIGIN" on lorry "MH-01-TL-0001"
    And the shipment of order "LP-2" for "CONS-A" is put on load "LOAD-LP-2"
    And load "LOAD-LP-2" is planned
    When trip "TRIP-LP-2" runs load "LOAD-LP-2" for "VENDOR-1" on lorry "MH-01-TL-0001" driven by "Ravi"
    And the proof of delivery for the shipment of order "LP-2" for "CONS-A" is started
    And the times are set to reporting 30, unloading start 40 and unloading end 10 minutes ago for the shipment of order "LP-2" for "CONS-A"
    Then the step is refused with "pod-timing-order-invalid"
    And the proof for the shipment of order "LP-2" for "CONS-A" has no times
    When the times are set to reporting 40, unloading start 30 and unloading end 10 minutes ago for the shipment of order "LP-2" for "CONS-A"
    Then the proof of delivery for the shipment of order "LP-2" for "CONS-A" is "PENDING"
