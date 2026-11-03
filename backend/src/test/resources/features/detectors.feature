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

@detectors
Feature: The control tower notices a late arrival and a spoiled cold chain
  Two detectors driven by what telematics reports, never by polling trips. A
  trip is watched once somebody records what it was promised: the arrival it
  committed to, and the temperature band its cargo must stay in. A projected
  arrival later than committed by more than the tenant's tolerance is an
  ETA_DELAY ticket; a reading outside the band for longer than the tenant's
  tolerance is a TEMPERATURE_EXCURSION ticket. Each is raised at most once per
  trip until it is closed, and a redelivered report adds nothing.

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
    And a dispatched trip "TRIP-D1" on lorry "MH-01-TL-0001" from "ORIGIN" to "DEST-A"
    And the tenant sets the tolerances to 30 minutes for an ETA slip and 15 minutes for a temperature excursion

  # ------------------------------------------------------------ ETA slip

  Scenario: An arrival projected well past the commitment raises one ticket
    Given trip "TRIP-D1" is committed to arrive in 10 minutes
    When the lorry "MH-01-TL-0001" reports a fix at 19.1000,72.9000 now
    Then trip "TRIP-D1" has 1 exception ticket
    And the ticket on trip "TRIP-D1" is a "ETA_DELAY" of severity "P3"
    And the ticket on trip "TRIP-D1" came from the "ETA_SLIP_DETECTED" detector

  Scenario: A slip inside the tolerance is not an exception
    Given trip "TRIP-D1" is committed to arrive in 30 minutes
    When the lorry "MH-01-TL-0001" reports a fix at 19.1000,72.9000 now
    Then trip "TRIP-D1" has 0 exception tickets

  Scenario: A trip on course to arrive early is not an exception
    Given trip "TRIP-D1" is committed to arrive in 180 minutes
    When the lorry "MH-01-TL-0001" reports a fix at 19.1000,72.9000 now
    Then trip "TRIP-D1" has 0 exception tickets

  Scenario: A trip nobody gave a commitment is not judged
    When the lorry "MH-01-TL-0001" reports a fix at 19.1000,72.9000 now
    Then trip "TRIP-D1" has 0 exception tickets

  Scenario: Further fixes of a late trip add nothing while its ticket is open
    Given trip "TRIP-D1" is committed to arrive in 10 minutes
    When the lorry "MH-01-TL-0001" reports a fix at 19.1000,72.9000 now
    And the lorry "MH-01-TL-0001" reports a fix at 19.1010,72.9010 now
    Then trip "TRIP-D1" has 1 exception ticket

  Scenario: A wider tenant tolerance lets the same slip pass
    Given the tenant sets the tolerances to 120 minutes for an ETA slip and 15 minutes for a temperature excursion
    And trip "TRIP-D1" is committed to arrive in 10 minutes
    When the lorry "MH-01-TL-0001" reports a fix at 19.1000,72.9000 now
    Then trip "TRIP-D1" has 0 exception tickets

  # --------------------------------------------------- temperature excursion

  Scenario: A reading out of band for longer than the tolerance raises one ticket
    Given trip "TRIP-D1" must stay between 2 and 8 C
    When the lorry "MH-01-TL-0001" reports a fix at 19.1000,72.9000 with cargo at 5 C 40 minutes ago
    And the lorry "MH-01-TL-0001" reports a fix at 19.1000,72.9000 with cargo at 12 C 30 minutes ago
    And the lorry "MH-01-TL-0001" reports a fix at 19.1000,72.9000 with cargo at 12.5 C 10 minutes ago
    Then trip "TRIP-D1" has 1 exception ticket
    And the ticket on trip "TRIP-D1" is a "TEMPERATURE_EXCURSION" of severity "P2"
    And the ticket on trip "TRIP-D1" came from the "TEMPERATURE_EXCURSION_DETECTED" detector
    And the ticket on trip "TRIP-D1" was located at 19.1000,72.9000 from "DETECTOR"
    And the ticket on trip "TRIP-D1" is known as ticket "X1"
    And ticket "X1" suspends settlement

  Scenario: An excursion shorter than the tolerance is not an exception
    Given trip "TRIP-D1" must stay between 2 and 8 C
    When the lorry "MH-01-TL-0001" reports a fix at 19.1000,72.9000 with cargo at 12 C 30 minutes ago
    And the lorry "MH-01-TL-0001" reports a fix at 19.1000,72.9000 with cargo at 12 C 20 minutes ago
    Then trip "TRIP-D1" has 0 exception tickets

  Scenario: A reading back inside the band starts the clock again
    Given trip "TRIP-D1" must stay between 2 and 8 C
    When the lorry "MH-01-TL-0001" reports a fix at 19.1000,72.9000 with cargo at 12 C 50 minutes ago
    And the lorry "MH-01-TL-0001" reports a fix at 19.1000,72.9000 with cargo at 6 C 40 minutes ago
    And the lorry "MH-01-TL-0001" reports a fix at 19.1000,72.9000 with cargo at 12 C 20 minutes ago
    And the lorry "MH-01-TL-0001" reports a fix at 19.1000,72.9000 with cargo at 12 C 10 minutes ago
    Then trip "TRIP-D1" has 0 exception tickets
    And trip "TRIP-D1" has been out of its band since 20 minutes ago

  Scenario: A reading inside the band clears the excursion
    Given trip "TRIP-D1" must stay between 2 and 8 C
    When the lorry "MH-01-TL-0001" reports a fix at 19.1000,72.9000 with cargo at 12 C 20 minutes ago
    And the lorry "MH-01-TL-0001" reports a fix at 19.1000,72.9000 with cargo at 6 C 10 minutes ago
    Then trip "TRIP-D1" is not out of its band

  Scenario: One excursion is one ticket however long it goes on
    Given trip "TRIP-D1" must stay between 2 and 8 C
    When the lorry "MH-01-TL-0001" reports a fix at 19.1000,72.9000 with cargo at 12 C 40 minutes ago
    And the lorry "MH-01-TL-0001" reports a fix at 19.1000,72.9000 with cargo at 12 C 20 minutes ago
    And the lorry "MH-01-TL-0001" reports a fix at 19.1000,72.9000 with cargo at 13 C 10 minutes ago
    And the lorry "MH-01-TL-0001" reports a fix at 19.1000,72.9000 with cargo at 13 C 2 minutes ago
    Then trip "TRIP-D1" has 1 exception ticket

  Scenario: A flush delivered twice is one ticket
    Given trip "TRIP-D1" must stay between 2 and 8 C
    And the lorry "MH-01-TL-0001" has buffered a reading of 12 C at 19.1000,72.9000 40 minutes ago
    And the lorry "MH-01-TL-0001" has buffered a reading of 12 C at 19.1000,72.9000 20 minutes ago
    And the lorry "MH-01-TL-0001" has buffered a reading of 13 C at 19.1000,72.9000 10 minutes ago
    When the buffered readings are flushed
    And the buffered readings are flushed again
    Then trip "TRIP-D1" has 1 exception ticket

  Scenario: A trip with no band is not judged on temperature
    Given trip "TRIP-D1" is committed to arrive in 600 minutes
    When the lorry "MH-01-TL-0001" reports a fix at 19.1000,72.9000 with cargo at 30 C 40 minutes ago
    And the lorry "MH-01-TL-0001" reports a fix at 19.1000,72.9000 with cargo at 30 C 10 minutes ago
    Then trip "TRIP-D1" has 0 exception tickets

  Scenario: A probe reporting an impossible temperature is refused
    Given trip "TRIP-D1" must stay between 2 and 8 C
    When the lorry "MH-01-TL-0001" reports a fix at 19.1000,72.9000 with cargo at 400 C 10 minutes ago
    Then the last report was rejected as an implausible temperature
    And trip "TRIP-D1" has 0 exception tickets

  # ------------------------------------------------------- terms and tolerances

  Scenario: The terms a trip is watched against can be read back
    Given trip "TRIP-D1" must stay between 2 and 8 C
    Then trip "TRIP-D1" is watched for a band of 2 to 8 C

  Scenario: The tolerances the tenant set are the ones in force
    Then the tolerances in force are 30 minutes for an ETA slip and 15 minutes for a temperature excursion

  Scenario: A band with only a minimum is refused
    When the desk sets only a minimum of 2 C on trip "TRIP-D1"
    Then the watch is refused mentioning "both a minimum and a maximum"

  Scenario: A band upside down is refused
    When the desk sets a band of 8 to 2 C on trip "TRIP-D1"
    Then the watch is refused mentioning "above the maximum"

  Scenario: A watch that watches nothing is refused
    When the desk sets an empty watch on trip "TRIP-D1"
    Then the watch is refused mentioning "committed arrival, a temperature band, or both"

  Scenario: A tolerance of nothing is refused
    When the desk sets the ETA tolerance to 0 minutes
    Then the watch is refused mentioning "between 1 minute"

  Scenario: Setting a watch needs the right to manage exceptions
    Given the signed-in user lacks "EXCEPTION_MANAGE"
    When the desk sets a band of 2 to 8 C on trip "TRIP-D1"
    Then the watch change is refused as unauthorised

  Scenario: Clearing a watch stops the detectors judging the trip
    Given trip "TRIP-D1" is committed to arrive in 10 minutes
    When the desk stops watching trip "TRIP-D1"
    And the lorry "MH-01-TL-0001" reports a fix at 19.1000,72.9000 now
    Then trip "TRIP-D1" has 0 exception tickets
