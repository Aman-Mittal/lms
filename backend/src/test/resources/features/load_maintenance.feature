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

@load-maintenance
Feature: A load is changed after it was built, until it is out with a vendor or gone
  A planner takes a consignment off a load, opens a planned load for building
  again, or cancels a load outright. Each change is made against the version
  of the load the planner last read, so two planners cannot overwrite each
  other, and each one is in the load's history.

  Decisions these scenarios pin down:
  - a consignment taken off goes back to the status it boarded from, the
    drops left behind close up, and the load's totals follow its freight;
  - a load out with a vendor keeps its freight (load-allocated), and so does
    an awarded one (load-awarded), until the allocation is cancelled;
  - cancelling a load withdraws its allocation, offered or awarded, and
    releases its consignments rather than cancelling them, unless a trip is
    already raised against it (load-has-active-trip);
  - nothing comes off a load that has left (load-dispatched), and a cancelled
    load stays cancelled (load-already-cancelled);
  - cancelling a consignment takes it off its load by the same removal, except
    that a load it empties is cancelled rather than refused.

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
    And a carrier "HAULIER"
    And a carrier "VENDOR-1"
    And a carrier "VENDOR-2"
    And a routing guide from "ORIGIN" to "DEST-A" for "RIGID"
    And the guide ranks "VENDOR-1" first with a 60 minute SLA
    And the guide ranks "VENDOR-2" second with a 60 minute SLA

  Scenario: A consignment taken off a planned load closes up the drops and restates the load
    Given a planned load "LOAD-LM-1" carrying order "LM-1" of 1000 kg
    And order "LM-2" of 2000 kg is added to planned load "LOAD-LM-1"
    And order "LM-3" of 3000 kg is added to planned load "LOAD-LM-1"
    When the shipment of order "LM-2" for "CONS-A" is taken off load "LOAD-LM-1"
    Then load "LOAD-LM-1" carries 4000 kg
    And load "LOAD-LM-1" holds 2 consignments
    And the drops of load "LOAD-LM-1" run from 1 to 2
    And load "LOAD-LM-1" is "PLANNED"
    And the shipment of order "LM-2" for "CONS-A" is "PLANNED"
    And the audit trail of load "LOAD-LM-1" reads "LOAD_OPENED, LOAD_PLANNED, LOAD_CONSIGNMENT_REMOVED"
    And the audit trail of the shipment of order "LM-2" for "CONS-A" reads "CONSIGNMENT_GENERATED, CONSIGNMENT_ASSIGNED, CONSIGNMENT_REMOVED_FROM_LOAD"

  Scenario: A consignment cancelled off a planned load leaves the load as a removal would
    Given a planned load "LOAD-LM-CX1" carrying order "LM-CX11" of 1000 kg
    And order "LM-CX12" of 2000 kg is added to planned load "LOAD-LM-CX1"
    And order "LM-CX13" of 3000 kg is added to planned load "LOAD-LM-CX1"
    When the shipment of order "LM-CX11" for "CONS-A" is cancelled because "Customer withdrew it"
    Then load "LOAD-LM-CX1" carries 5000 kg
    And load "LOAD-LM-CX1" holds 2 consignments
    And the drops of load "LOAD-LM-CX1" run from 1 to 2
    And load "LOAD-LM-CX1" is "PLANNED"
    And the audit trail of load "LOAD-LM-CX1" reads "LOAD_OPENED, LOAD_PLANNED, LOAD_CONSIGNMENT_REMOVED"

  Scenario: The last consignment of a planned load is never removed, but cancelling it cancels the load
    Given a planned load "LOAD-LM-CX2" carrying order "LM-CX21" of 1000 kg
    When the shipment of order "LM-CX21" for "CONS-A" is taken off load "LOAD-LM-CX2"
    Then the load change is refused with "load-would-be-empty"
    And load "LOAD-LM-CX2" is "PLANNED"
    When the shipment of order "LM-CX21" for "CONS-A" is cancelled because "Customer withdrew it"
    Then load "LOAD-LM-CX2" is "CANCELLED"
    And the shipment of order "LM-CX21" for "CONS-A" is "CANCELLED"

  Scenario: A load out with a vendor keeps its freight until the allocation is cancelled
    Given a planned load "LOAD-LM-2" carrying order "LM-21" of 1000 kg
    And order "LM-22" of 1000 kg is added to planned load "LOAD-LM-2"
    And load "LOAD-LM-2" is allocated
    When the shipment of order "LM-22" for "CONS-A" is taken off load "LOAD-LM-2"
    Then the load change is refused with "load-allocated"
    When load "LOAD-LM-2" is un-planned
    Then the load change is refused with "load-allocated"
    And load "LOAD-LM-2" carries 2000 kg
    When the allocation of load "LOAD-LM-2" is cancelled
    And the shipment of order "LM-22" for "CONS-A" is taken off load "LOAD-LM-2"
    Then load "LOAD-LM-2" carries 1000 kg
    And the allocation is "CANCELLED"

  Scenario: An awarded load keeps its freight until the award is withdrawn, and is then un-planned
    Given a planned load "LOAD-LM-3" carrying order "LM-31" of 1000 kg
    And order "LM-32" of 1000 kg is added to planned load "LOAD-LM-3"
    And load "LOAD-LM-3" is allocated
    And "VENDOR-1" accepts the offer
    When the shipment of order "LM-32" for "CONS-A" is taken off load "LOAD-LM-3"
    Then the load change is refused with "load-awarded"
    When load "LOAD-LM-3" is un-planned
    Then the load change is refused with "load-awarded"
    When the allocation of load "LOAD-LM-3" is cancelled
    And load "LOAD-LM-3" is un-planned
    Then load "LOAD-LM-3" is "DRAFT"
    And load "LOAD-LM-3" carries 2000 kg
    And the audit trail of load "LOAD-LM-3" reads "LOAD_OPENED, LOAD_PLANNED, LOAD_AWARDED, LOAD_AWARD_WITHDRAWN, LOAD_UNPLANNED"

  Scenario: Cancelling an awarded load releases its freight and withdraws the award
    Given a planned load "LOAD-LM-4" carrying order "LM-4" of 1000 kg
    And load "LOAD-LM-4" is allocated
    And "VENDOR-1" accepts the offer
    When load "LOAD-LM-4" is cancelled because "the customer moved the pickup"
    Then load "LOAD-LM-4" is "CANCELLED"
    And load "LOAD-LM-4" carries 0 kg
    And load "LOAD-LM-4" holds 0 consignments
    And the shipment of order "LM-4" for "CONS-A" is "PLANNED"
    And order "LM-4" is "FULLY_PLANNED"
    And the allocation is "CANCELLED"
    And the audit trail of load "LOAD-LM-4" reads "LOAD_OPENED, LOAD_PLANNED, LOAD_AWARDED, LOAD_CANCELLED"
    And the audit entry "LOAD_CANCELLED" of load "LOAD-LM-4" moved status from "AWARDED" to "CANCELLED"
    And the audit trail of the allocation of load "LOAD-LM-4" reads "ALLOCATION_OPENED, ALLOCATION_AWARDED, ALLOCATION_CANCELLED"
    And the audit trail of the shipment of order "LM-4" for "CONS-A" reads "CONSIGNMENT_GENERATED, CONSIGNMENT_ASSIGNED, CONSIGNMENT_REMOVED_FROM_LOAD"
    When load "LOAD-LM-4" is cancelled because "asked twice"
    Then the load change is refused with "load-already-cancelled"

  Scenario: A load with a trip raised against it is not cancelled
    Given a planned load "LOAD-LM-5" carrying order "LM-5" of 1000 kg
    And load "LOAD-LM-5" is allocated
    And "VENDOR-1" accepts the offer
    And trip "TRIP-LM-5" is raised on awarded load "LOAD-LM-5"
    When load "LOAD-LM-5" is cancelled because "no longer needed"
    Then the load change is refused with "load-has-active-trip"
    And load "LOAD-LM-5" is "AWARDED"
    And the allocation is "AWARDED"

  Scenario: Nothing comes off a load that has left
    Given a planned load "LOAD-LM-6" carrying order "LM-61" of 1000 kg
    And order "LM-62" of 1000 kg is added to planned load "LOAD-LM-6"
    And trip "TRIP-LM-6" runs load "LOAD-LM-6" for "HAULIER" on lorry "MH-01-SL-0001" driven by "Ravi"
    When the shipment of order "LM-62" for "CONS-A" is taken off load "LOAD-LM-6"
    Then the load change is refused with "load-dispatched"
    When load "LOAD-LM-6" is cancelled because "too late"
    Then the load change is refused with "load-dispatched"
    And load "LOAD-LM-6" carries 2000 kg
    And load "LOAD-LM-6" is "DISPATCHED"

  Scenario: A change made against a version that is no longer current changes nothing
    Given a planned load "LOAD-LM-7" carrying order "LM-71" of 1000 kg
    And order "LM-72" of 1000 kg is added to planned load "LOAD-LM-7"
    When the shipment of order "LM-72" for "CONS-A" is taken off load "LOAD-LM-7" at a stale version
    Then the load change is refused with "concurrent-modification"
    When load "LOAD-LM-7" is un-planned at a stale version
    Then the load change is refused with "concurrent-modification"
    And load "LOAD-LM-7" carries 2000 kg
    And load "LOAD-LM-7" is "PLANNED"

  Scenario: The last consignment stays on a planned load
    Given a planned load "LOAD-LM-8" carrying order "LM-8" of 1000 kg
    When the shipment of order "LM-8" for "CONS-A" is taken off load "LOAD-LM-8"
    Then the load change is refused with "load-would-be-empty"
    When load "LOAD-LM-8" is un-planned
    And the shipment of order "LM-8" for "CONS-A" is taken off load "LOAD-LM-8"
    Then load "LOAD-LM-8" is "DRAFT"
    And load "LOAD-LM-8" holds 0 consignments
    When the shipment of order "LM-8" for "CONS-A" is taken off load "LOAD-LM-8"
    Then the load change is refused with "consignment-not-on-load"

  Scenario: A docket taken off a delivery load waits at its hub again
    Given a trading partner "CUST-1"
    And a hub "HUB-BOM" at 19.10,72.90
    And a hub "HUB-DEL" at 28.60,77.20
    And a depot "SITE-A" at 28.40,77.00
    And terminal "HUB-BOM" is in LTL zone "WEST"
    And terminal "HUB-DEL" is in LTL zone "NORTH"
    And an LTL rate card "WN-1" from zone "WEST" to zone "NORTH" charging 12.00 per kg with a 50 kg minimum, 6 kg per CFT, a 100 docket charge and a 500 ODA surcharge
    And a lorry "MH-04-LM-0011" with payload 10000 kg and volume 40 m3
    And lorry "MH-04-LM-0011" has an "INSURANCE" certificate valid for 365 days
    And a lorry "DL-01-LM-0012" with payload 3000 kg and volume 20 m3
    And an LTL booking "LTL-LM-9" for "CUST-1" through hub "HUB-BOM" to hub "HUB-DEL"
    And order "LTL-LM-9" has line 1 of 200 kg of GENERAL for "CONS-A" at "SITE-A"
    And order "LTL-LM-9" is validated
    And consignments are generated for order "LTL-LM-9"
    And an LTL load "LOAD-LH-LM-9" is opened at hub "HUB-BOM" on lorry "MH-04-LM-0011"
    And the shipment of order "LTL-LM-9" for "CONS-A" is put on load "LOAD-LH-LM-9"
    And load "LOAD-LH-LM-9" is planned
    And trip "TRIP-LH-LM-9" runs load "LOAD-LH-LM-9" for "HAULIER" on lorry "MH-04-LM-0011" driven by "Ravi"
    And trip "TRIP-LH-LM-9" is completed at its destination
    And an LTL delivery load "LOAD-DL-LM-9" is opened at hub "HUB-DEL" on lorry "DL-01-LM-0012"
    And the shipment of order "LTL-LM-9" for "CONS-A" is put on load "LOAD-DL-LM-9"
    And the shipment of order "LTL-LM-9" for "CONS-A" is "ASSIGNED_TO_DELIVERY"
    When the shipment of order "LTL-LM-9" for "CONS-A" is taken off load "LOAD-DL-LM-9"
    Then the shipment of order "LTL-LM-9" for "CONS-A" is "ARRIVED_AT_HUB"
    And hub "HUB-DEL" has 1 dockets awaiting delivery
    And load "LOAD-DL-LM-9" carries 0 kg
    And load "LOAD-LH-LM-9" is "COMPLETED"
