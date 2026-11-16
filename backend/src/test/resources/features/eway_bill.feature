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

@eway-bill
Feature: Statutory e-way bills per consignment, raised through a pluggable adapter
  TC-CNG-028, FR-CNG-014, FR-CNG-028, FR-CNG-029.

  A unit chooses an adapter (NONE, the default, or the deterministic STUB). With
  none, nothing about a consignment or a dispatch changes. With one, a bill is
  generated for a consignment, its Part-B vehicle updated, its validity extended,
  or cancelled within the window, and every refusal is a stable problem code. A
  unit may also require a live bill on every consignment before a trip leaves.

  Background:
    Given a tenant "acme" named "Acme Logistics"
    And tenant "acme" has an organisational unit "/acme/" named "Acme HQ" of type "HQ"
    And the tenant scope is "acme" at "/acme/"
    And a trading partner "CUST"
    And a trading partner "CONS-A"
    And a depot "ORIGIN" at 19.10,72.90
    And a depot "DEST-A" at 19.30,73.10
    And a carrier "VENDOR-1"
    And a lorry "MH-01-EW-0001" with payload 10000 kg and volume 40 m3
    And lorry "MH-01-EW-0001" has an "INSURANCE" certificate valid for 365 days
    And lorry "MH-01-EW-0001" has a "FITNESS" certificate valid for 365 days
    And a lorry driver "Ravi" licensed for 365 days

  Scenario: Without an adapter nothing changes, and a bill cannot be raised
    Given an awarded load "LOAD-W1" of 5000 kg from "ORIGIN" to "CONS-A" at "DEST-A" on lorry "MH-01-EW-0001" for "VENDOR-1"
    When an e-way bill is generated for the consignment on load "LOAD-W1"
    Then the e-way bill step is refused with "eway-bill-disabled"
    And the consignment on load "LOAD-W1" has 0 e-way bills
    When a trip "TRIP-W1" is raised against load "LOAD-W1"
    And trip "TRIP-W1" is assigned lorry "MH-01-EW-0001" and driver "Ravi"
    And trip "TRIP-W1" gates in at 08:00
    And trip "TRIP-W1" is weighed 6000 kg empty
    And trip "TRIP-W1" is weighed 11000 kg laden
    And trip "TRIP-W1" is marked loaded
    And trip "TRIP-W1" carries a "CONSIGNMENT_NOTE" numbered "LR-0001"
    And trip "TRIP-W1" carries an "EWAY_BILL" numbered "EW-0001"
    And trip "TRIP-W1" is dispatched at 10:00
    Then trip "TRIP-W1" is "DISPATCHED"

  Scenario: A bill is generated, put on Part-B, extended, found by its number and cancelled
    Given the unit uses the "STUB" e-way bill adapter
    And an awarded load "LOAD-W2" of 5000 kg from "ORIGIN" to "CONS-A" at "DEST-A" on lorry "MH-01-EW-0001" for "VENDOR-1"
    When an e-way bill is generated for the consignment on load "LOAD-W2"
    Then the e-way bill of the consignment on load "LOAD-W2" is "ACTIVE"
    And the e-way bill of the consignment on load "LOAD-W2" has a twelve digit number
    And the e-way bill of the consignment on load "LOAD-W2" is found by its number
    When "PART_B" is applied to the e-way bill of the consignment on load "LOAD-W2" with reason "" and vehicle "MH 01 EW 0001"
    Then the e-way bill of the consignment on load "LOAD-W2" is on Part-B for vehicle "MH01EW0001"
    When "EXTEND" is applied to the e-way bill of the consignment on load "LOAD-W2" with reason "road closed" and vehicle ""
    Then the e-way bill of the consignment on load "LOAD-W2" is "EXTENDED"
    When "CANCEL" is applied to the e-way bill of the consignment on load "LOAD-W2" with reason "goods not moved" and vehicle ""
    Then the e-way bill of the consignment on load "LOAD-W2" is "CANCELLED"
    When "EXTEND" is applied to the e-way bill of the consignment on load "LOAD-W2" with reason "again" and vehicle ""
    Then the e-way bill step is refused with "eway-bill-not-live"

  Scenario: The adapter can refuse, and an unknown action is an error rather than a no-op
    Given the unit uses the "STUB" e-way bill adapter
    And an awarded load "LOAD-W3" of 5000 kg from "ORIGIN" to "CONS-A" at "DEST-A" on lorry "MH-01-EW-0001" for "VENDOR-1"
    When an e-way bill is generated for the consignment on load "LOAD-W3" against document "LR-REFUSE-1"
    Then the e-way bill step is refused with "eway-bill-refused"
    And the consignment on load "LOAD-W3" has 0 e-way bills
    When an e-way bill is generated for the consignment on load "LOAD-W3"
    Then the e-way bill step is not refused
    When "REVOKE" is applied to the e-way bill of the consignment on load "LOAD-W3" with reason "x" and vehicle ""
    Then the e-way bill step is refused with "eway-action-unknown"
    When "EXTEND" is applied to the e-way bill of the consignment on load "LOAD-W3" with reason "" and vehicle ""
    Then the e-way bill step is refused with "eway-reason-required"

  Scenario: Dispatch waits for a live bill when the unit requires one
    Given the unit uses the "STUB" e-way bill adapter
    And the unit requires an e-way bill on every consignment to dispatch
    And an awarded load "LOAD-W4" of 5000 kg from "ORIGIN" to "CONS-A" at "DEST-A" on lorry "MH-01-EW-0001" for "VENDOR-1"
    And a trip "TRIP-W4" is raised against load "LOAD-W4"
    And trip "TRIP-W4" is assigned lorry "MH-01-EW-0001" and driver "Ravi"
    And trip "TRIP-W4" gates in at 08:00
    And trip "TRIP-W4" is weighed 6000 kg empty
    And trip "TRIP-W4" is weighed 11000 kg laden
    And trip "TRIP-W4" is marked loaded
    And trip "TRIP-W4" carries a "CONSIGNMENT_NOTE" numbered "LR-0004"
    And trip "TRIP-W4" carries an "EWAY_BILL" numbered "EW-0004"
    When trip "TRIP-W4" is dispatched at 10:00
    Then dispatch is refused mentioning "has no active e-way bill"
    And trip "TRIP-W4" is "LOADED"
    When an e-way bill is generated for the consignment on load "LOAD-W4"
    And trip "TRIP-W4" is dispatched at 10:00
    Then trip "TRIP-W4" is "DISPATCHED"
