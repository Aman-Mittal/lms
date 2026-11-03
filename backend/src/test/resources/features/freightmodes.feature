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

Feature: Full truckloads and part loads are planned and priced differently
  Issue #18; vision document 3.3 and 3.4.

  A full truckload (FTL) is one customer's freight on the whole vehicle, priced
  per vehicle. A part load (LTL) is booked shipment by shipment: each one gets
  a docket between an origin hub and a destination hub, dockets from many
  customers are consolidated onto one vehicle, and each docket is billed to its
  own customer by zone on its own chargeable weight. The vehicle is still paid
  for as a vehicle, so the customer receivable per docket is a separate
  document from the carrier's freight bill.

  The rate card used throughout charges 12.00 per kg between zones WEST and
  NORTH, never less than 50 kg, bills each cubic foot as 6 kg, and adds 100 per
  docket and 500 when delivery is out of the delivery area (ODA).

  A docket travels twice. The line haul carries it from its origin hub to its
  destination hub; when that trip completes the docket has ARRIVED_AT_HUB, and
  a local delivery load from that hub takes it to its consignee.

  Background:
    Given a tenant "acme" named "Acme Logistics"
    And tenant "acme" has an organisational unit "/acme/" named "Acme HQ" of type "HQ"
    And the tenant scope is "acme" at "/acme/"
    And a trading partner "CUST-1"
    And a trading partner "CUST-2"
    And a trading partner "CONS-A"
    And a trading partner "CONS-B"
    And a hub "HUB-BOM" at 19.10,72.90
    And a hub "HUB-DEL" at 28.60,77.20
    And a hub "HUB-BLR" at 12.95,77.60
    And a hub "HUB-CCU" at 22.55,88.35
    And a depot "SITE-A" at 28.40,77.00
    And a depot "SITE-B" at 29.40,77.90
    And terminal "HUB-BOM" is in LTL zone "WEST"
    And terminal "HUB-DEL" is in LTL zone "NORTH"
    And terminal "HUB-BLR" is in LTL zone "SOUTH"
    And terminal "HUB-CCU" is in LTL zone "EAST"
    And terminal "SITE-B" is out of the delivery area in LTL zone "NORTH"
    And an LTL rate card "WN-1" from zone "WEST" to zone "NORTH" charging 12.00 per kg with a 50 kg minimum, 6 kg per CFT, a 100 docket charge and a 500 ODA surcharge

  # ----------------------------------------------------------- the mode

  Scenario: A full truckload is unchanged: lorry receipts, and no docket billing
    Given an order "FTL-1" for "CUST-1" from "HUB-BOM"
    And order "FTL-1" has line 1 of 200 kg of GENERAL for "CONS-A" at "SITE-A"
    And order "FTL-1" is validated
    When consignments are generated for order "FTL-1"
    Then the shipment of order "FTL-1" for "CONS-A" is numbered in the "LR-" series
    And order "FTL-1" raised no docket receivable

  Scenario: A part-load shipment is booked on a docket
    Given an LTL booking "LTL-1" for "CUST-1" through hub "HUB-BOM" to hub "HUB-DEL"
    And order "LTL-1" has line 1 of 200 kg of GENERAL for "CONS-A" at "SITE-A"
    And order "LTL-1" is validated
    When consignments are generated for order "LTL-1"
    Then the shipment of order "LTL-1" for "CONS-A" is numbered in the "DKT-" series

  Scenario: A part load must name its hubs
    When a LTL booking "LTL-2" for "CUST-1" from "HUB-BOM" is attempted
    Then the booking is refused with "ltl-order-needs-hubs"

  Scenario: A part load's origin hub must be a hub
    # SITE-A is a customer's site. Consolidating dockets there would gather
    # freight at a yard no line haul calls at.
    When an LTL booking "LTL-3" for "CUST-1" through hub "SITE-A" to hub "HUB-DEL" is attempted
    Then the booking is refused with "ltl-hub-not-a-hub"

  Scenario: A part load's destination hub must be a hub
    When an LTL booking "LTL-4" for "CUST-1" through hub "HUB-BOM" to hub "SITE-B" is attempted
    Then the booking is refused with "ltl-hub-not-a-hub"

  Scenario: Reserved freight modes cannot be booked yet
    # RAIL is named so the vocabulary is stable, not because it can be planned.
    When a RAIL booking "RAIL-1" for "CUST-1" from "HUB-BOM" is attempted
    Then the booking is refused with "freight-mode-not-supported"

  # ---------------------------------------------------------- LTL rating

  Scenario: A docket is priced zone to zone on its own weight
    # 200 kg x 12.00 = 2400, plus the 100 docket charge.
    Given an LTL booking "LTL-10" for "CUST-1" through hub "HUB-BOM" to hub "HUB-DEL"
    And order "LTL-10" has line 1 of 200 kg of GENERAL for "CONS-A" at "SITE-A"
    And order "LTL-10" is validated
    When consignments are generated for order "LTL-10"
    Then the receivable for order "LTL-10" and "CONS-A" is charged on 200.000 kg
    And the receivable for order "LTL-10" and "CONS-A" carries a docket charge of 100.00 and an ODA surcharge of 0.00
    And the receivable for order "LTL-10" and "CONS-A" totals 2500.00

  Scenario: A light docket is billed at the minimum chargeable weight
    # 30 kg is billed as 50 kg: 600, plus 100.
    Given an LTL booking "LTL-11" for "CUST-1" through hub "HUB-BOM" to hub "HUB-DEL"
    And order "LTL-11" has line 1 of 30 kg of GENERAL for "CONS-A" at "SITE-A"
    And order "LTL-11" is validated
    When consignments are generated for order "LTL-11"
    Then the receivable for order "LTL-11" and "CONS-A" is charged on 50.000 kg
    And the receivable for order "LTL-11" and "CONS-A" applied the minimum weight
    And the receivable for order "LTL-11" and "CONS-A" totals 700.00

  Scenario: A bulky docket is billed on its volume in cubic feet
    # 1 m3 is 35.315 CFT; at 6 kg per CFT that is 211.890 kg, which outweighs
    # the 100 kg it actually weighs. 211.890 x 12.00 = 2542.68, plus 100.
    Given an LTL booking "LTL-12" for "CUST-1" through hub "HUB-BOM" to hub "HUB-DEL"
    And order "LTL-12" has line 1 of 100 kg of GENERAL measuring 1.0 by 1.0 by 1.0 m for "CONS-A" at "SITE-A"
    And order "LTL-12" is validated
    When consignments are generated for order "LTL-12"
    Then the receivable for order "LTL-12" and "CONS-A" is charged on 211.890 kg
    And the receivable for order "LTL-12" and "CONS-A" totals 2642.68

  Scenario: Delivery out of the delivery area earns the ODA surcharge
    Given an LTL booking "LTL-13" for "CUST-1" through hub "HUB-BOM" to hub "HUB-DEL"
    And order "LTL-13" has line 1 of 200 kg of GENERAL for "CONS-B" at "SITE-B"
    And order "LTL-13" is validated
    When consignments are generated for order "LTL-13"
    Then the receivable for order "LTL-13" and "CONS-B" carries a docket charge of 100.00 and an ODA surcharge of 500.00
    And the receivable for order "LTL-13" and "CONS-B" totals 3000.00

  Scenario: A card can price per cubic foot
    # 140 kg at 7 kg per CFT is 20 CFT; 20 x 70.00 = 1400, plus 50.
    Given an LTL rate card "WS-1" from zone "WEST" to zone "SOUTH" charging 70.00 per CFT with a 0 kg minimum, 7 kg per CFT, a 50 docket charge and a 0 ODA surcharge
    And an LTL booking "LTL-14" for "CUST-1" through hub "HUB-BOM" to hub "HUB-BLR"
    And order "LTL-14" has line 1 of 140 kg of GENERAL for "CONS-A" at "SITE-A"
    And order "LTL-14" is validated
    When consignments are generated for order "LTL-14"
    Then the receivable for order "LTL-14" and "CONS-A" totals 1450.00

  Scenario: A docket on a lane with no rate card is raised unpriced, not skipped
    Given an LTL booking "LTL-15" for "CUST-1" through hub "HUB-BOM" to hub "HUB-CCU"
    And order "LTL-15" has line 1 of 200 kg of GENERAL for "CONS-A" at "SITE-A"
    And order "LTL-15" is validated
    When consignments are generated for order "LTL-15"
    Then the receivable for order "LTL-15" and "CONS-A" is unpriced because "No LTL rate card from zone WEST to zone EAST"

  # ------------------------------------------------------ fuel surcharge

  Scenario: The booking month's fuel index surcharges the docket's freight
    # Freight 200 kg x 12.00 = 2400; 7.5% of that is 180.00. The docket charge
    # is not surcharged: 2400 + 100 + 180 = 2680.
    Given the fuel surcharge 0 days ago was 7.5 percent
    And an LTL booking "LTL-16" for "CUST-1" through hub "HUB-BOM" to hub "HUB-DEL"
    And order "LTL-16" has line 1 of 200 kg of GENERAL for "CONS-A" at "SITE-A"
    And order "LTL-16" is validated
    When consignments are generated for order "LTL-16"
    Then the receivable for order "LTL-16" and "CONS-A" carries a fuel surcharge of 180.00 at 7.5 percent
    And the receivable for order "LTL-16" and "CONS-A" totals 2680.00

  Scenario: The fuel surcharge leaves the ODA surcharge alone
    # 2400 freight, 180 fuel on it, 100 docket, 500 ODA unsurcharged.
    Given the fuel surcharge 0 days ago was 7.5 percent
    And an LTL booking "LTL-17" for "CUST-1" through hub "HUB-BOM" to hub "HUB-DEL"
    And order "LTL-17" has line 1 of 200 kg of GENERAL for "CONS-B" at "SITE-B"
    And order "LTL-17" is validated
    When consignments are generated for order "LTL-17"
    Then the receivable for order "LTL-17" and "CONS-B" carries a fuel surcharge of 180.00 at 7.5 percent
    And the receivable for order "LTL-17" and "CONS-B" totals 3180.00

  Scenario: Another month's fuel index does not apply
    # Forty days ago is always another month.
    Given the fuel surcharge 40 days ago was 9.0 percent
    And an LTL booking "LTL-18" for "CUST-1" through hub "HUB-BOM" to hub "HUB-DEL"
    And order "LTL-18" has line 1 of 200 kg of GENERAL for "CONS-A" at "SITE-A"
    And order "LTL-18" is validated
    When consignments are generated for order "LTL-18"
    Then the receivable for order "LTL-18" and "CONS-A" carries no fuel surcharge
    And the receivable for order "LTL-18" and "CONS-A" totals 2500.00

  Scenario: A customer-portal user reads only their own docket receivables
    Given an LTL booking "LTL-19" for "CUST-1" through hub "HUB-BOM" to hub "HUB-DEL"
    And order "LTL-19" has line 1 of 200 kg of GENERAL for "CONS-A" at "SITE-A"
    And order "LTL-19" is validated
    And consignments are generated for order "LTL-19"
    And an LTL booking "LTL-29B" for "CUST-2" through hub "HUB-BOM" to hub "HUB-DEL"
    And order "LTL-29B" has line 1 of 100 kg of GENERAL for "CONS-B" at "SITE-B"
    And order "LTL-29B" is validated
    And consignments are generated for order "LTL-29B"
    When the signed-in user is a portal user for "CUST-1"
    Then the docket receivables of "anyone" listed by the signed-in user number 1, all for "CUST-1"
    And the docket receivables of "CUST-2" listed by the signed-in user number 0, all for "CUST-1"

  # -------------------------------------------------------- ODA per kg

  Scenario: An ODA charge per kg is reckoned on the chargeable weight
    # A successor card, from today, charges ODA at 3.50 per kg, never less
    # than 250. 200 kg x 3.50 = 700. Total 2400 + 100 + 700 = 3200.
    Given an LTL rate card "WN-2" from zone "WEST" to zone "NORTH" charging 12.00 per kg with a 50 kg minimum, 6 kg per CFT, a 100 docket charge and ODA at 3.50 per kg with a 250 minimum
    And an LTL booking "LTL-40" for "CUST-1" through hub "HUB-BOM" to hub "HUB-DEL"
    And order "LTL-40" has line 1 of 200 kg of GENERAL for "CONS-B" at "SITE-B"
    And order "LTL-40" is validated
    When consignments are generated for order "LTL-40"
    Then the receivable for order "LTL-40" and "CONS-B" carries a docket charge of 100.00 and an ODA surcharge of 700.00
    And the receivable for order "LTL-40" and "CONS-B" charged ODA "PER_KG"
    And the receivable for order "LTL-40" and "CONS-B" totals 3200.00

  Scenario: An ODA charge per kg never falls below its minimum
    # 30 kg is billed as 50 kg; 50 x 3.50 = 175, lifted to 250.
    # Freight 600 + docket 100 + ODA 250 = 950.
    Given an LTL rate card "WN-3" from zone "WEST" to zone "NORTH" charging 12.00 per kg with a 50 kg minimum, 6 kg per CFT, a 100 docket charge and ODA at 3.50 per kg with a 250 minimum
    And an LTL booking "LTL-41" for "CUST-1" through hub "HUB-BOM" to hub "HUB-DEL"
    And order "LTL-41" has line 1 of 30 kg of GENERAL for "CONS-B" at "SITE-B"
    And order "LTL-41" is validated
    When consignments are generated for order "LTL-41"
    Then the receivable for order "LTL-41" and "CONS-B" carries a docket charge of 100.00 and an ODA surcharge of 250.00
    And the receivable for order "LTL-41" and "CONS-B" totals 950.00

  Scenario: A flat ODA card says so on the receivable
    Given an LTL booking "LTL-42" for "CUST-1" through hub "HUB-BOM" to hub "HUB-DEL"
    And order "LTL-42" has line 1 of 200 kg of GENERAL for "CONS-B" at "SITE-B"
    And order "LTL-42" is validated
    When consignments are generated for order "LTL-42"
    Then the receivable for order "LTL-42" and "CONS-B" charged ODA "FLAT"

  Scenario: A per-kg ODA card must state its rate
    When an LTL rate card "WN-4" from zone "WEST" to zone "NORTH" with ODA per kg but no rate is attempted
    Then the rate card is refused as invalid mentioning "per kg"

  # ------------------------------------------------------- consolidation

  Scenario: Dockets from different customers are consolidated onto one vehicle
    Given a lorry "MH-04-LT-0001" with payload 10000 kg and volume 40 m3
    And an LTL booking "LTL-20" for "CUST-1" through hub "HUB-BOM" to hub "HUB-DEL"
    And order "LTL-20" has line 1 of 200 kg of GENERAL for "CONS-A" at "SITE-A"
    And order "LTL-20" is validated
    And consignments are generated for order "LTL-20"
    And an LTL booking "LTL-21" for "CUST-2" through hub "HUB-BOM" to hub "HUB-DEL"
    And order "LTL-21" has line 1 of 300 kg of GENERAL for "CONS-B" at "SITE-B"
    And order "LTL-21" is validated
    And consignments are generated for order "LTL-21"
    And an LTL load "LOAD-LTL-20" is opened at hub "HUB-BOM" on lorry "MH-04-LT-0001"
    When the shipment of order "LTL-20" for "CONS-A" is put on load "LOAD-LTL-20"
    And the shipment of order "LTL-21" for "CONS-B" is put on load "LOAD-LTL-20"
    And load "LOAD-LTL-20" is planned
    Then load "LOAD-LTL-20" holds 2 consignments
    And load "LOAD-LTL-20" carries 500 kg
    And load "LOAD-LTL-20" consolidates dockets from 2 customers
    And load "LOAD-LTL-20" is sourced on the lane from "HUB-BOM" to "HUB-DEL"
    And load "LOAD-LTL-20" is "PLANNED"

  Scenario: A full-truckload consignment is refused on a part-load vehicle
    Given a lorry "MH-04-LT-0002" with payload 10000 kg and volume 40 m3
    And an order "FTL-22" for "CUST-1" from "HUB-BOM"
    And order "FTL-22" has line 1 of 200 kg of GENERAL for "CONS-A" at "SITE-A"
    And order "FTL-22" is validated
    And consignments are generated for order "FTL-22"
    And an LTL load "LOAD-LTL-22" is opened at hub "HUB-BOM" on lorry "MH-04-LT-0002"
    When the shipment of order "FTL-22" for "CONS-A" is put on load "LOAD-LTL-22"
    Then consolidation is refused with "load-freight-mode-mismatch"

  Scenario: A docket is refused on a full-truckload vehicle
    Given a lorry "MH-04-LT-0003" with payload 10000 kg and volume 40 m3
    And an LTL booking "LTL-23" for "CUST-1" through hub "HUB-BOM" to hub "HUB-DEL"
    And order "LTL-23" has line 1 of 200 kg of GENERAL for "CONS-A" at "SITE-A"
    And order "LTL-23" is validated
    And consignments are generated for order "LTL-23"
    And a load "LOAD-FTL-23" is opened at "HUB-BOM" on lorry "MH-04-LT-0003"
    When the shipment of order "LTL-23" for "CONS-A" is put on load "LOAD-FTL-23"
    Then consolidation is refused with "load-freight-mode-mismatch"

  Scenario: A docket booked through another origin hub is not in the yard to load
    Given a lorry "MH-04-LT-0004" with payload 10000 kg and volume 40 m3
    And an LTL booking "LTL-24" for "CUST-1" through hub "HUB-BLR" to hub "HUB-DEL"
    And order "LTL-24" has line 1 of 200 kg of GENERAL for "CONS-A" at "SITE-A"
    And order "LTL-24" is validated
    And consignments are generated for order "LTL-24"
    And an LTL load "LOAD-LTL-24" is opened at hub "HUB-BOM" on lorry "MH-04-LT-0004"
    When the shipment of order "LTL-24" for "CONS-A" is put on load "LOAD-LTL-24"
    Then consolidation is refused with "docket-not-at-load-origin"

  Scenario: One line haul runs to one destination hub
    Given a lorry "MH-04-LT-0005" with payload 10000 kg and volume 40 m3
    And an LTL booking "LTL-25" for "CUST-1" through hub "HUB-BOM" to hub "HUB-DEL"
    And order "LTL-25" has line 1 of 200 kg of GENERAL for "CONS-A" at "SITE-A"
    And order "LTL-25" is validated
    And consignments are generated for order "LTL-25"
    And an LTL booking "LTL-26" for "CUST-2" through hub "HUB-BOM" to hub "HUB-BLR"
    And order "LTL-26" has line 1 of 200 kg of GENERAL for "CONS-B" at "SITE-B"
    And order "LTL-26" is validated
    And consignments are generated for order "LTL-26"
    And an LTL load "LOAD-LTL-25" is opened at hub "HUB-BOM" on lorry "MH-04-LT-0005"
    And the shipment of order "LTL-25" for "CONS-A" is put on load "LOAD-LTL-25"
    When the shipment of order "LTL-26" for "CONS-B" is put on load "LOAD-LTL-25"
    Then consolidation is refused with "docket-destination-hub-differs"

  Scenario: Consolidation does not lift the vehicle's capacity
    Given a lorry "MH-04-LT-0006" with payload 400 kg and volume 40 m3
    And an LTL booking "LTL-27" for "CUST-1" through hub "HUB-BOM" to hub "HUB-DEL"
    And order "LTL-27" has line 1 of 200 kg of GENERAL for "CONS-A" at "SITE-A"
    And order "LTL-27" is validated
    And consignments are generated for order "LTL-27"
    And an LTL booking "LTL-28" for "CUST-2" through hub "HUB-BOM" to hub "HUB-DEL"
    And order "LTL-28" has line 1 of 300 kg of GENERAL for "CONS-B" at "SITE-B"
    And order "LTL-28" is validated
    And consignments are generated for order "LTL-28"
    And an LTL load "LOAD-LTL-27" is opened at hub "HUB-BOM" on lorry "MH-04-LT-0006"
    And the shipment of order "LTL-27" for "CONS-A" is put on load "LOAD-LTL-27"
    When the shipment of order "LTL-28" for "CONS-B" is put on load "LOAD-LTL-27"
    Then consolidation is refused with "load-capacity-exceeded"
    And load "LOAD-LTL-27" carries 200 kg

  Scenario: Consolidation does not lift the compatibility matrix
    # Two customers, each with a perfectly legal docket. Sharing a vehicle is
    # what makes them a hazard, and that is exactly what consolidation does.
    Given a hazmat-certified lorry "MH-04-LT-0007" with payload 10000 kg and volume 40 m3
    And an LTL booking "LTL-29" for "CUST-1" through hub "HUB-BOM" to hub "HUB-DEL"
    And order "LTL-29" has line 1 of 200 kg of FOOD for "CONS-A" at "SITE-A"
    And order "LTL-29" is validated
    And consignments are generated for order "LTL-29"
    And an LTL booking "LTL-30" for "CUST-2" through hub "HUB-BOM" to hub "HUB-DEL"
    And order "LTL-30" has line 1 of 100 kg of TOXIC UN "UN2810" for "CONS-B" at "SITE-B"
    And order "LTL-30" is validated
    And consignments are generated for order "LTL-30"
    And an LTL load "LOAD-LTL-29" is opened at hub "HUB-BOM" on lorry "MH-04-LT-0007"
    And the shipment of order "LTL-29" for "CONS-A" is put on load "LOAD-LTL-29"
    When the shipment of order "LTL-30" for "CONS-B" is put on load "LOAD-LTL-29"
    Then consolidation is refused with "load-incompatible-materials"

  # -------------------------------------------------------- delivery leg

  Scenario: A docket arrives at its destination hub when the line haul's trip completes
    Given a lorry "MH-04-LT-0010" with payload 10000 kg and volume 40 m3
    And lorry "MH-04-LT-0010" has an "INSURANCE" certificate valid for 365 days
    And a lorry driver "Ravi" licensed for 365 days
    And a carrier "LINEHAUL-CO"
    And an LTL booking "LTL-50" for "CUST-1" through hub "HUB-BOM" to hub "HUB-DEL"
    And order "LTL-50" has line 1 of 200 kg of GENERAL for "CONS-A" at "SITE-A"
    And order "LTL-50" is validated
    And consignments are generated for order "LTL-50"
    And an LTL load "LOAD-LH-50" is opened at hub "HUB-BOM" on lorry "MH-04-LT-0010"
    And the shipment of order "LTL-50" for "CONS-A" is put on load "LOAD-LH-50"
    And load "LOAD-LH-50" is planned
    When trip "TRIP-LH-50" runs load "LOAD-LH-50" for "LINEHAUL-CO" on lorry "MH-04-LT-0010" driven by "Ravi"
    Then the shipment of order "LTL-50" for "CONS-A" is "IN_TRANSIT"
    When trip "TRIP-LH-50" is completed at its destination
    Then the shipment of order "LTL-50" for "CONS-A" is "ARRIVED_AT_HUB"
    And load "LOAD-LH-50" is "COMPLETED"
    And hub "HUB-DEL" has 1 docket awaiting delivery
    And hub "HUB-BOM" has 0 dockets awaiting delivery

  Scenario: An arrived docket goes out on a delivery load from its hub to its consignee
    Given a lorry "MH-04-LT-0011" with payload 10000 kg and volume 40 m3
    And lorry "MH-04-LT-0011" has an "INSURANCE" certificate valid for 365 days
    And a lorry "DL-01-LT-0012" with payload 3000 kg and volume 20 m3
    And lorry "DL-01-LT-0012" has an "INSURANCE" certificate valid for 365 days
    And a lorry driver "Ravi" licensed for 365 days
    And a lorry driver "Sunil" licensed for 365 days
    And a carrier "LINEHAUL-CO"
    And a carrier "CITY-CO"
    And an LTL booking "LTL-51" for "CUST-1" through hub "HUB-BOM" to hub "HUB-DEL"
    And order "LTL-51" has line 1 of 200 kg of GENERAL for "CONS-A" at "SITE-A"
    And order "LTL-51" is validated
    And consignments are generated for order "LTL-51"
    And an LTL load "LOAD-LH-51" is opened at hub "HUB-BOM" on lorry "MH-04-LT-0011"
    And the shipment of order "LTL-51" for "CONS-A" is put on load "LOAD-LH-51"
    And load "LOAD-LH-51" is planned
    And trip "TRIP-LH-51" runs load "LOAD-LH-51" for "LINEHAUL-CO" on lorry "MH-04-LT-0011" driven by "Ravi"
    And trip "TRIP-LH-51" is completed at its destination
    And an LTL delivery load "LOAD-DL-51" is opened at hub "HUB-DEL" on lorry "DL-01-LT-0012"
    When the shipment of order "LTL-51" for "CONS-A" is put on load "LOAD-DL-51"
    And load "LOAD-DL-51" is planned
    Then the shipment of order "LTL-51" for "CONS-A" is "ASSIGNED_TO_DELIVERY"
    And hub "HUB-DEL" has 0 dockets awaiting delivery
    And delivery load "LOAD-DL-51" runs from "HUB-DEL" to "SITE-A"
    When trip "TRIP-DL-51" runs load "LOAD-DL-51" for "CITY-CO" on lorry "DL-01-LT-0012" driven by "Sunil"
    Then the shipment of order "LTL-51" for "CONS-A" is "OUT_FOR_DELIVERY"
    When trip "TRIP-DL-51" is completed at its destination
    Then the shipment of order "LTL-51" for "CONS-A" is "DELIVERED"
    And load "LOAD-DL-51" is "COMPLETED"
    And the shipment of order "LTL-51" was carried on load "LOAD-LH-51" and then on load "LOAD-DL-51"

  Scenario: A docket still on the road cannot go out for delivery
    Given a lorry "DL-01-LT-0013" with payload 3000 kg and volume 20 m3
    And an LTL booking "LTL-52" for "CUST-1" through hub "HUB-BOM" to hub "HUB-DEL"
    And order "LTL-52" has line 1 of 200 kg of GENERAL for "CONS-A" at "SITE-A"
    And order "LTL-52" is validated
    And consignments are generated for order "LTL-52"
    And an LTL delivery load "LOAD-DL-52" is opened at hub "HUB-DEL" on lorry "DL-01-LT-0013"
    When the shipment of order "LTL-52" for "CONS-A" is put on load "LOAD-DL-52"
    Then consolidation is refused with "docket-not-arrived-at-hub"

  Scenario: A delivery load takes only dockets that arrived at its own hub
    Given a lorry "MH-04-LT-0014" with payload 10000 kg and volume 40 m3
    And lorry "MH-04-LT-0014" has an "INSURANCE" certificate valid for 365 days
    And a lorry "KA-01-LT-0015" with payload 3000 kg and volume 20 m3
    And a lorry driver "Ravi" licensed for 365 days
    And a carrier "LINEHAUL-CO"
    And an LTL booking "LTL-53" for "CUST-1" through hub "HUB-BOM" to hub "HUB-DEL"
    And order "LTL-53" has line 1 of 200 kg of GENERAL for "CONS-A" at "SITE-A"
    And order "LTL-53" is validated
    And consignments are generated for order "LTL-53"
    And an LTL load "LOAD-LH-53" is opened at hub "HUB-BOM" on lorry "MH-04-LT-0014"
    And the shipment of order "LTL-53" for "CONS-A" is put on load "LOAD-LH-53"
    And load "LOAD-LH-53" is planned
    And trip "TRIP-LH-53" runs load "LOAD-LH-53" for "LINEHAUL-CO" on lorry "MH-04-LT-0014" driven by "Ravi"
    And trip "TRIP-LH-53" is completed at its destination
    And an LTL delivery load "LOAD-DL-53" is opened at hub "HUB-BLR" on lorry "KA-01-LT-0015"
    When the shipment of order "LTL-53" for "CONS-A" is put on load "LOAD-DL-53"
    Then consolidation is refused with "docket-at-another-hub"

  Scenario: An arrived docket cannot ride a second line haul
    Given a lorry "MH-04-LT-0016" with payload 10000 kg and volume 40 m3
    And lorry "MH-04-LT-0016" has an "INSURANCE" certificate valid for 365 days
    And a lorry "DL-01-LT-0017" with payload 10000 kg and volume 40 m3
    And a lorry driver "Ravi" licensed for 365 days
    And a carrier "LINEHAUL-CO"
    And an LTL booking "LTL-54" for "CUST-1" through hub "HUB-BOM" to hub "HUB-DEL"
    And order "LTL-54" has line 1 of 200 kg of GENERAL for "CONS-A" at "SITE-A"
    And order "LTL-54" is validated
    And consignments are generated for order "LTL-54"
    And an LTL load "LOAD-LH-54" is opened at hub "HUB-BOM" on lorry "MH-04-LT-0016"
    And the shipment of order "LTL-54" for "CONS-A" is put on load "LOAD-LH-54"
    And load "LOAD-LH-54" is planned
    And trip "TRIP-LH-54" runs load "LOAD-LH-54" for "LINEHAUL-CO" on lorry "MH-04-LT-0016" driven by "Ravi"
    And trip "TRIP-LH-54" is completed at its destination
    And an LTL load "LOAD-LH-54B" is opened at hub "HUB-DEL" on lorry "DL-01-LT-0017"
    When the shipment of order "LTL-54" for "CONS-A" is put on load "LOAD-LH-54B"
    Then consolidation is refused with "consignment-not-assignable"

  Scenario: A full truckload has no separate delivery leg
    Given a lorry "MH-04-LT-0018" with payload 10000 kg and volume 40 m3
    When a full-truckload delivery load "LOAD-DL-55" is attempted at "HUB-DEL" on lorry "MH-04-LT-0018"
    Then the delivery load is refused with "delivery-leg-only-for-ltl"

  Scenario: A docket billed as a receivable is not charged again as client transport
    # CUST-1 is also a 3PL client with a per-kg transport rate. Its docket is
    # billed once, by the docket receivable; neither the line haul nor the
    # delivery trip adds a client transport charge on top.
    Given client "CUST-1" holds contract "C1-2026" effective 90 days ago:
      | transport per kg        | 5.00 |
      | transport minimum       | 0    |
      | storage per pallet-day  | 0    |
      | handling in per pallet  | 0    |
      | handling out per pallet | 0    |
    And a lorry "MH-04-LT-0019" with payload 10000 kg and volume 40 m3
    And lorry "MH-04-LT-0019" has an "INSURANCE" certificate valid for 365 days
    And a lorry "DL-01-LT-0020" with payload 3000 kg and volume 20 m3
    And lorry "DL-01-LT-0020" has an "INSURANCE" certificate valid for 365 days
    And a lorry driver "Ravi" licensed for 365 days
    And a lorry driver "Sunil" licensed for 365 days
    And a carrier "LINEHAUL-CO"
    And a carrier "CITY-CO"
    And an LTL booking "LTL-56" for "CUST-1" through hub "HUB-BOM" to hub "HUB-DEL"
    And order "LTL-56" has line 1 of 200 kg of GENERAL for "CONS-A" at "SITE-A"
    And order "LTL-56" is validated
    And consignments are generated for order "LTL-56"
    And an LTL load "LOAD-LH-56" is opened at hub "HUB-BOM" on lorry "MH-04-LT-0019"
    And the shipment of order "LTL-56" for "CONS-A" is put on load "LOAD-LH-56"
    And load "LOAD-LH-56" is planned
    And trip "TRIP-LH-56" runs load "LOAD-LH-56" for "LINEHAUL-CO" on lorry "MH-04-LT-0019" driven by "Ravi"
    And trip "TRIP-LH-56" is completed at its destination
    And an LTL delivery load "LOAD-DL-56" is opened at hub "HUB-DEL" on lorry "DL-01-LT-0020"
    And the shipment of order "LTL-56" for "CONS-A" is put on load "LOAD-DL-56"
    And load "LOAD-DL-56" is planned
    And trip "TRIP-DL-56" runs load "LOAD-DL-56" for "CITY-CO" on lorry "DL-01-LT-0020" driven by "Sunil"
    When trip "TRIP-DL-56" is completed at its destination
    And the completion of trip "TRIP-DL-56" is delivered to client billing again
    Then the shipment of order "LTL-56" for "CONS-A" is "DELIVERED"
    And "CUST-1" has 0 transport charge for trip "TRIP-LH-56"
    And "CUST-1" has 0 transport charge for trip "TRIP-DL-56"
    And the receivable for order "LTL-56" and "CONS-A" totals 2500.00
