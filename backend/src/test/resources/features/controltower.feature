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

Feature: Something went wrong on the road, and somebody owns it until it is closed
  Vision document 3.8.

  An exception is a ticket about one trip. It is raised by a person or by a
  detector, walks RAISED, ASSIGNED, UNDER_INVESTIGATION, RESOLVED, CLOSED, and
  carries two guarantees: where the vehicle was is captured at the moment of
  raising, and a critical ticket -- P1 or P2 -- freezes settlement of the trip
  until a manager closes it. Closing is what releases a vendor's money, so the
  manager who closes a critical ticket cannot be the person who resolved it.

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

  # ------------------------------------------------------------ severity

  Scenario: An accident is critical and freezes settlement
    Given a dispatched trip "TRIP-T1" on lorry "MH-01-TL-0001" from "ORIGIN" to "DEST-A"
    When "ACCIDENT" is reported on trip "TRIP-T1" as ticket "X1"
    Then ticket "X1" is "RAISED" with severity "P1"
    And ticket "X1" suspends settlement

  Scenario: A late arrival is worth a ticket but not worth holding up money
    Given a dispatched trip "TRIP-T1" on lorry "MH-01-TL-0001" from "ORIGIN" to "DEST-A"
    When "ETA_DELAY" is reported on trip "TRIP-T1" as ticket "X1"
    Then ticket "X1" is "RAISED" with severity "P3"
    And ticket "X1" does not suspend settlement

  # ------------------------------------------------- position at raising

  Scenario: The vehicle's own recent fix is where it happened
    Given a dispatched trip "TRIP-T1" on lorry "MH-01-TL-0001" from "ORIGIN" to "DEST-A"
    And a position for "MH-01-TL-0001" at 19.1000,72.9000 0 hours ago
    And the positions are ingested
    When "BREAKDOWN" is reported on trip "TRIP-T1" as ticket "X1" at 19.5000,73.5000
    Then ticket "X1" was located at 19.1000,72.9000 from "TELEMATICS"

  Scenario: A stale fix loses to where the reporter says they are
    # Three hours old is not where the lorry is now. The driver standing next
    # to it is the better answer.
    Given a dispatched trip "TRIP-T1" on lorry "MH-01-TL-0001" from "ORIGIN" to "DEST-A"
    And a position for "MH-01-TL-0001" at 19.1000,72.9000 3 hours ago
    And the positions are ingested
    When "BREAKDOWN" is reported on trip "TRIP-T1" as ticket "X1" at 19.5000,73.5000
    Then ticket "X1" was located at 19.5000,73.5000 from "REPORTED"

  Scenario: A stale fix is still kept when it is all there is, and says how old it is
    Given a dispatched trip "TRIP-T1" on lorry "MH-01-TL-0001" from "ORIGIN" to "DEST-A"
    And a position for "MH-01-TL-0001" at 19.1000,72.9000 3 hours ago
    And the positions are ingested
    When "THEFT" is reported on trip "TRIP-T1" as ticket "X1"
    Then ticket "X1" was located at 19.1000,72.9000 from "TELEMATICS"
    And the position on ticket "X1" is about 3 hours old

  Scenario: With nothing to go on, the ticket says so rather than inventing a place
    Given a dispatched trip "TRIP-T1" on lorry "MH-01-TL-0001" from "ORIGIN" to "DEST-A"
    When "THEFT" is reported on trip "TRIP-T1" as ticket "X1"
    Then ticket "X1" has no position

  Scenario: Half a coordinate is refused
    # It would plot somewhere plausible and wrong.
    Given a dispatched trip "TRIP-T1" on lorry "MH-01-TL-0001" from "ORIGIN" to "DEST-A"
    When "ACCIDENT" is reported on trip "TRIP-T1" with only a latitude
    Then the control tower refuses mentioning "both a latitude and a longitude"

  # ------------------------------------------------------------ lifecycle

  Scenario: A ticket walks the whole lifecycle
    Given a dispatched trip "TRIP-T1" on lorry "MH-01-TL-0001" from "ORIGIN" to "DEST-A"
    And "ACCIDENT" is reported on trip "TRIP-T1" as ticket "X1"
    When ticket "X1" is assigned to "desk@acme.test"
    And ticket "X1" is taken under investigation
    And ticket "X1" is resolved by "asha" because "Driver unhurt; load transshipped to MH-01-TL-0002"
    And ticket "X1" is closed by "vikram"
    Then ticket "X1" is "CLOSED" with severity "P1"
    And ticket "X1" was closed by "vikram"
    And ticket "X1" has 5 history entries

  Scenario: Nobody investigates a ticket nobody owns
    Given a dispatched trip "TRIP-T1" on lorry "MH-01-TL-0001" from "ORIGIN" to "DEST-A"
    And "ACCIDENT" is reported on trip "TRIP-T1" as ticket "X1"
    When ticket "X1" is taken under investigation
    Then the control tower refuses mentioning "cannot move from RAISED to UNDER_INVESTIGATION"

  Scenario: A resolution has to say how
    Given a dispatched trip "TRIP-T1" on lorry "MH-01-TL-0001" from "ORIGIN" to "DEST-A"
    And "ACCIDENT" is reported on trip "TRIP-T1" as ticket "X1"
    And ticket "X1" is assigned to "desk@acme.test"
    And ticket "X1" is taken under investigation
    When ticket "X1" is resolved by "asha" because ""
    Then the control tower refuses mentioning "account of how it was resolved"

  Scenario: Whoever resolved a critical ticket cannot also close it
    Given a dispatched trip "TRIP-T1" on lorry "MH-01-TL-0001" from "ORIGIN" to "DEST-A"
    And "ACCIDENT" is reported on trip "TRIP-T1" as ticket "X1"
    And ticket "X1" is assigned to "desk@acme.test"
    And ticket "X1" is taken under investigation
    And ticket "X1" is resolved by "asha" because "Load recovered intact"
    When ticket "X1" is closed by "asha"
    Then the control tower refuses mentioning "a different manager has to close it"
    And ticket "X1" is "RESOLVED" with severity "P1"

  Scenario: A ticket holding up no money may be closed by whoever resolved it
    Given a dispatched trip "TRIP-T1" on lorry "MH-01-TL-0001" from "ORIGIN" to "DEST-A"
    And "DOCUMENT_MISMATCH" is reported on trip "TRIP-T1" as ticket "X1"
    And ticket "X1" is assigned to "desk@acme.test"
    And ticket "X1" is taken under investigation
    And ticket "X1" is resolved by "asha" because "Corrected e-way bill uploaded"
    When ticket "X1" is closed by "asha"
    Then ticket "X1" is "CLOSED" with severity "P3"

  Scenario: A manager who does not accept the resolution sends it back
    Given a dispatched trip "TRIP-T1" on lorry "MH-01-TL-0001" from "ORIGIN" to "DEST-A"
    And "THEFT" is reported on trip "TRIP-T1" as ticket "X1"
    And ticket "X1" is assigned to "desk@acme.test"
    And ticket "X1" is taken under investigation
    And ticket "X1" is resolved by "asha" because "Probably fine"
    When ticket "X1" is sent back by "vikram" because "No police report attached"
    Then ticket "X1" is "UNDER_INVESTIGATION" with severity "P1"

  Scenario: Closing needs the closing permission
    Given a dispatched trip "TRIP-T1" on lorry "MH-01-TL-0001" from "ORIGIN" to "DEST-A"
    And "ETA_DELAY" is reported on trip "TRIP-T1" as ticket "X1"
    And ticket "X1" is assigned to "desk@acme.test"
    And ticket "X1" is taken under investigation
    And ticket "X1" is resolved by "asha" because "Arrived"
    And the signed-in user lacks "EXCEPTION_CLOSE"
    When ticket "X1" is closed
    Then the control tower refuses it as unauthorised

  # ------------------------------------------------------------ detectors

  Scenario: Straying from the corridor raises a critical ticket with nobody pressing anything
    Given a dispatched trip "TRIP-T1" on lorry "MH-01-TL-0001" from "ORIGIN" to "DEST-A"
    And a position for "MH-01-TL-0001" at 19.1000,72.9000 6 hours ago
    And a position for "MH-01-TL-0001" at 19.1000,74.5000 4 hours ago
    And a position for "MH-01-TL-0001" at 19.1000,74.6000 2 hours ago
    When the positions are ingested
    Then trip "TRIP-T1" has 1 exception ticket
    And the ticket on trip "TRIP-T1" is a "ROUTE_DEVIATION" of severity "P2"
    And the ticket on trip "TRIP-T1" was located at 19.1000,74.5000 from "DETECTOR"

  Scenario: A detection delivered twice is one ticket
    # The outbox delivers at least once. Two tickets for one excursion would
    # put two holds on the money and split the investigation in half.
    Given a dispatched trip "TRIP-T1" on lorry "MH-01-TL-0001" from "ORIGIN" to "DEST-A"
    And a position for "MH-01-TL-0001" at 19.1000,72.9000 6 hours ago
    And a position for "MH-01-TL-0001" at 19.1000,74.5000 4 hours ago
    And the positions are ingested
    When the route deviation on trip "TRIP-T1" is delivered again
    Then trip "TRIP-T1" has 1 exception ticket

  # ----------------------------------------------------------- settlement

  Scenario: A critical ticket freezes the bill until a manager closes it
    Given a rate card "WEST-2026" from "ORIGIN" to "DEST-A" for "VENDOR-1" effective 30 days ago:
      | free hours         | 2 |
      | detention per hour | 0 |
      | additional drop    | 0 |
      | minimum charge     | 0 |
    And the card charges 4.00 per kg for any weight
    And a trip "T1" carrying 5000 kg for "VENDOR-1"
    And trip "T1" is run, dispatched 2 days ago after 30 minutes at the origin
    And "THEFT" is reported on trip "T1" as ticket "X1"
    And trip "T1" is completed
    When "VENDOR-1" invoices 20000 against "FB-T1"
    Then matching is refused mentioning "cannot be settled while exception"
    And bill "FB-T1" is "DRAFT"
    And bill "FB-T1" is held by ticket "X1"
    When ticket "X1" is assigned to "desk@acme.test"
    And ticket "X1" is taken under investigation
    And ticket "X1" is resolved by "asha" because "Seal intact at destination; false alarm"
    And ticket "X1" is closed by "vikram"
    And "VENDOR-1" invoices 20000 against "FB-T1"
    Then bill "FB-T1" is "APPROVED_FOR_PAYMENT"
    And bill "FB-T1" is not held

  Scenario: Two critical tickets are two holds
    Given a rate card "WEST-2026" from "ORIGIN" to "DEST-A" for "VENDOR-1" effective 30 days ago:
      | free hours         | 2 |
      | detention per hour | 0 |
      | additional drop    | 0 |
      | minimum charge     | 0 |
    And the card charges 4.00 per kg for any weight
    And a trip "T1" carrying 5000 kg for "VENDOR-1"
    And trip "T1" is run, dispatched 2 days ago after 30 minutes at the origin
    And "THEFT" is reported on trip "T1" as ticket "X1"
    And "ACCIDENT" is reported on trip "T1" as ticket "X2"
    And trip "T1" is completed
    When ticket "X1" is assigned to "desk@acme.test"
    And ticket "X1" is taken under investigation
    And ticket "X1" is resolved by "asha" because "Recovered"
    And ticket "X1" is closed by "vikram"
    Then bill "FB-T1" is held by ticket "X2"

  Scenario: A minor ticket does not touch the money
    Given a rate card "WEST-2026" from "ORIGIN" to "DEST-A" for "VENDOR-1" effective 30 days ago:
      | free hours         | 2 |
      | detention per hour | 0 |
      | additional drop    | 0 |
      | minimum charge     | 0 |
    And the card charges 4.00 per kg for any weight
    And a trip "T1" carrying 5000 kg for "VENDOR-1"
    And trip "T1" is run, dispatched 2 days ago after 30 minutes at the origin
    And "DOCUMENT_MISMATCH" is reported on trip "T1" as ticket "X1"
    And trip "T1" is completed
    When "VENDOR-1" invoices 20000 against "FB-T1"
    Then bill "FB-T1" is "APPROVED_FOR_PAYMENT"

  Scenario: A release that overtakes its placement still leaves the money free
    # After a restart the outbox republishes in no promised order. A hold that
    # arrived after its own release must not freeze a trip forever.
    Given a dispatched trip "TRIP-T1" on lorry "MH-01-TL-0001" from "ORIGIN" to "DEST-A"
    When finance hears that a hold on trip "TRIP-T1" was released before hearing it was placed
    Then trip "TRIP-T1" has no settlement hold

  # --------------------------------------------------------------- board

  Scenario: The board counts what is open and what is holding money
    Given a dispatched trip "TRIP-T1" on lorry "MH-01-TL-0001" from "ORIGIN" to "DEST-A"
    And "ACCIDENT" is reported on trip "TRIP-T1" as ticket "X1"
    And "ROUTE_DEVIATION" is reported on trip "TRIP-T1" as ticket "X2"
    And "ETA_DELAY" is reported on trip "TRIP-T1" as ticket "X3"
    And ticket "X3" is assigned to "desk@acme.test"
    Then the board shows 1 P1, 1 P2 and 1 P3 open, 2 unassigned and 2 holding settlement
    And the board lists 3 tickets, newest first
