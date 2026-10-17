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

  Background:
    Given a tenant "acme" named "Acme Logistics"
    And tenant "acme" has an organisational unit "/acme/" named "Acme HQ" of type "HQ"
    And the tenant scope is "acme" at "/acme/"
    And a trading partner "CUST-1"
    And a trading partner "CUST-2"
    And a trading partner "CONS-A"
    And a trading partner "CONS-B"
    And a depot "HUB-BOM" at 19.10,72.90
    And a depot "HUB-DEL" at 28.60,77.20
    And a depot "HUB-BLR" at 12.95,77.60
    And a depot "HUB-CCU" at 22.55,88.35
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
