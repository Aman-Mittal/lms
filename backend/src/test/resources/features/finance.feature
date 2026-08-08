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

Feature: A movement becomes money
  Vision document 3.9.

  The context where being wrong costs on the day it happens. Two rules carry
  everything else. A trip is priced against the rate card in force on the day
  it was *dispatched*, so a bill raised in March for a January movement is
  argued from January's card. And a card that has priced a movement is never
  edited -- a correction is a new version, because the alternative silently
  reprices settled invoices with no diff and nothing to notice.

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

  # --------------------------------------------------------------- 3.9.1 rating

  Scenario: Linehaul comes from the band the chargeable weight falls in
    Given a trip "T1" carrying 5000 kg for "VENDOR-1"
    When trip "T1" is run, dispatched 2 days ago after 30 minutes at the origin
    And trip "T1" is completed
    Then bill "FB-T1" is "DRAFT"
    And bill "FB-T1" totals 20000
    And bill "FB-T1" has a "BASE_FREIGHT" line of 20000
    And bill "FB-T1" adds up

  Scenario: Waiting inside the free allowance costs nothing
    # And costs no *line* either. A detention line of zero on every bill would
    # train whoever reviews them to stop reading the detention line.
    Given a trip "T1" carrying 5000 kg for "VENDOR-1"
    When trip "T1" is run, dispatched 2 days ago after 90 minutes at the origin
    And trip "T1" is completed
    Then bill "FB-T1" totals 20000
    And bill "FB-T1" has no "DETENTION" line

  Scenario: Detention is charged on the dwell beyond the allowance, not on all of it
    # Three and a half hours at the gate, two of them free: ninety minutes at
    # 300 an hour is 450, not 1050.
    Given a trip "T1" carrying 5000 kg for "VENDOR-1"
    When trip "T1" is run, dispatched 2 days ago after 210 minutes at the origin
    And trip "T1" is completed
    Then bill "FB-T1" has a "DETENTION" line of 450
    And bill "FB-T1" totals 20450
    And bill "FB-T1" adds up

  Scenario: Every drop after the first carries a fee, and the first does not
    # The first drop is the journey, which the linehaul already prices. Charging
    # for it would bill the same movement twice.
    Given a depot "DEST-B" at 19.400,73.200
    And a trip "T2" carrying 5000 kg for "VENDOR-1" dropping at "DEST-B" and "DEST-A"
    When trip "T2" is run, dispatched 2 days ago after 30 minutes at the origin
    And trip "T2" is completed
    Then bill "FB-T2" has an "ADDITIONAL_DROP" line of 750
    And bill "FB-T2" adds up

  Scenario: The fuel surcharge indexes the linehaul, not the waiting
    # Detention is a lorry standing still; it burns no diesel. Applying the
    # surcharge to the whole bill would inflate it every time a vendor was kept
    # waiting, which is the opposite of the incentive anybody wants.
    Given the fuel surcharge 2 days ago was 10.0 percent
    And a trip "T1" carrying 5000 kg for "VENDOR-1"
    When trip "T1" is run, dispatched 2 days ago after 210 minutes at the origin
    And trip "T1" is completed
    Then bill "FB-T1" has a "FUEL_SURCHARGE" line of 2000
    And bill "FB-T1" totals 22450
    And bill "FB-T1" adds up

  Scenario: A light load is billed the lane floor, and the uplift is shown
    # 300 kg still costs a full lorry to move. The uplift is its own line so
    # that a vendor can see why a small movement cost what it did, and so that
    # a controller can see how often the floor is being hit.
    Given a depot "DEST-B" at 19.400,73.200
    And a rate card "SOUTH-2026" from "ORIGIN" to "DEST-B" for "VENDOR-1" effective 30 days ago:
      | free hours         | 2    |
      | detention per hour | 300  |
      | additional drop    | 0    |
      | minimum charge     | 8000 |
    And the card charges 4.00 per kg for any weight
    And a trip "T3" carrying 300 kg for "VENDOR-1" dropping at "DEST-B"
    When trip "T3" is run, dispatched 2 days ago after 30 minutes at the origin
    And trip "T3" is completed
    Then bill "FB-T3" has a "BASE_FREIGHT" line of 1200
    And bill "FB-T3" has a "MINIMUM_CHARGE_UPLIFT" line of 6800
    And bill "FB-T3" totals 8000
    And bill "FB-T3" adds up

  # ------------------------------------------------------- 3.9.1 immutability

  Scenario: A rate published today does not reprice a movement made last week
    # The rule the whole context is built around. The trip left on the old card
    # and is billed on the old card, even though the bill is raised after the
    # new one took effect.
    Given a trip "T1" carrying 5000 kg for "VENDOR-1"
    And trip "T1" is run, dispatched 10 days ago after 30 minutes at the origin
    When a rate card "WEST-2026-B" from "ORIGIN" to "DEST-A" for "VENDOR-1" effective 5 days ago:
      | free hours         | 2   |
      | detention per hour | 300 |
      | additional drop    | 750 |
      | minimum charge     | 0   |
    And the card charges 9.00 per kg for any weight
    And trip "T1" is completed
    Then bill "FB-T1" totals 20000
    And bill "FB-T1" was priced under "WEST-2026"

  Scenario: Publishing a successor closes the version it replaces
    When a rate card "WEST-2026-B" from "ORIGIN" to "DEST-A" for "VENDOR-1" effective 5 days ago:
      | free hours         | 2   |
      | detention per hour | 300 |
      | additional drop    | 750 |
      | minimum charge     | 0   |
    Then rate card "WEST-2026" was closed 6 days ago
    And rate card "WEST-2026-B" is the version in force

  Scenario: A successor cannot start before the card it replaces
    # Backdating under a live card would put two rates in force on the same
    # days, and pricing would then depend on which row the query reached first.
    When a rate card "WEST-2026-B" from "ORIGIN" to "DEST-A" for "VENDOR-1" effective 40 days ago:
      | free hours         | 2   |
      | detention per hour | 300 |
      | additional drop    | 750 |
      | minimum charge     | 0   |
    Then publishing the card is refused mentioning "cannot start on"

  Scenario: The database refuses to edit a published rate card
    # Not the service -- the table. A rule enforced only in application code is
    # a rule that a migration, a console session or a future endpoint can walk
    # straight past.
    When the minimum charge on "WEST-2026" is edited directly
    Then the edit is refused mentioning "publish a new version"

  Scenario: Two weight bands cannot both claim the same weight
    When the card charges 6.00 per kg from 3000 kg to 8000 kg
    Then adding the band is refused mentioning "already covers part of"

  # --------------------------------------------------- 3.9.2 tolerance matching

  Scenario: An invoice within tolerance is approved for payment
    Given a trip "T1" carrying 5000 kg for "VENDOR-1"
    And trip "T1" is run, dispatched 2 days ago after 30 minutes at the origin
    And trip "T1" is completed
    When "VENDOR-1" invoices 20100 against "FB-T1"
    Then bill "FB-T1" is "APPROVED_FOR_PAYMENT"

  Scenario: An invoice beyond tolerance is disputed rather than paid
    Given a trip "T1" carrying 5000 kg for "VENDOR-1"
    And trip "T1" is run, dispatched 2 days ago after 30 minutes at the origin
    And trip "T1" is completed
    When "VENDOR-1" invoices 21000 against "FB-T1"
    Then bill "FB-T1" is "DISPUTED"
    And bill "FB-T1" records a variance of 1000

  Scenario: An invoice for too little is disputed just as one for too much is
    # Not free money. It means the vendor's understanding of the rate and ours
    # have diverged, and next month the divergence points the other way.
    Given a trip "T1" carrying 5000 kg for "VENDOR-1"
    And trip "T1" is run, dispatched 2 days ago after 30 minutes at the origin
    And trip "T1" is completed
    When "VENDOR-1" invoices 18000 against "FB-T1"
    Then bill "FB-T1" is "DISPUTED"
    And bill "FB-T1" records a variance of -2000

  Scenario: A small bill is not disputed over a rounding difference
    # Two per cent of 2 000 is 40, and disputing an 80-rupee gap would cost more
    # to resolve than to pay. The absolute floor is what makes the percentage
    # usable at the small end.
    Given a depot "DEST-B" at 19.400,73.200
    And a rate card "SOUTH-2026" from "ORIGIN" to "DEST-B" for "VENDOR-1" effective 30 days ago:
      | free hours         | 2 |
      | detention per hour | 0 |
      | additional drop    | 0 |
      | minimum charge     | 0 |
    And the card charges 4.00 per kg for any weight
    And a trip "T3" carrying 500 kg for "VENDOR-1" dropping at "DEST-B"
    And trip "T3" is run, dispatched 2 days ago after 30 minutes at the origin
    And trip "T3" is completed
    When "VENDOR-1" invoices 2080 against "FB-T3"
    Then bill "FB-T3" is "APPROVED_FOR_PAYMENT"

  Scenario: The same small bill is disputed once the gap outgrows the floor
    Given a depot "DEST-B" at 19.400,73.200
    And a rate card "SOUTH-2026" from "ORIGIN" to "DEST-B" for "VENDOR-1" effective 30 days ago:
      | free hours         | 2 |
      | detention per hour | 0 |
      | additional drop    | 0 |
      | minimum charge     | 0 |
    And the card charges 4.00 per kg for any weight
    And a trip "T3" carrying 500 kg for "VENDOR-1" dropping at "DEST-B"
    And trip "T3" is run, dispatched 2 days ago after 30 minutes at the origin
    And trip "T3" is completed
    When "VENDOR-1" invoices 2200 against "FB-T3"
    Then bill "FB-T3" is "DISPUTED"

  Scenario: A dispute is settled on the record or not at all
    Given a trip "T1" carrying 5000 kg for "VENDOR-1"
    And trip "T1" is run, dispatched 2 days ago after 30 minutes at the origin
    And trip "T1" is completed
    And "VENDOR-1" invoices 21000 against "FB-T1"
    When the dispute on "FB-T1" is settled with no reason given
    Then settling is refused mentioning "requires a reason"
    When the dispute on "FB-T1" is settled because "toll revision agreed by email on the 14th"
    Then bill "FB-T1" is "APPROVED_FOR_PAYMENT"
    And bill "FB-T1" records the resolution "toll revision agreed"

  # ------------------------------------------------------------- edge cases

  Scenario: A lane with no rate card produces a bill that says so
    # Not silence. A trip with no bill looks exactly like a trip nobody has got
    # to yet; an UNPRICED bill appears on the same screen as everything else and
    # explains itself.
    Given a depot "DEST-B" at 19.400,73.200
    And a trip "T3" carrying 5000 kg for "VENDOR-1" dropping at "DEST-B"
    When trip "T3" is run, dispatched 2 days ago after 30 minutes at the origin
    And trip "T3" is completed
    Then bill "FB-T3" is "UNPRICED"
    And bill "FB-T3" explains itself mentioning "No rate card in force"

  Scenario: An unpriced bill cannot be matched against anything
    Given a depot "DEST-B" at 19.400,73.200
    And a trip "T3" carrying 5000 kg for "VENDOR-1" dropping at "DEST-B"
    And trip "T3" is run, dispatched 2 days ago after 30 minutes at the origin
    And trip "T3" is completed
    When "VENDOR-1" invoices 20000 against "FB-T3"
    Then matching is refused mentioning "no computed amount"

  Scenario: One trip yields one bill however many times the event arrives
    # The unique constraint on (tenant, trip) is the real guarantee -- two bills
    # for one movement means paying it twice -- and the guard is what turns that
    # constraint from an error into a no-op when an event is redelivered.
    Given a trip "T1" carrying 5000 kg for "VENDOR-1"
    And trip "T1" is run, dispatched 2 days ago after 30 minutes at the origin
    And trip "T1" is completed
    When the bill for trip "T1" is raised again
    Then trip "T1" has exactly 1 bill

  Scenario: Approving an invoice needs the permission to approve invoices
    Given a trip "T1" carrying 5000 kg for "VENDOR-1"
    And trip "T1" is run, dispatched 2 days ago after 30 minutes at the origin
    And trip "T1" is completed
    And the signed-in user lacks "INVOICE_APPROVE"
    When "VENDOR-1" invoices 20100 against "FB-T1"
    Then matching is refused as unauthorised

  Scenario: Publishing a rate card needs the permission to manage tariffs
    # Deliberately not INVOICE_APPROVE. Whoever decides what a lane costs should
    # not also certify that the vendor's bill matches it.
    Given the signed-in user lacks "TARIFF_MANAGE"
    When a rate card "WEST-2026-B" from "ORIGIN" to "DEST-A" for "VENDOR-1" effective 5 days ago:
      | free hours         | 2   |
      | detention per hour | 300 |
      | additional drop    | 750 |
      | minimum charge     | 0   |
    Then publishing the card is refused as unauthorised
