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

@pod
Feature: A proof of delivery decides a consignment's status and when money moves
  TC-CNG-011, TC-CNG-058, TC-CNG-094, TC-CNG-095, TC-CNG-151.

  Completing a trip does not wait for a proof of delivery; money does. A full
  truckload is in transit once its trip leaves, and delivered when either its
  trip completes or its proof is confirmed, whichever comes first. The carrier
  is paid for a trip only once every consignment on it is proved clean, or a
  person with the right releases the hold and says why. A client is invoiced
  for a trip's transport only once its consignments are proved, unless its
  contract bills on dispatch or someone marks them billable by hand.

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

  # ------------------------------------------------------------------ status

  Scenario: A confirmed proof delivers a full truckload before its trip completes, once
    Given an order "PD-1" for "CUST" from "ORIGIN"
    And order "PD-1" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "PD-1" is validated
    And consignments are generated for order "PD-1"
    And a load "LOAD-PD-1" is opened at "ORIGIN" on lorry "MH-01-TL-0001"
    And the shipment of order "PD-1" for "CONS-A" is put on load "LOAD-PD-1"
    And load "LOAD-PD-1" is planned
    When trip "TRIP-PD-1" runs load "LOAD-PD-1" for "VENDOR-1" on lorry "MH-01-TL-0001" driven by "Ravi"
    Then the shipment of order "PD-1" for "CONS-A" is "IN_TRANSIT"
    When a clean proof of delivery is recorded for the shipment of order "PD-1" for "CONS-A"
    Then the shipment of order "PD-1" for "CONS-A" is "DELIVERED"
    And order "PD-1" is "FULFILLED"
    When the proof of delivery for the shipment of order "PD-1" for "CONS-A" is announced again
    And trip "TRIP-PD-1" is completed at its destination
    Then the shipment of order "PD-1" for "CONS-A" is "DELIVERED"
    And the audit trail of the shipment of order "PD-1" for "CONS-A" reads "CONSIGNMENT_GENERATED, CONSIGNMENT_ASSIGNED, CONSIGNMENT_IN_TRANSIT, CONSIGNMENT_DELIVERED"

  # ------------------------------------------------------- the carrier's money

  Scenario: A completed trip waits for its proofs before the carrier is paid
    Given a trip "T1" carrying 5000 kg for "VENDOR-1"
    And trip "T1" is run, dispatched 2 days ago after 30 minutes at the origin
    When trip "T1" is completed without proofs of delivery
    Then bill "FB-T1" is "DRAFT"
    And bill "FB-T1" is awaiting proofs of delivery
    When "VENDOR-1" invoices 20000 against "FB-T1"
    Then matching is refused mentioning "clean proof of delivery"
    And bill "FB-T1" is "DRAFT"
    When a clean proof of delivery is recorded for every consignment on trip "T1"
    Then bill "FB-T1" is not awaiting proofs of delivery
    When "VENDOR-1" invoices 20000 against "FB-T1"
    Then bill "FB-T1" is "APPROVED_FOR_PAYMENT"

  Scenario: Proofs recorded before the trip completes leave nothing to hold
    Given a trip "T1" carrying 5000 kg for "VENDOR-1"
    And trip "T1" is run, dispatched 2 days ago after 30 minutes at the origin
    And a clean proof of delivery is recorded for every consignment on trip "T1"
    When trip "T1" is completed without proofs of delivery
    Then bill "FB-T1" is not awaiting proofs of delivery
    When "VENDOR-1" invoices 20000 against "FB-T1"
    Then bill "FB-T1" is "APPROVED_FOR_PAYMENT"

  Scenario: A proof recording damage keeps the hold until a person releases it, saying why
    Given a trip "T1" carrying 5000 kg for "VENDOR-1"
    And trip "T1" is run, dispatched 2 days ago after 30 minutes at the origin
    And trip "T1" is completed without proofs of delivery
    When a proof of delivery recording a DAMAGE because "Two cartons crushed" is recorded for every consignment on trip "T1"
    Then bill "FB-T1" is awaiting proofs of delivery
    When the proof hold on "FB-T1" is released with no reason given
    Then releasing the proof hold is refused mentioning "Say why"
    And bill "FB-T1" is awaiting proofs of delivery
    When the proof hold on "FB-T1" is released because "Damage accepted; claim raised against the carrier"
    Then bill "FB-T1" shows the proof hold released because "Damage accepted; claim raised against the carrier"
    And the release of the proof hold on "FB-T1" is audited with the reason "Damage accepted; claim raised against the carrier"
    When "VENDOR-1" invoices 20000 against "FB-T1"
    Then bill "FB-T1" is "APPROVED_FOR_PAYMENT"
    When the proof hold on "FB-T1" is released because "Released twice"
    Then releasing the proof hold is refused mentioning "is not held for proofs of delivery"

  Scenario: Releasing a proof hold needs its own right
    Given a trip "T1" carrying 5000 kg for "VENDOR-1"
    And trip "T1" is run, dispatched 2 days ago after 30 minutes at the origin
    And trip "T1" is completed without proofs of delivery
    And the signed-in user lacks "SETTLEMENT_HOLD_RELEASE"
    When the proof hold on "FB-T1" is released because "Looks fine to me"
    Then releasing the proof hold is refused as unauthorised
    And bill "FB-T1" is awaiting proofs of delivery

  # -------------------------------------------------------- the client's money

  Scenario: A client is invoiced for a trip's transport only once its consignments are proved or marked
    Given client "CUST" holds contract "CUST-2026" effective 90 days ago:
      | transport per kg | 5.00 |
    And a trip "T1" carrying 5000 kg for "VENDOR-1"
    And trip "T1" is run, dispatched 35 days ago after 30 minutes at the origin
    When trip "T1" is completed without proofs of delivery
    Then the transport charge of "CUST" for trip "T1" waits for proofs of delivery
    When the month 35 days ago is billed for "CUST"
    Then client billing refuses mentioning "wait for proofs of delivery"
    When every consignment on trip "T1" is marked billable because "Consignee signed a paper copy"
    And every consignment on trip "T1" is marked billable because "Consignee signed a paper copy"
    Then the marks on trip "T1" are audited once each with the reason "Consignee signed a paper copy"
    And the transport charge of "CUST" for trip "T1" is billable
    When the month 35 days ago is billed for "CUST"
    Then the invoice of "CUST" for the month 35 days ago bills transport on 5000 kg

  Scenario: A confirmed proof makes a client's transport billable
    Given client "CUST" holds contract "CUST-2026" effective 90 days ago:
      | transport per kg | 5.00 |
    And a trip "T1" carrying 5000 kg for "VENDOR-1"
    And trip "T1" is run, dispatched 35 days ago after 30 minutes at the origin
    And trip "T1" is completed without proofs of delivery
    When a proof of delivery recording a SHORTAGE because "One pallet short" is recorded for every consignment on trip "T1"
    Then the transport charge of "CUST" for trip "T1" is billable
    And bill "FB-T1" is awaiting proofs of delivery

  Scenario: A client whose contract bills on dispatch is charged, and billable, when the trip leaves
    Given client "CUST" holds contract "CUST-ADV" effective 90 days ago:
      | transport per kg  | 5.00 |
      | bills on dispatch | yes  |
    And a trip "T1" carrying 5000 kg for "VENDOR-1"
    When trip "T1" is run, dispatched 35 days ago after 30 minutes at the origin
    Then "CUST" was charged transport on 5000 kg for trip "T1"
    And the transport charge of "CUST" for trip "T1" is billable
    When trip "T1" is completed without proofs of delivery
    Then "CUST" has 1 transport charge for trip "T1"
    When the month 35 days ago is billed for "CUST"
    Then the invoice of "CUST" for the month 35 days ago bills transport on 5000 kg
