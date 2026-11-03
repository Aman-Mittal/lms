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

Feature: Getting the lorry out of the yard, and only when it should be
  Vision document 3.6.

  Dispatch is the one place in the platform where a rule stops being data and
  starts stopping a vehicle. Everything upstream can be corrected; after the
  gate opens, it cannot.

  These scenarios exercise the guards through the real command services and a
  real database, so the compliance port, the load's own state machine, and the
  unique constraints that make double-dispatch impossible are all in force.

  Background:
    Given a tenant "acme" named "Acme Logistics"
    And tenant "acme" has an organisational unit "/acme/" named "Acme HQ" of type "HQ"
    And the tenant scope is "acme" at "/acme/"
    And a trading partner "CUST"
    And a trading partner "CONS-A"
    And a depot "ORIGIN" at 19.10,72.90
    And a depot "DEST-A" at 19.30,73.10
    And a carrier "VENDOR-1"
    And a lorry "MH-01-EX-0001" with payload 10000 kg and volume 40 m3
    And lorry "MH-01-EX-0001" has an "INSURANCE" certificate valid for 365 days
    And lorry "MH-01-EX-0001" has a "FITNESS" certificate valid for 365 days
    And a lorry driver "Ravi" licensed for 365 days

  Scenario: A trip runs from award to proof of delivery
    Given an awarded load "LOAD-E1" of 5000 kg from "ORIGIN" to "CONS-A" at "DEST-A" on lorry "MH-01-EX-0001" for "VENDOR-1"
    And a trip "TRIP-1" is raised against load "LOAD-E1"
    And trip "TRIP-1" is assigned lorry "MH-01-EX-0001" and driver "Ravi"
    And trip "TRIP-1" gates in at 08:00
    And trip "TRIP-1" is weighed 6000 kg empty
    And trip "TRIP-1" is weighed 11000 kg laden
    And trip "TRIP-1" is marked loaded
    And trip "TRIP-1" carries a "CONSIGNMENT_NOTE" numbered "LR-0001"
    And trip "TRIP-1" carries an "EWAY_BILL" numbered "EW-0001"
    When trip "TRIP-1" is dispatched at 10:00
    Then trip "TRIP-1" is "DISPATCHED"
    And load "LOAD-E1" is "DISPATCHED"
    And trip "TRIP-1" has an origin dwell of 120 minutes
    And trip "TRIP-1" has a payload of 5000 kg

  Scenario: Dispatch is blocked without the statutory paperwork
    Given an awarded load "LOAD-E2" of 5000 kg from "ORIGIN" to "CONS-A" at "DEST-A" on lorry "MH-01-EX-0001" for "VENDOR-1"
    And a trip "TRIP-2" is raised against load "LOAD-E2"
    And trip "TRIP-2" is assigned lorry "MH-01-EX-0001" and driver "Ravi"
    And trip "TRIP-2" gates in at 08:00
    And trip "TRIP-2" is weighed 6000 kg empty
    And trip "TRIP-2" is weighed 11000 kg laden
    And trip "TRIP-2" is marked loaded
    When trip "TRIP-2" is dispatched at 10:00
    Then dispatch is refused mentioning "consignment note"
    And dispatch is refused mentioning "eway bill"
    And trip "TRIP-2" is "LOADED"

  Scenario: Attaching the paperwork unblocks the dispatch
    Given an awarded load "LOAD-E3" of 5000 kg from "ORIGIN" to "CONS-A" at "DEST-A" on lorry "MH-01-EX-0001" for "VENDOR-1"
    And a trip "TRIP-3" is raised against load "LOAD-E3"
    And trip "TRIP-3" is assigned lorry "MH-01-EX-0001" and driver "Ravi"
    And trip "TRIP-3" gates in at 08:00
    And trip "TRIP-3" is weighed 6000 kg empty
    And trip "TRIP-3" is weighed 11000 kg laden
    And trip "TRIP-3" is marked loaded
    And trip "TRIP-3" is dispatched at 10:00
    And trip "TRIP-3" carries a "CONSIGNMENT_NOTE" numbered "LR-0003"
    And trip "TRIP-3" carries an "EWAY_BILL" numbered "EW-0003"
    When trip "TRIP-3" is dispatched at 10:30
    Then trip "TRIP-3" is "DISPATCHED"

  Scenario: A lorry whose certificate expires before departure cannot be assigned
    # Judged against the departure date, not today. A certificate valid this
    # morning and lapsing this evening is not valid for an evening departure,
    # and finding that out at the gate wastes a driver's day.
    Given a lorry "MH-01-EX-0002" with payload 10000 kg and volume 40 m3
    And lorry "MH-01-EX-0002" has an "INSURANCE" certificate valid for 2 days
    And an awarded load "LOAD-E4" of 5000 kg from "ORIGIN" to "CONS-A" at "DEST-A" on lorry "MH-01-EX-0002" for "VENDOR-1"
    And a trip "TRIP-4" is raised against load "LOAD-E4" departing in 30 days
    When trip "TRIP-4" is assigned lorry "MH-01-EX-0002" and driver "Ravi"
    Then assigning the lorry is refused mentioning "INSURANCE"

  Scenario: Dangerous goods need a certified lorry and an endorsed driver
    Given a hazmat-certified lorry "MH-01-EX-0003" with payload 10000 kg and volume 40 m3
    And lorry "MH-01-EX-0003" has an "INSURANCE" certificate valid for 365 days
    And an awarded hazmat load "LOAD-E5" of 5000 kg from "ORIGIN" to "CONS-A" at "DEST-A" on lorry "MH-01-EX-0003" for "VENDOR-1"
    And a trip "TRIP-5" is raised against load "LOAD-E5"
    And trip "TRIP-5" is assigned lorry "MH-01-EX-0003" and driver "Ravi"
    And trip "TRIP-5" gates in at 08:00
    And trip "TRIP-5" is weighed 6000 kg empty
    And trip "TRIP-5" is weighed 11000 kg laden
    And trip "TRIP-5" is marked loaded
    And trip "TRIP-5" carries a "CONSIGNMENT_NOTE" numbered "LR-0005"
    And trip "TRIP-5" carries an "EWAY_BILL" numbered "EW-0005"
    When trip "TRIP-5" is dispatched at 10:00
    Then dispatch is refused mentioning "not endorsed"

  Scenario: A weighed payload far off the manifest blocks the dispatch
    # Freight that was not loaded, freight that was not on the manifest, or a
    # scale that needs calibrating. All three matter and all three are
    # invisible without the comparison.
    Given an awarded load "LOAD-E6" of 5000 kg from "ORIGIN" to "CONS-A" at "DEST-A" on lorry "MH-01-EX-0001" for "VENDOR-1"
    And a trip "TRIP-6" is raised against load "LOAD-E6"
    And trip "TRIP-6" is assigned lorry "MH-01-EX-0001" and driver "Ravi"
    And trip "TRIP-6" gates in at 08:00
    And trip "TRIP-6" is weighed 6000 kg empty
    And trip "TRIP-6" is weighed 14000 kg laden
    And trip "TRIP-6" is marked loaded
    And trip "TRIP-6" carries a "CONSIGNMENT_NOTE" numbered "LR-0006"
    And trip "TRIP-6" carries an "EWAY_BILL" numbered "EW-0006"
    When trip "TRIP-6" is dispatched at 10:00
    Then dispatch is refused mentioning "beyond the"

  Scenario: A small weighing difference is within tolerance
    # Weighbridges have a tolerance and pallets absorb moisture. A system that
    # refused every load half a percent off its manifest would be switched off
    # within a week.
    Given an awarded load "LOAD-E7" of 5000 kg from "ORIGIN" to "CONS-A" at "DEST-A" on lorry "MH-01-EX-0001" for "VENDOR-1"
    And a trip "TRIP-7" is raised against load "LOAD-E7"
    And trip "TRIP-7" is assigned lorry "MH-01-EX-0001" and driver "Ravi"
    And trip "TRIP-7" gates in at 08:00
    And trip "TRIP-7" is weighed 6000 kg empty
    And trip "TRIP-7" is weighed 11100 kg laden
    And trip "TRIP-7" is marked loaded
    And trip "TRIP-7" carries a "CONSIGNMENT_NOTE" numbered "LR-0007"
    And trip "TRIP-7" carries an "EWAY_BILL" numbered "EW-0007"
    When trip "TRIP-7" is dispatched at 10:00
    Then trip "TRIP-7" is "DISPATCHED"

  Scenario: A gross weight taken before the tare is refused
    Given an awarded load "LOAD-E8" of 5000 kg from "ORIGIN" to "CONS-A" at "DEST-A" on lorry "MH-01-EX-0001" for "VENDOR-1"
    And a trip "TRIP-8" is raised against load "LOAD-E8"
    And trip "TRIP-8" is assigned lorry "MH-01-EX-0001" and driver "Ravi"
    And trip "TRIP-8" gates in at 08:00
    When trip "TRIP-8" is weighed 11000 kg laden
    Then weighing is refused mentioning "no tare reading"

  Scenario: A trip cannot be marked loaded before it has been weighed
    Given an awarded load "LOAD-E9" of 5000 kg from "ORIGIN" to "CONS-A" at "DEST-A" on lorry "MH-01-EX-0001" for "VENDOR-1"
    And a trip "TRIP-9" is raised against load "LOAD-E9"
    And trip "TRIP-9" is assigned lorry "MH-01-EX-0001" and driver "Ravi"
    And trip "TRIP-9" gates in at 08:00
    And trip "TRIP-9" is weighed 6000 kg empty
    When trip "TRIP-9" is marked loaded
    Then loading is refused mentioning "both a tare and a gross"

  Scenario: A load may only have one trip
    Given an awarded load "LOAD-E10" of 5000 kg from "ORIGIN" to "CONS-A" at "DEST-A" on lorry "MH-01-EX-0001" for "VENDOR-1"
    And a trip "TRIP-10" is raised against load "LOAD-E10"
    When a trip "TRIP-10B" is raised against load "LOAD-E10"
    Then raising the trip is refused mentioning "already has trip"

  Scenario: A trip cannot be raised against a load nobody has taken
    Given a load "LOAD-E11" ready for sourcing from "ORIGIN" to "CONS-A" at "DEST-A" on lorry "MH-01-EX-0001"
    When a trip "TRIP-11" is raised against load "LOAD-E11"
    Then raising the trip is refused mentioning "only be raised against an awarded load"

  Scenario: Documents cannot be added after the lorry has left
    Given an awarded load "LOAD-E12" of 5000 kg from "ORIGIN" to "CONS-A" at "DEST-A" on lorry "MH-01-EX-0001" for "VENDOR-1"
    And a trip "TRIP-12" is raised against load "LOAD-E12"
    And trip "TRIP-12" is assigned lorry "MH-01-EX-0001" and driver "Ravi"
    And trip "TRIP-12" gates in at 08:00
    And trip "TRIP-12" is weighed 6000 kg empty
    And trip "TRIP-12" is weighed 11000 kg laden
    And trip "TRIP-12" is marked loaded
    And trip "TRIP-12" carries a "CONSIGNMENT_NOTE" numbered "LR-0012"
    And trip "TRIP-12" carries an "EWAY_BILL" numbered "EW-0012"
    And trip "TRIP-12" is dispatched at 10:00
    When trip "TRIP-12" carries a "POD" numbered "POD-0012"
    Then attaching the document is refused mentioning "already left"

  Scenario: The gate log records both crossings
    Given an awarded load "LOAD-E13" of 5000 kg from "ORIGIN" to "CONS-A" at "DEST-A" on lorry "MH-01-EX-0001" for "VENDOR-1"
    And a trip "TRIP-13" is raised against load "LOAD-E13"
    And trip "TRIP-13" is assigned lorry "MH-01-EX-0001" and driver "Ravi"
    And trip "TRIP-13" gates in at 08:00
    And trip "TRIP-13" is weighed 6000 kg empty
    And trip "TRIP-13" is weighed 11000 kg laden
    And trip "TRIP-13" is marked loaded
    And trip "TRIP-13" carries a "CONSIGNMENT_NOTE" numbered "LR-0013"
    And trip "TRIP-13" carries an "EWAY_BILL" numbered "EW-0013"
    When trip "TRIP-13" is dispatched at 10:00
    Then trip "TRIP-13" has 2 gate events

  Scenario: A trip is cancelled before dispatch and gives its load back
    # Cancelling stops the trip holding the load, not the vendor's award: the
    # load stays AWARDED and a replacement trip can be raised against it.
    Given an awarded load "LOAD-C1" of 5000 kg from "ORIGIN" to "CONS-A" at "DEST-A" on lorry "MH-01-EX-0001" for "VENDOR-1"
    And a trip "TRIP-C1" is raised against load "LOAD-C1"
    And trip "TRIP-C1" is assigned lorry "MH-01-EX-0001" and driver "Ravi"
    When trip "TRIP-C1" is cancelled because "driver fell ill"
    Then trip "TRIP-C1" is "CANCELLED"
    And trip "TRIP-C1" records the cancellation reason "driver fell ill"
    And load "LOAD-C1" is "AWARDED"
    And no freight bill exists for trip "TRIP-C1"
    When a trip "TRIP-C1B" is raised against load "LOAD-C1"
    Then trip "TRIP-C1B" is "PLANNED"
    And trip "TRIP-C1" is "CANCELLED"

  Scenario: A replacement trip is still held to one live trip per load
    Given an awarded load "LOAD-C2" of 5000 kg from "ORIGIN" to "CONS-A" at "DEST-A" on lorry "MH-01-EX-0001" for "VENDOR-1"
    And a trip "TRIP-C2" is raised against load "LOAD-C2"
    And trip "TRIP-C2" is cancelled because "re-planned"
    And a trip "TRIP-C2B" is raised against load "LOAD-C2"
    When a trip "TRIP-C2C" is raised against load "LOAD-C2"
    Then raising the trip is refused mentioning "already has trip"

  Scenario: A loaded trip can still be cancelled; a dispatched one cannot
    Given an awarded load "LOAD-C3" of 5000 kg from "ORIGIN" to "CONS-A" at "DEST-A" on lorry "MH-01-EX-0001" for "VENDOR-1"
    And a trip "TRIP-C3" is raised against load "LOAD-C3"
    And trip "TRIP-C3" is assigned lorry "MH-01-EX-0001" and driver "Ravi"
    And trip "TRIP-C3" gates in at 08:00
    And trip "TRIP-C3" is weighed 6000 kg empty
    And trip "TRIP-C3" is weighed 11000 kg laden
    And trip "TRIP-C3" is marked loaded
    And trip "TRIP-C3" carries a "CONSIGNMENT_NOTE" numbered "LR-C003"
    And trip "TRIP-C3" carries an "EWAY_BILL" numbered "EW-C003"
    And trip "TRIP-C3" is dispatched at 10:00
    When trip "TRIP-C3" is cancelled because "changed our minds"
    Then cancelling is refused mentioning "can no longer be cancelled"
    And trip "TRIP-C3" is "DISPATCHED"
    And load "LOAD-C3" is "DISPATCHED"

  Scenario: A trip that has been weighed and loaded is cancelled
    Given an awarded load "LOAD-C4" of 5000 kg from "ORIGIN" to "CONS-A" at "DEST-A" on lorry "MH-01-EX-0001" for "VENDOR-1"
    And a trip "TRIP-C4" is raised against load "LOAD-C4"
    And trip "TRIP-C4" is assigned lorry "MH-01-EX-0001" and driver "Ravi"
    And trip "TRIP-C4" gates in at 08:00
    And trip "TRIP-C4" is weighed 6000 kg empty
    And trip "TRIP-C4" is weighed 11000 kg laden
    And trip "TRIP-C4" is marked loaded
    When trip "TRIP-C4" is cancelled because "customer withdrew"
    Then trip "TRIP-C4" is "CANCELLED"

  Scenario: Cancelling twice changes nothing and is audited once
    Given an awarded load "LOAD-C5" of 5000 kg from "ORIGIN" to "CONS-A" at "DEST-A" on lorry "MH-01-EX-0001" for "VENDOR-1"
    And a trip "TRIP-C5" is raised against load "LOAD-C5"
    And trip "TRIP-C5" is cancelled because "driver fell ill"
    When trip "TRIP-C5" is cancelled because "second thoughts"
    Then trip "TRIP-C5" records the cancellation reason "driver fell ill"
    And the audit trail for trip "TRIP-C5" has 1 "TRIP_CANCELLED" entries
    And the "TRIP_CANCELLED" audit entry for trip "TRIP-C5" mentions "driver fell ill"

  Scenario: A cancellation needs a reason
    Given an awarded load "LOAD-C6" of 5000 kg from "ORIGIN" to "CONS-A" at "DEST-A" on lorry "MH-01-EX-0001" for "VENDOR-1"
    And a trip "TRIP-C6" is raised against load "LOAD-C6"
    When trip "TRIP-C6" is cancelled without a reason
    Then cancelling is refused as invalid
    And trip "TRIP-C6" is "PLANNED"

  Scenario: A cancelled trip cannot be revived
    Given an awarded load "LOAD-C7" of 5000 kg from "ORIGIN" to "CONS-A" at "DEST-A" on lorry "MH-01-EX-0001" for "VENDOR-1"
    And a trip "TRIP-C7" is raised against load "LOAD-C7"
    And trip "TRIP-C7" is cancelled because "re-planned"
    When trip "TRIP-C7" is assigned lorry "MH-01-EX-0001" and driver "Ravi"
    Then assigning the lorry is refused mentioning "cannot move from CANCELLED"

  Scenario: Every step of a trip's life leaves an audit entry
    Given an awarded load "LOAD-A1" of 5000 kg from "ORIGIN" to "CONS-A" at "DEST-A" on lorry "MH-01-EX-0001" for "VENDOR-1"
    And a trip "TRIP-A1" is raised against load "LOAD-A1"
    And trip "TRIP-A1" is assigned lorry "MH-01-EX-0001" and driver "Ravi"
    And trip "TRIP-A1" gates in at 08:00
    And trip "TRIP-A1" is weighed 6000 kg empty
    And trip "TRIP-A1" is weighed 11000 kg laden
    And trip "TRIP-A1" is marked loaded
    And trip "TRIP-A1" carries a "CONSIGNMENT_NOTE" numbered "LR-A001"
    And trip "TRIP-A1" carries an "EWAY_BILL" numbered "EW-A001"
    And trip "TRIP-A1" is dispatched at 10:00
    And trip "TRIP-A1" is moving
    And trip "TRIP-A1" arrives at 14:00
    When trip "TRIP-A1" is completed at 15:00
    Then the audit trail for trip "TRIP-A1" lists "TRIP_RAISED, TRIP_ASSIGNED, TRIP_GATE_IN, TRIP_WEIGHED, TRIP_WEIGHED, TRIP_LOADED, TRIP_DOCUMENT_ATTACHED, TRIP_DOCUMENT_ATTACHED, TRIP_DISPATCHED, TRIP_IN_TRANSIT, TRIP_ARRIVED, TRIP_COMPLETED"
    And the "TRIP_DISPATCHED" audit entry for trip "TRIP-A1" mentions "LOADED"

  Scenario: A refused dispatch leaves no dispatch entry
    # Entries are written with the change they describe, so a transition that
    # is refused is not in the trail; the trip stays where it was.
    Given an awarded load "LOAD-A2" of 5000 kg from "ORIGIN" to "CONS-A" at "DEST-A" on lorry "MH-01-EX-0001" for "VENDOR-1"
    And a trip "TRIP-A2" is raised against load "LOAD-A2"
    And trip "TRIP-A2" is assigned lorry "MH-01-EX-0001" and driver "Ravi"
    When trip "TRIP-A2" is dispatched at 10:00
    Then dispatch is refused mentioning "consignment note"
    And the audit trail for trip "TRIP-A2" lists "TRIP_RAISED, TRIP_ASSIGNED"

  Scenario: A terminal a trip starts from cannot be deleted
    Given an awarded load "LOAD-T1" of 5000 kg from "ORIGIN" to "CONS-A" at "DEST-A" on lorry "MH-01-EX-0001" for "VENDOR-1"
    And a trip "TRIP-T1" is raised against load "LOAD-T1"
    When terminal "ORIGIN" is deleted
    Then deleting the terminal is refused with "terminal-in-use" mentioning "trips"
    And terminal "ORIGIN" still exists

