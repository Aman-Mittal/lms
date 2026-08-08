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

Feature: One order becomes one invoice
  The whole spine, walked once.

  Every other feature file tests a context. This one tests that the contexts
  join up -- an order raised at one end produces a priced, matched freight bill
  at the other, with nothing in between reached by a shortcut. It is deliberately
  composed from the same steps the individual features use: a scenario needing
  its own vocabulary would be exercising a parallel implementation rather than
  the one those features describe.

  The numbers are chosen so that each stage's contribution is visible in the
  final total. 1 000 kg measuring two metres cubed is eight cubic metres, and
  eight cubic metres of air weigh 1 600 kg on the road-freight convention -- so
  chargeable weight has to pick the volumetric figure, and the bill has to show
  it. Gate-in at 08:00 and gate-out at 11:30 is three and a half hours, two of
  them free, so detention has to be 450 and not 1 050.

  Background:
    Given a tenant "acme" named "Acme Logistics"
    And tenant "acme" has an organisational unit "/acme/" named "Acme HQ" of type "HQ"
    And the tenant scope is "acme" at "/acme/"
    And a trading partner "CUST"
    And a trading partner "CONS-A"
    And a carrier "VENDOR-1"
    And a carrier "VENDOR-2"
    And a depot "ORIGIN" at 19.100,72.900
    And a depot "DEST-A" at 19.300,73.100
    And a lorry "MH-01-TL-0001" with payload 20000 kg and volume 60 m3
    And lorry "MH-01-TL-0001" has an "INSURANCE" certificate valid for 365 days
    And a lorry driver "Ravi" licensed for 365 days

  Scenario: An order raised this morning is a matched freight bill by this evening
    # ---------------------------------------------------------------- 3.9.1
    # The rate card comes first, because pricing is against the version in
    # force on the dispatch date and the trip has not been dispatched yet.
    # It belongs to VENDOR-2, who is the vendor that ends up with the load.
    Given a rate card "WEST-2026" from "ORIGIN" to "DEST-A" for "VENDOR-2" effective 30 days ago:
      | free hours         | 2   |
      | detention per hour | 300 |
      | additional drop    | 750 |
      | minimum charge     | 0   |
    And the card charges 4.00 per kg for any weight

    # ------------------------------------------------------------------ 3.3
    # Light and bulky: 8 m3 of dead weight 1 000 kg. Billing the dead weight
    # would give away every cubic metre of it.
    When an order "SO-1" for "CUST" from "ORIGIN"
    And order "SO-1" has line 1 of 1000 kg of GENERAL measuring 2.0 by 2.0 by 2.0 m for "CONS-A" at "DEST-A"
    And order "SO-1" is validated
    Then order "SO-1" is "VALIDATED"

    # ------------------------------------------------------------------ 3.4
    When consignments are generated for order "SO-1"
    Then 1 consignments are created
    And the consignment for "CONS-A" at "DEST-A" is chargeable at 1600 kg

    When a load "LOAD-1" is opened at "ORIGIN" on lorry "MH-01-TL-0001"
    And the consignment for "CONS-A" at "DEST-A" is assigned to load "LOAD-1"
    And load "LOAD-1" is planned
    Then load "LOAD-1" is "PLANNED"

    # ------------------------------------------------------------------ 3.5
    # Rank 1 declines and the cascade moves on. A load nobody takes is a
    # commercial problem, so the guide is walked rather than retried.
    Given a routing guide from "ORIGIN" to "DEST-A" for "RIGID"
    And the guide ranks "VENDOR-1" first with a 30 minute SLA
    And the guide ranks "VENDOR-2" second with a 30 minute SLA
    When load "LOAD-1" is allocated
    Then the offer is with "VENDOR-1" at rank 1

    When "VENDOR-1" rejects the offer
    Then the offer is with "VENDOR-2" at rank 2

    When "VENDOR-2" accepts the offer
    Then the allocation is "AWARDED" to "VENDOR-2"
    And load "LOAD-1" is "AWARDED"

    # ------------------------------------------------------------------ 3.6
    When a trip "TRIP-1" is raised against load "LOAD-1"
    And trip "TRIP-1" is assigned lorry "MH-01-TL-0001" and driver "Ravi"
    And trip "TRIP-1" gates in at 08:00
    And trip "TRIP-1" is weighed 6000 kg empty
    And trip "TRIP-1" is weighed 7000 kg laden
    And trip "TRIP-1" is marked loaded
    Then trip "TRIP-1" has a payload of 1000 kg

    # The compliance gate refuses before it permits. Without this the scenario
    # would prove only that a dispatch can succeed, which is the easy half.
    When trip "TRIP-1" is dispatched at 11:30
    Then dispatch is refused mentioning "consignment note"

    When trip "TRIP-1" carries a "CONSIGNMENT_NOTE" numbered "LR-0001"
    And trip "TRIP-1" carries an "EWAY_BILL" numbered "EW-0001"
    And trip "TRIP-1" is dispatched at 11:30
    Then trip "TRIP-1" is "DISPATCHED"
    And trip "TRIP-1" has an origin dwell of 210 minutes

    # ------------------------------------------------------------------ 3.7
    # Nobody touches the trip from here. Three coordinates move it.
    Given a position for "MH-01-TL-0001" at 19.1000,72.9000 4 hours ago
    And a position for "MH-01-TL-0001" at 19.2000,73.0000 3 hours ago
    When the positions are ingested
    Then trip "TRIP-1" is "IN_TRANSIT"

    Given a position for "MH-01-TL-0001" at 19.3000,73.1000 2 hours ago
    When the positions are ingested
    Then trip "TRIP-1" is "AT_DESTINATION"
    And trip "TRIP-1" crossed "DEST-A" as "ENTERED"

    # ------------------------------------------------------------------ 3.9
    # Proof of delivery raises the bill. 1 600 chargeable kg at 4.00 is 6 400
    # of linehaul; ninety chargeable minutes at 300 an hour is 450 of
    # detention; one drop, so no drop fee.
    When trip "TRIP-1" is completed
    Then trip "TRIP-1" is "COMPLETED"
    And bill "FB-TRIP-1" is "DRAFT"
    And bill "FB-TRIP-1" was priced under "WEST-2026"
    And bill "FB-TRIP-1" has a "BASE_FREIGHT" line of 6400
    And bill "FB-TRIP-1" has a "DETENTION" line of 450
    And bill "FB-TRIP-1" has no "ADDITIONAL_DROP" line
    And bill "FB-TRIP-1" totals 6850
    And bill "FB-TRIP-1" adds up

    # ---------------------------------------------------------------- 3.9.2
    # Two per cent of 6 850 is 137, so fifty is noise and six hundred is not.
    When "VENDOR-2" invoices 6900 against "FB-TRIP-1"
    Then bill "FB-TRIP-1" is "APPROVED_FOR_PAYMENT"
    And the audit log records "INVOICE_APPROVED" with before and after snapshots

    # ------------------------------------------------------------------ 3.1
    # Everything above exists. None of it is visible to anybody else.
    Given a tenant "globex" named "Globex Freight"
    And tenant "globex" has an organisational unit "/globex/" named "Globex HQ" of type "HQ"
    And the tenant scope is "globex" at "/globex/"
    Then tenant "globex" sees 0 orders

  Scenario: The same movement, invoiced beyond tolerance, is disputed rather than paid
    # Identical up to settlement. Split from the walk above rather than
    # appended to it, because a scenario that ends twice is a scenario whose
    # last assertion nobody reads.
    Given a rate card "WEST-2026" from "ORIGIN" to "DEST-A" for "VENDOR-2" effective 30 days ago:
      | free hours         | 2   |
      | detention per hour | 300 |
      | additional drop    | 750 |
      | minimum charge     | 0   |
    And the card charges 4.00 per kg for any weight
    And an order "SO-1" for "CUST" from "ORIGIN"
    And order "SO-1" has line 1 of 1000 kg of GENERAL measuring 2.0 by 2.0 by 2.0 m for "CONS-A" at "DEST-A"
    And order "SO-1" is validated
    And consignments are generated for order "SO-1"
    And a load "LOAD-1" is opened at "ORIGIN" on lorry "MH-01-TL-0001"
    And the consignment for "CONS-A" at "DEST-A" is assigned to load "LOAD-1"
    And load "LOAD-1" is planned
    And a routing guide from "ORIGIN" to "DEST-A" for "RIGID"
    And the guide ranks "VENDOR-2" first with a 30 minute SLA
    And load "LOAD-1" is allocated
    And "VENDOR-2" accepts the offer
    And a trip "TRIP-1" is raised against load "LOAD-1"
    And trip "TRIP-1" is assigned lorry "MH-01-TL-0001" and driver "Ravi"
    And trip "TRIP-1" gates in at 08:00
    And trip "TRIP-1" is weighed 6000 kg empty
    And trip "TRIP-1" is weighed 7000 kg laden
    And trip "TRIP-1" is marked loaded
    And trip "TRIP-1" carries a "CONSIGNMENT_NOTE" numbered "LR-0001"
    And trip "TRIP-1" carries an "EWAY_BILL" numbered "EW-0001"
    And trip "TRIP-1" is dispatched at 11:30
    # The same three coordinates. A trip cannot jump from DISPATCHED to
    # COMPLETED, and the state machine refusing that is how this scenario
    # discovered it was skipping the road.
    And a position for "MH-01-TL-0001" at 19.1000,72.9000 4 hours ago
    And a position for "MH-01-TL-0001" at 19.2000,73.0000 3 hours ago
    And a position for "MH-01-TL-0001" at 19.3000,73.1000 2 hours ago
    And the positions are ingested
    And trip "TRIP-1" is completed

    When "VENDOR-2" invoices 7500 against "FB-TRIP-1"
    Then bill "FB-TRIP-1" is "DISPUTED"
    And bill "FB-TRIP-1" records a variance of 650
    And the audit log records "INVOICE_DISPUTED" with before and after snapshots
