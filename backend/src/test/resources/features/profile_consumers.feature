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

Feature: The modules that read settings resolve them for the record in hand
  Tracking thresholds, the dispatch checklist and the invoice match tolerance
  are settings rather than deployment properties. With no profile, each is
  what the deployment always applied. A profile for an organisational unit, a
  lorry, a customer or a vendor changes it for exactly the trips and bills
  that concern that unit, lorry, customer or vendor -- and a dated checklist
  changes the gate on the day it takes effect, not before.

  Background:
    Given a tenant "acme" named "Acme Logistics"
    And tenant "acme" has an organisational unit "/acme/" named "Acme HQ" of type "HQ"
    And the tenant scope is "acme" at "/acme/"
    And a trading partner "CUST"
    And a trading partner "OTHER"
    And a trading partner "CONS-A"
    And a depot "ORIGIN" at 19.100,72.900
    And a depot "DEST-A" at 19.300,73.100
    And a carrier "VENDOR-1"
    And a lorry "MH-01-PC-0001" with payload 20000 kg and volume 60 m3
    And lorry "MH-01-PC-0001" has an "INSURANCE" certificate valid for 365 days
    And a lorry "MH-01-PC-0002" with payload 20000 kg and volume 60 m3
    And lorry "MH-01-PC-0002" has an "INSURANCE" certificate valid for 365 days
    And a lorry driver "Ravi" licensed for 365 days

  # ------------------------------------------------------- dispatch checklist

  Scenario: A customer's checklist demands a document the organisation's does not
    Given customer "CUST" has a profile setting "execution.dispatch.requiredDocuments" to "{\"CONSIGNMENT_NOTE\": true, \"EWAY_BILL\": true, \"COMMERCIAL_INVOICE\": true}"
    And an awarded load "LOAD-P1" of 5000 kg for customer "CUST" on lorry "MH-01-PC-0001"
    And an awarded load "LOAD-P2" of 5000 kg for customer "OTHER" on lorry "MH-01-PC-0002"
    And a trip "TRIP-P1" is raised against load "LOAD-P1"
    And trip "TRIP-P1" is assigned lorry "MH-01-PC-0001" and driver "Ravi"
    And trip "TRIP-P1" gates in at 08:00
    And trip "TRIP-P1" is weighed 6000 kg empty
    And trip "TRIP-P1" is weighed 11000 kg laden
    And trip "TRIP-P1" is marked loaded
    And trip "TRIP-P1" carries a "CONSIGNMENT_NOTE" numbered "LR-P1"
    And trip "TRIP-P1" carries an "EWAY_BILL" numbered "EW-P1"
    And a trip "TRIP-P2" is raised against load "LOAD-P2"
    And trip "TRIP-P2" is assigned lorry "MH-01-PC-0002" and driver "Ravi"
    And trip "TRIP-P2" gates in at 08:00
    And trip "TRIP-P2" is weighed 6000 kg empty
    And trip "TRIP-P2" is weighed 11000 kg laden
    And trip "TRIP-P2" is marked loaded
    And trip "TRIP-P2" carries a "CONSIGNMENT_NOTE" numbered "LR-P2"
    And trip "TRIP-P2" carries an "EWAY_BILL" numbered "EW-P2"
    When trip "TRIP-P1" is dispatched at 10:00
    Then dispatch is refused mentioning "no commercial invoice attached (required by customer profile"
    And trip "TRIP-P1" is "LOADED"
    When trip "TRIP-P2" is dispatched at 10:00
    Then trip "TRIP-P2" is "DISPATCHED"
    When trip "TRIP-P1" carries a "COMMERCIAL_INVOICE" numbered "INV-P1"
    And trip "TRIP-P1" is dispatched at 10:30
    Then trip "TRIP-P1" is "DISPATCHED"

  Scenario: A checklist that takes effect tomorrow does not hold back today's dispatch
    Given customer "CUST" has a profile setting "execution.dispatch.requiredDocuments" to "{\"CONSIGNMENT_NOTE\": true, \"EWAY_BILL\": true, \"COMMERCIAL_INVOICE\": true}" from tomorrow
    And an awarded load "LOAD-P3" of 5000 kg for customer "CUST" on lorry "MH-01-PC-0001"
    And a trip "TRIP-P3" is raised against load "LOAD-P3"
    And trip "TRIP-P3" is assigned lorry "MH-01-PC-0001" and driver "Ravi"
    And trip "TRIP-P3" gates in at 08:00
    And trip "TRIP-P3" is weighed 6000 kg empty
    And trip "TRIP-P3" is weighed 11000 kg laden
    And trip "TRIP-P3" is marked loaded
    And trip "TRIP-P3" carries a "CONSIGNMENT_NOTE" numbered "LR-P3"
    And trip "TRIP-P3" carries an "EWAY_BILL" numbered "EW-P3"
    When trip "TRIP-P3" is dispatched at 10:00
    Then trip "TRIP-P3" is "DISPATCHED"
    And the documents customer "CUST" must carry from tomorrow include "COMMERCIAL_INVOICE"

  Scenario: The system default names itself when it refuses
    Given an awarded load "LOAD-P4" of 5000 kg for customer "OTHER" on lorry "MH-01-PC-0001"
    And a trip "TRIP-P4" is raised against load "LOAD-P4"
    And trip "TRIP-P4" is assigned lorry "MH-01-PC-0001" and driver "Ravi"
    And trip "TRIP-P4" gates in at 08:00
    And trip "TRIP-P4" is weighed 6000 kg empty
    And trip "TRIP-P4" is weighed 11000 kg laden
    And trip "TRIP-P4" is marked loaded
    And trip "TRIP-P4" carries a "CONSIGNMENT_NOTE" numbered "LR-P4"
    When trip "TRIP-P4" is dispatched at 10:00
    Then dispatch is refused mentioning "no eway bill attached (required by the system default)"

  Scenario: A unit's tighter weight tolerance blocks a payload the default lets through
    Given org unit "/acme/" has a profile setting "execution.dispatch.weightTolerancePct" to "1"
    And an awarded load "LOAD-P5" of 5000 kg for customer "OTHER" on lorry "MH-01-PC-0001"
    And a trip "TRIP-P5" is raised against load "LOAD-P5"
    And trip "TRIP-P5" is assigned lorry "MH-01-PC-0001" and driver "Ravi"
    And trip "TRIP-P5" gates in at 08:00
    And trip "TRIP-P5" is weighed 6000 kg empty
    And trip "TRIP-P5" is weighed 11100 kg laden
    And trip "TRIP-P5" is marked loaded
    And trip "TRIP-P5" carries a "CONSIGNMENT_NOTE" numbered "LR-P5"
    And trip "TRIP-P5" carries an "EWAY_BILL" numbered "EW-P5"
    When trip "TRIP-P5" is dispatched at 10:00
    Then dispatch is refused mentioning "beyond the 1% tolerance (set by org unit profile"

  Scenario: A document type nobody attaches to a trip cannot be made a condition of leaving
    Then a profile for customer "CUST" setting "execution.dispatch.requiredDocuments" to "{\"BOARDING_PASS\": true}" is refused with "setting-invalid-value"

  # ------------------------------------------------------------- tracking

  Scenario: A lorry's tighter stoppage threshold raises a ticket the default lets pass
    Given lorry "MH-01-PC-0001" has a profile setting "telematics.stoppage.threshold" to "PT20M"
    And a dispatched trip "TRIP-S1" on lorry "MH-01-PC-0001" from "ORIGIN" to "DEST-A"
    And a position for "MH-01-PC-0001" at 19.1000,72.9000 4 hours ago
    And a position for "MH-01-PC-0001" at 19.2000,73.0000 90 minutes ago
    And a position for "MH-01-PC-0001" at 19.2000,73.0000 60 minutes ago
    When the positions are ingested
    Then trip "TRIP-S1" has 1 exception ticket
    And the ticket on trip "TRIP-S1" is a "EXTENDED_STOPPAGE" of severity "P2"

  Scenario: The same thirty-minute stop on a lorry with no profile is not an exception
    Given lorry "MH-01-PC-0002" has a profile setting "telematics.stoppage.threshold" to "PT20M"
    And a dispatched trip "TRIP-S2" on lorry "MH-01-PC-0001" from "ORIGIN" to "DEST-A"
    And a position for "MH-01-PC-0001" at 19.1000,72.9000 4 hours ago
    And a position for "MH-01-PC-0001" at 19.2000,73.0000 90 minutes ago
    And a position for "MH-01-PC-0001" at 19.2000,73.0000 60 minutes ago
    When the positions are ingested
    Then trip "TRIP-S2" has 0 exception tickets

  Scenario: A detour inside the default corridor is not a deviation
    Given a dispatched trip "TRIP-S3" on lorry "MH-01-PC-0001" from "ORIGIN" to "DEST-A"
    And a position for "MH-01-PC-0001" at 19.1000,72.9000 6 hours ago
    And a position for "MH-01-PC-0001" at 19.2000,73.0500 4 hours ago
    When the positions are ingested
    Then trip "TRIP-S3" has 0 open route deviations

  Scenario: The customer's narrow corridor makes the same detour a deviation
    Given customer "CUST" has a profile setting "telematics.routeDeviation.corridorMetres" to "1000"
    And a dispatched trip "TRIP-S4" on lorry "MH-01-PC-0001" from "ORIGIN" to "DEST-A"
    And a position for "MH-01-PC-0001" at 19.1000,72.9000 6 hours ago
    And a position for "MH-01-PC-0001" at 19.2000,73.0500 4 hours ago
    When the positions are ingested
    Then trip "TRIP-S4" has 1 open route deviation

  Scenario: A lorry that does not arrive by itself waits for a person at the destination
    Given lorry "MH-01-PC-0001" has a profile setting "execution.tracking.autoArrival" to "false"
    And a dispatched trip "TRIP-S5" on lorry "MH-01-PC-0001" from "ORIGIN" to "DEST-A"
    And a position for "MH-01-PC-0001" at 19.1000,72.9000 4 hours ago
    And a position for "MH-01-PC-0001" at 19.2000,73.0000 3 hours ago
    And a position for "MH-01-PC-0001" at 19.3000,73.1000 2 hours ago
    When the positions are ingested
    Then trip "TRIP-S5" is "IN_TRANSIT"
    And trip "TRIP-S5" crossed "DEST-A" as "ENTERED"

  Scenario: By default, leaving the destination does not complete the trip
    Given a dispatched trip "TRIP-S6" on lorry "MH-01-PC-0001" from "ORIGIN" to "DEST-A"
    And a position for "MH-01-PC-0001" at 19.1000,72.9000 4 hours ago
    And a position for "MH-01-PC-0001" at 19.2000,73.0000 3 hours ago
    And a position for "MH-01-PC-0001" at 19.3000,73.1000 2 hours ago
    And a position for "MH-01-PC-0001" at 19.4000,73.2000 1 hours ago
    When the positions are ingested
    Then trip "TRIP-S6" is "AT_DESTINATION"

  Scenario: A unit that completes on departure from the destination does so without a person
    Given org unit "/acme/" has a profile setting "execution.tracking.autoCompleteOnExit" to "true"
    And a dispatched trip "TRIP-S7" on lorry "MH-01-PC-0001" from "ORIGIN" to "DEST-A"
    And a position for "MH-01-PC-0001" at 19.1000,72.9000 4 hours ago
    And a position for "MH-01-PC-0001" at 19.2000,73.0000 3 hours ago
    And a position for "MH-01-PC-0001" at 19.3000,73.1000 2 hours ago
    And a position for "MH-01-PC-0001" at 19.4000,73.2000 1 hours ago
    When the positions are ingested
    Then trip "TRIP-S7" is "COMPLETED"

  Scenario: A customer's wider ETA tolerance lets a slip pass that the tenant's would not
    Given the tenant sets the tolerances to 30 minutes for an ETA slip and 15 minutes for a temperature excursion
    And customer "CUST" has a profile setting "controltower.detector.etaSlipTolerance" to "PT3H"
    And a dispatched trip "TRIP-S8" on lorry "MH-01-PC-0001" from "ORIGIN" to "DEST-A"
    And trip "TRIP-S8" is committed to arrive in 10 minutes
    When the lorry "MH-01-PC-0001" reports a fix at 19.1000,72.9000 now
    Then trip "TRIP-S8" has 0 exception tickets

  Scenario: Another customer's tolerance does not reach this customer's trip
    Given the tenant sets the tolerances to 30 minutes for an ETA slip and 15 minutes for a temperature excursion
    And customer "OTHER" has a profile setting "controltower.detector.etaSlipTolerance" to "PT3H"
    And a dispatched trip "TRIP-S9" on lorry "MH-01-PC-0001" from "ORIGIN" to "DEST-A"
    And trip "TRIP-S9" is committed to arrive in 10 minutes
    When the lorry "MH-01-PC-0001" reports a fix at 19.1000,72.9000 now
    Then trip "TRIP-S9" has 1 exception ticket
