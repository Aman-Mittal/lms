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

@docket-cancellation
Feature: Cancelling a docket retracts what it was billed
  Issue #18 follow-up. Planning announced a docket when it was booked and
  finance raised a receivable from the announcement, but nothing announced the
  docket's cancellation, so a cancelled shipment stayed billable.

  The receivable of a cancelled docket becomes CANCELLED with the reason. Its
  amounts stay on the row as what was quoted, so a dispute can still be argued
  from them; a cancelled receivable is not billable. No docket receivable is
  in an invoice today (client billing leaves LTL out and finance has no
  invoicing of dockets), so there is nothing to credit-note.

  Background:
    Given a tenant "acme" named "Acme Logistics"
    And tenant "acme" has an organisational unit "/acme/" named "Acme HQ" of type "HQ"
    And the tenant scope is "acme" at "/acme/"
    And a trading partner "CUST-1"
    And a trading partner "CONS-A"
    And a trading partner "CONS-B"
    And a hub "HUB-BOM" at 19.10,72.90
    And a hub "HUB-DEL" at 28.60,77.20
    And a hub "HUB-CCU" at 22.55,88.35
    And a depot "SITE-A" at 28.40,77.00
    And a depot "SITE-B" at 29.40,77.90
    And terminal "HUB-BOM" is in LTL zone "WEST"
    And terminal "HUB-DEL" is in LTL zone "NORTH"
    And terminal "HUB-CCU" is in LTL zone "EAST"
    And an LTL rate card "WN-1" from zone "WEST" to zone "NORTH" charging 12.00 per kg with a 50 kg minimum, 6 kg per CFT, a 100 docket charge and a 500 ODA surcharge

  Scenario: Cancelling a part-load order cancels the receivable of each of its dockets
    Given an LTL booking "DC-1" for "CUST-1" through hub "HUB-BOM" to hub "HUB-DEL"
    And order "DC-1" has line 1 of 200 kg of GENERAL for "CONS-A" at "SITE-A"
    And order "DC-1" has line 2 of 100 kg of GENERAL for "CONS-B" at "SITE-B"
    And order "DC-1" is validated
    And consignments are generated for order "DC-1"
    And the receivable for order "DC-1" and "CONS-A" totals 2500.00
    When order "DC-1" is cancelled with the reason "customer withdrew the booking"
    Then the receivable for order "DC-1" and "CONS-A" is cancelled because "was cancelled"
    And the receivable for order "DC-1" and "CONS-B" is cancelled because "was cancelled"
    And the cancelled receivable for order "DC-1" and "CONS-A" still records the quoted 2500.00

  Scenario: A docket that was never priced is cancelled too, not left unpriced
    Given an LTL booking "DC-2" for "CUST-1" through hub "HUB-BOM" to hub "HUB-CCU"
    And order "DC-2" has line 1 of 200 kg of GENERAL for "CONS-A" at "SITE-A"
    And order "DC-2" is validated
    And consignments are generated for order "DC-2"
    And the receivable for order "DC-2" and "CONS-A" is unpriced because "No LTL rate card"
    When order "DC-2" is cancelled
    Then the receivable for order "DC-2" and "CONS-A" is cancelled because "was cancelled"

  Scenario: Cancelling a full-truckload order has no receivable to retract and changes nothing else
    Given an order "DC-3" for "CUST-1" from "HUB-BOM"
    And order "DC-3" has line 1 of 200 kg of GENERAL for "CONS-A" at "SITE-A"
    And order "DC-3" is validated
    And consignments are generated for order "DC-3"
    When order "DC-3" is cancelled
    Then order "DC-3" is "CANCELLED"
    And order "DC-3" raised no docket receivable

  Scenario: The same cancellation delivered twice cancels the receivable once
    Given an LTL booking "DC-4" for "CUST-1" through hub "HUB-BOM" to hub "HUB-DEL"
    And order "DC-4" has line 1 of 200 kg of GENERAL for "CONS-A" at "SITE-A"
    And order "DC-4" is validated
    And consignments are generated for order "DC-4"
    And order "DC-4" is cancelled
    When the cancellation of the dockets of order "DC-4" is delivered again
    Then the receivable for order "DC-4" and "CONS-A" is cancelled because "was cancelled"
    And the receivable for order "DC-4" and "CONS-A" was cancelled in the audit trail exactly once

  Scenario: A cancellation naming a docket that never had a receivable changes nothing
    Given an LTL booking "DC-5" for "CUST-1" through hub "HUB-BOM" to hub "HUB-DEL"
    And order "DC-5" has line 1 of 200 kg of GENERAL for "CONS-A" at "SITE-A"
    And order "DC-5" is validated
    When a cancellation naming an unknown docket is delivered
    Then no receivable is cancelled
