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

Feature: Charges raised by hand, vendor payables and advances
  Issue #225.

  A freight bill is priced from a rate card and is never re-priced; what
  happens around it is money too. A charge raised by hand comes from a
  catalogue, is approved when it is large, and is attached to the vendor's bill
  only while that bill is an open draft -- an approved bill is left exactly as it
  was and the charge waits for the vendor's next one. A vendor's invoice must
  agree with what was approved, and an advance against a trip may not exceed a
  share of its freight.

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
      | free hours         | 2   |
      | detention per hour | 300 |
      | additional drop    | 750 |
      | minimum charge     | 0   |
    And the card charges 4.00 per kg for any weight
    And a catalogue charge "TOLL" counted "PER_UNIT" at 100 for "PAYABLE"
    And a catalogue charge "HALT" counted "PER_DAY" at 400 for "RECEIVABLE"

  Scenario: A charge for a payable is a line of the trip's open bill
    Given a trip "T1" carrying 5000 kg for "VENDOR-1"
    When trip "T1" is run, dispatched 2 days ago after 30 minutes at the origin
    And trip "T1" is completed
    And a charge "TOLL" of 3 is raised against the trip of bill "FB-T1"
    Then the charge is "APPROVED"
    And the charge is a line of bill "FB-T1"
    And bill "FB-T1" totals 20300
    And bill "FB-T1" has a "MANUAL_CHARGE" line of 300
    And bill "FB-T1" adds up
    When the charge is cancelled because "The toll was waived"
    Then the charge is "CANCELLED"
    And bill "FB-T1" totals 20000
    And bill "FB-T1" has no "MANUAL_CHARGE" line

  Scenario: An approved bill is never re-priced; the charge waits for the vendor's next bill
    Given a trip "T1" carrying 5000 kg for "VENDOR-1"
    When trip "T1" is run, dispatched 2 days ago after 30 minutes at the origin
    And trip "T1" is completed
    And "VENDOR-1" invoices 20000 against "FB-T1"
    Then bill "FB-T1" is "APPROVED_FOR_PAYMENT"
    When a charge "TOLL" of 3 is raised against the trip of bill "FB-T1"
    Then the charge is "APPROVED"
    And the charge waits for the vendor's next bill
    And bill "FB-T1" totals 20000
    And bill "FB-T1" has no "MANUAL_CHARGE" line
    Given a trip "T2" carrying 5000 kg for "VENDOR-1"
    When trip "T2" is run, dispatched 1 days ago after 30 minutes at the origin
    And trip "T2" is completed
    Then the charge is a line of bill "FB-T2"
    And bill "FB-T2" totals 20300
    And bill "FB-T1" totals 20000

  Scenario: A charge above the threshold waits for an approval
    Given the manual charge approval threshold is 1000
    And a trip "T1" carrying 5000 kg for "VENDOR-1"
    When trip "T1" is run, dispatched 2 days ago after 30 minutes at the origin
    And trip "T1" is completed
    And a charge "HALT" of 2 is raised against the trip of bill "FB-T1"
    Then the charge is "APPROVED"
    When a charge "HALT" of 3 is raised against the trip of bill "FB-T1"
    Then the charge is "PENDING_APPROVAL"
    And an approval request is open for the charge
    When the charge is cancelled because "Raised in error"
    Then the charge is "CANCELLED"
    And no approval request is open for the charge

  Scenario: A charge that cannot be raised is refused with its reason
    Given a trip "T1" carrying 5000 kg for "VENDOR-1"
    When trip "T1" is run, dispatched 2 days ago after 30 minutes at the origin
    And trip "T1" is completed
    And a charge "TOLL" of 3 is raised on the order of bill "FB-T1"
    Then raising the charge is refused mentioning "manual-charge-payable-needs-trip"
    When a charge "TOLL" of 0 is raised against the trip of bill "FB-T1"
    Then raising the charge is refused mentioning "manual-charge-quantity-invalid"

  Scenario: A vendor's invoice must agree with what was approved
    Given a trip "T1" carrying 5000 kg for "VENDOR-1"
    When trip "T1" is run, dispatched 2 days ago after 30 minutes at the origin
    And trip "T1" is completed
    And "VENDOR-1" invoices 20000 against "FB-T1"
    And the vendor's invoice "INV-1" for 25000 is captured against bill "FB-T1"
    Then capturing the invoice is refused mentioning "vendor-invoice-mismatch"
    When the vendor's invoice "INV-1" for 20100 is captured against bill "FB-T1"
    Then invoice "INV-1" has 20100 outstanding
    When 5000 is paid against invoice "INV-1" by reference "UTR-1"
    Then invoice "INV-1" has 15100 outstanding
    And invoice "INV-1" is "PARTIALLY_PAID"
    When 15200 is paid against invoice "INV-1" by reference "UTR-2"
    Then paying is refused mentioning "vendor-payment-exceeds-balance"
    When 15100 is paid against invoice "INV-1" by reference "UTR-3"
    Then invoice "INV-1" is "PAID"

  Scenario: An advance may not exceed the cap on the trip's freight, and is deducted from the vendor's invoice
    Given a trip "T1" carrying 5000 kg for "VENDOR-1"
    When trip "T1" is run, dispatched 2 days ago after 30 minutes at the origin
    And trip "T1" is completed
    And an advance of 10000 by "BANK" is requested for the trip of bill "FB-T1"
    Then the advance is "PENDING_APPROVAL"
    When an advance of 4001 by "CASH" is requested for the trip of bill "FB-T1"
    Then the advance request is refused mentioning "advance-exceeds-cap"
    When an advance of 10 by "BANK" is requested for the trip of bill "FB-T1"
    Then the advance request is refused mentioning "advance-mode-already-used"
    When an advance of 4000 by "CASH" is requested for the trip of bill "FB-T1"
    Then the advance is "PENDING_APPROVAL"
