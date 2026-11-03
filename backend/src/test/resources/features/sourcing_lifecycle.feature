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

@sourcing-lifecycle
Feature: Withdrawing sourcing work when a load's plan changes
  An award that is cancelled has to give the load back, and an order that is
  cancelled has to take its freight out of sourcing. Both used to leave the
  load AWARDED or open to vendors while nobody wanted it: the allocation was
  marked CANCELLED, the load stayed AWARDED, and offering it again was refused
  because it was no longer PLANNED.

  Decisions these scenarios pin down:
  - cancelling an award returns the load to PLANNED and clears its vendor, and
    the next offer is a new allocation (the cancelled one stays as history);
  - while a trip is raised against the load, the award cannot be cancelled and
    neither can an order whose freight is on that load (load-has-active-trip);
  - cancelling an order that empties a load withdraws its pending offer and
    cancels its allocation, awarded or not, in the same transaction.

  Background:
    Given a tenant "acme" named "Acme Logistics"
    And tenant "acme" has an organisational unit "/acme/" named "Acme HQ" of type "HQ"
    And the tenant scope is "acme" at "/acme/"
    And a trading partner "CUST"
    And a trading partner "CONS-A"
    And a depot "ORIGIN" at 19.10,72.90
    And a depot "DEST-A" at 19.30,73.10
    And a lorry "MH-01-SL-0001" with payload 10000 kg and volume 40 m3
    And lorry "MH-01-SL-0001" has an "INSURANCE" certificate valid for 365 days
    And a lorry driver "Ravi" licensed for 365 days
    And a carrier "VENDOR-1"
    And a carrier "VENDOR-2"

  Scenario: Cancelling an awarded allocation returns the load to PLANNED so it can be offered again
    Given a planned load "LOAD-SL-1" carrying order "SL-1" of 1000 kg
    And a routing guide from "ORIGIN" to "DEST-A" for "RIGID"
    And the guide ranks "VENDOR-1" first with a 60 minute SLA
    And the guide ranks "VENDOR-2" second with a 60 minute SLA
    And load "LOAD-SL-1" is allocated
    And "VENDOR-1" accepts the offer
    And load "LOAD-SL-1" is "AWARDED"
    When the allocation of load "LOAD-SL-1" is cancelled
    Then load "LOAD-SL-1" is "PLANNED"
    And load "LOAD-SL-1" has no awarded vendor
    And load "LOAD-SL-1" has 1 allocations, of which 1 are "CANCELLED"
    When load "LOAD-SL-1" is allocated
    Then the offer is with "VENDOR-1" at rank 1
    When "VENDOR-1" accepts the offer
    Then load "LOAD-SL-1" is "AWARDED"
    And load "LOAD-SL-1" has 2 allocations, of which 1 are "CANCELLED"

  Scenario: Cancelling an outstanding offer leaves the load PLANNED and offerable
    Given a planned load "LOAD-SL-2" carrying order "SL-2" of 1000 kg
    And a routing guide from "ORIGIN" to "DEST-A" for "RIGID"
    And the guide ranks "VENDOR-1" first with a 60 minute SLA
    And load "LOAD-SL-2" is allocated
    When the allocation of load "LOAD-SL-2" is cancelled
    Then load "LOAD-SL-2" is "PLANNED"
    When load "LOAD-SL-2" is allocated
    Then the offer is with "VENDOR-1" at rank 1
    And load "LOAD-SL-2" has 2 allocations, of which 1 are "CANCELLED"

  Scenario: An award cannot be cancelled while a trip is raised against the load
    Given a planned load "LOAD-SL-3" carrying order "SL-3" of 1000 kg
    And a routing guide from "ORIGIN" to "DEST-A" for "RIGID"
    And the guide ranks "VENDOR-1" first with a 60 minute SLA
    And load "LOAD-SL-3" is allocated
    And "VENDOR-1" accepts the offer
    And trip "TRIP-SL-3" is raised on awarded load "LOAD-SL-3"
    When the allocation of load "LOAD-SL-3" is cancelled
    Then cancelling the allocation is refused with "load-has-active-trip"
    And the allocation is "AWARDED" to "VENDOR-1"
    And load "LOAD-SL-3" is "AWARDED"

  Scenario: An award can be cancelled once the trip raised against the load is cancelled
    Given a planned load "LOAD-SL-4" carrying order "SL-4" of 1000 kg
    And a routing guide from "ORIGIN" to "DEST-A" for "RIGID"
    And the guide ranks "VENDOR-1" first with a 60 minute SLA
    And load "LOAD-SL-4" is allocated
    And "VENDOR-1" accepts the offer
    And trip "TRIP-SL-4" is raised on awarded load "LOAD-SL-4"
    And trip "TRIP-SL-4" has been cancelled
    When the allocation of load "LOAD-SL-4" is cancelled
    Then load "LOAD-SL-4" is "PLANNED"

  Scenario: Cancelling the old allocation again does not undo the new award
    Given a planned load "LOAD-SL-5" carrying order "SL-5" of 1000 kg
    And a routing guide from "ORIGIN" to "DEST-A" for "RIGID"
    And the guide ranks "VENDOR-1" first with a 60 minute SLA
    And the guide ranks "VENDOR-2" second with a 60 minute SLA
    And load "LOAD-SL-5" is allocated
    And "VENDOR-1" accepts the offer
    And the allocation of load "LOAD-SL-5" is cancelled
    And load "LOAD-SL-5" is allocated
    And "VENDOR-1" accepts the offer
    When the first allocation of load "LOAD-SL-5" is cancelled again
    Then load "LOAD-SL-5" is "AWARDED"
    And the allocation is "AWARDED" to "VENDOR-1"

  Scenario: Cancelling an award gives the vendor their fair share back
    Given a planned load "LOAD-SL-6" carrying order "SL-6" of 1000 kg
    And a round-robin routing guide from "ORIGIN" to "DEST-A" for "RIGID"
    And the guide ranks "VENDOR-1" first with a 60 minute SLA
    And load "LOAD-SL-6" is allocated
    And "VENDOR-1" accepts the offer
    And "VENDOR-1" has been tallied 1 awards this month
    When the allocation of load "LOAD-SL-6" is cancelled
    Then "VENDOR-1" has been tallied 0 awards this month

  Scenario: Cancelling an order withdraws the pending offer on its emptied load
    Given a planned load "LOAD-SL-7" carrying order "SL-7" of 1000 kg
    And a routing guide from "ORIGIN" to "DEST-A" for "RIGID"
    And the guide ranks "VENDOR-1" first with a 60 minute SLA
    And load "LOAD-SL-7" is allocated
    When order "SL-7" is cancelled
    Then load "LOAD-SL-7" is "CANCELLED"
    And the allocation is "CANCELLED"
    And the offer to "VENDOR-1" is recorded as "CANCELLED"
    And a further acceptance by "VENDOR-1" on load "LOAD-SL-7" is refused mentioning "no offer outstanding"

  Scenario: Cancelling an order cancels the award on its emptied load when no trip was raised
    Given a planned load "LOAD-SL-8" carrying order "SL-8" of 1000 kg
    And a routing guide from "ORIGIN" to "DEST-A" for "RIGID"
    And the guide ranks "VENDOR-1" first with a 60 minute SLA
    And load "LOAD-SL-8" is allocated
    And "VENDOR-1" accepts the offer
    When order "SL-8" is cancelled
    Then load "LOAD-SL-8" is "CANCELLED"
    And the allocation is "CANCELLED"
    And the offer to "VENDOR-1" is recorded as "ACCEPTED"

  Scenario: An order whose awarded load has a trip raised cannot be cancelled
    Given a planned load "LOAD-SL-9" carrying order "SL-9" of 1000 kg
    And a routing guide from "ORIGIN" to "DEST-A" for "RIGID"
    And the guide ranks "VENDOR-1" first with a 60 minute SLA
    And load "LOAD-SL-9" is allocated
    And "VENDOR-1" accepts the offer
    And trip "TRIP-SL-9" is raised on awarded load "LOAD-SL-9"
    When order "SL-9" is cancelled
    Then cancelling the order is refused with "load-has-active-trip"
    And order "SL-9" is "FULLY_PLANNED"
    And the shipment of order "SL-9" for "CONS-A" is "ASSIGNED_TO_LOAD"
    And the allocation is "AWARDED" to "VENDOR-1"
    And load "LOAD-SL-9" is "AWARDED"

  Scenario: Cancelling one of two orders on a load leaves the offer standing
    Given a planned load "LOAD-SL-10" carrying order "SL-10" of 1000 kg
    And order "SL-10-OTHER" of 500 kg is added to planned load "LOAD-SL-10"
    And a routing guide from "ORIGIN" to "DEST-A" for "RIGID"
    And the guide ranks "VENDOR-1" first with a 60 minute SLA
    And load "LOAD-SL-10" is allocated
    When order "SL-10" is cancelled
    Then load "LOAD-SL-10" is "PLANNED"
    And load "LOAD-SL-10" holds 1 consignments
    And the allocation is "OFFERED"
    And the offer is with "VENDOR-1" at rank 1

  Scenario: Cancelling the last of two orders on a load withdraws the offer with it
    Given a planned load "LOAD-SL-11" carrying order "SL-11" of 1000 kg
    And order "SL-11-OTHER" of 500 kg is added to planned load "LOAD-SL-11"
    And a routing guide from "ORIGIN" to "DEST-A" for "RIGID"
    And the guide ranks "VENDOR-1" first with a 60 minute SLA
    And load "LOAD-SL-11" is allocated
    When order "SL-11" is cancelled
    And order "SL-11-OTHER" is cancelled
    Then load "LOAD-SL-11" is "CANCELLED"
    And the allocation is "CANCELLED"
    And the offer to "VENDOR-1" is recorded as "CANCELLED"

  Scenario: An exhausted allocation is left as the record of why nobody took the load
    Given a planned load "LOAD-SL-12" carrying order "SL-12" of 1000 kg
    And a routing guide from "ORIGIN" to "DEST-A" for "RIGID"
    And the guide ranks "VENDOR-1" first with a 60 minute SLA
    And load "LOAD-SL-12" is allocated
    And "VENDOR-1" rejects the offer
    When order "SL-12" is cancelled
    Then load "LOAD-SL-12" is "CANCELLED"
    And the allocation is "EXHAUSTED"
