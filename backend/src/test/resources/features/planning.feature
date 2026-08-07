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

Feature: Demand becomes executable freight
  Vision document 3.3 and 3.4.

  The commercial half of the platform meeting the physical half: an order is a
  promise, a consignment is a tracking reference a customer can quote, and a
  load is a lorry with a weight on it. Every rule here decides whether a
  vehicle leaves the yard carrying something it should not be carrying.

  These scenarios run through the real command services against a real
  database, so the row-level security policies, the unique constraints and the
  capacity CHECK constraints of V6 are all in force -- the domain rules are
  proved to hold where they will actually be enforced.

  Background:
    Given a tenant "acme" named "Acme Logistics"
    And tenant "acme" has an organisational unit "/acme/" named "Acme HQ" of type "HQ"
    And the tenant scope is "acme" at "/acme/"
    And a trading partner "CUST"
    And a trading partner "CONS-A"
    And a trading partner "CONS-B"
    And a depot "ORIGIN" at 19.10,72.90
    And a depot "DEST-A" at 19.30,73.10
    And a depot "DEST-B" at 19.50,73.30

  # --------------------------------------------------------------- 3.3

  Scenario: An order with compatible goods passes validation
    Given an order "SO-1" for "CUST" from "ORIGIN"
    And order "SO-1" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    When order "SO-1" is validated
    Then order "SO-1" is "VALIDATED"

  Scenario: Food and toxic goods to the same destination are refused
    # The named example from 3.3. They would be grouped into one consignment,
    # which means one vehicle, which means contaminated food.
    Given an order "SO-2" for "CUST" from "ORIGIN"
    And order "SO-2" has line 1 of 500 kg of FOOD for "CONS-A" at "DEST-A"
    And order "SO-2" has line 2 of 200 kg of TOXIC UN "UN2810" for "CONS-A" at "DEST-A"
    When order "SO-2" is validated
    Then validation is refused mentioning "must not travel with"
    And order "SO-2" is "DRAFT"

  Scenario: Food and toxic goods to different destinations are allowed
    # They will never share a transport unit, so refusing this order would be
    # the system inventing a rule the business does not have.
    Given an order "SO-3" for "CUST" from "ORIGIN"
    And order "SO-3" has line 1 of 500 kg of FOOD for "CONS-A" at "DEST-A"
    And order "SO-3" has line 2 of 200 kg of TOXIC UN "UN2810" for "CONS-B" at "DEST-B"
    When order "SO-3" is validated
    Then order "SO-3" is "VALIDATED"

  Scenario: Dangerous goods without a UN number are refused
    Given an order "SO-4" for "CUST" from "ORIGIN"
    And order "SO-4" has line 1 of 300 kg of FLAMMABLE for "CONS-A" at "DEST-A"
    When order "SO-4" is validated
    Then validation is refused mentioning "carries no UN number"

  Scenario: A UN number on ordinary cargo is refused
    # The mirror image, and a real data-entry error: a UN number keyed against
    # the wrong line would otherwise let undeclared goods through downstream
    # checks that trust the class.
    Given an order "SO-5" for "CUST" from "ORIGIN"
    And order "SO-5" has line 1 of 300 kg of GENERAL UN "UN1203" for "CONS-A" at "DEST-A"
    When order "SO-5" is validated
    Then validation is refused mentioning "not a dangerous goods class"

  Scenario: Every problem is reported at once
    # A planner fixing a fifty-line order one rejection at a time is how people
    # end up entering orders outside the system.
    Given an order "SO-6" for "CUST" from "ORIGIN"
    And order "SO-6" has line 1 of 300 kg of FLAMMABLE for "CONS-A" at "DEST-A"
    And order "SO-6" has line 2 of 300 kg of CHEMICAL for "CONS-B" at "DEST-B"
    When order "SO-6" is validated
    Then validation is refused mentioning "line 1"
    And validation is refused mentioning "line 2"

  Scenario: A validated order can no longer be amended
    Given an order "SO-7" for "CUST" from "ORIGIN"
    And order "SO-7" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "SO-7" is validated
    When order "SO-7" takes a further line 2 of 500 kg of GENERAL for "CONS-A" at "DEST-A"
    Then the amendment is refused

  # ------------------------------------------------------------- 3.4.1

  Scenario: Lines are grouped into one consignment per consignee per destination
    Given an order "SO-10" for "CUST" from "ORIGIN"
    And order "SO-10" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "SO-10" has line 2 of 1500 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "SO-10" has line 3 of 800 kg of GENERAL for "CONS-B" at "DEST-B"
    And order "SO-10" is validated
    When consignments are generated for order "SO-10"
    Then 2 consignments are created
    And the consignment for "CONS-A" at "DEST-A" carries 2500 kg
    And the consignment for "CONS-B" at "DEST-B" carries 800 kg
    And order "SO-10" is "FULLY_PLANNED"

  Scenario: Generating consignments twice creates nothing the second time
    # Idempotency by construction rather than by an idempotency key: only
    # unplanned lines are drawn, and each is marked as it is taken. A retried
    # request after a timeout must not produce a second set of lorry receipts.
    Given an order "SO-11" for "CUST" from "ORIGIN"
    And order "SO-11" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "SO-11" is validated
    And consignments are generated for order "SO-11"
    When consignments are generated for order "SO-11"
    Then 0 consignments are created
    And order "SO-11" has 1 consignment in total

  Scenario: A consignment bills on volumetric weight when the freight is bulky
    # 8 m3 weighing 100 kg: 8,000,000 cm3 / 5000 = 1600 kg chargeable. Billing
    # this on dead weight would give the vehicle away.
    Given an order "SO-12" for "CUST" from "ORIGIN"
    And order "SO-12" has line 1 of 100 kg of GENERAL measuring 2.0 by 2.0 by 2.0 m for "CONS-A" at "DEST-A"
    And order "SO-12" is validated
    When consignments are generated for order "SO-12"
    Then the consignment for "CONS-A" at "DEST-A" is chargeable at 1600 kg

  Scenario: An unvalidated order yields no consignments
    Given an order "SO-13" for "CUST" from "ORIGIN"
    And order "SO-13" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    When consignments are generated for order "SO-13"
    Then 0 consignments are created

  # ------------------------------------------------------------- 3.4.2

  Scenario: Consignments are aggregated onto a load
    Given a lorry "MH-01-AA-1111" with payload 10000 kg and volume 40 m3
    And an order "SO-20" for "CUST" from "ORIGIN"
    And order "SO-20" has line 1 of 3000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "SO-20" has line 2 of 2000 kg of GENERAL for "CONS-B" at "DEST-B"
    And order "SO-20" is validated
    And consignments are generated for order "SO-20"
    And a load "LOAD-20" is opened at "ORIGIN" on lorry "MH-01-AA-1111"
    When the consignment for "CONS-A" at "DEST-A" is assigned to load "LOAD-20"
    And the consignment for "CONS-B" at "DEST-B" is assigned to load "LOAD-20"
    Then load "LOAD-20" carries 5000 kg
    And load "LOAD-20" is 50.00 percent utilised by weight
    And load "LOAD-20" holds 2 consignments

  Scenario: A load that would exceed the vehicle's payload is refused
    Given a lorry "MH-01-AA-2222" with payload 4000 kg and volume 40 m3
    And an order "SO-21" for "CUST" from "ORIGIN"
    And order "SO-21" has line 1 of 3000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "SO-21" has line 2 of 2000 kg of GENERAL for "CONS-B" at "DEST-B"
    And order "SO-21" is validated
    And consignments are generated for order "SO-21"
    And a load "LOAD-21" is opened at "ORIGIN" on lorry "MH-01-AA-2222"
    And the consignment for "CONS-A" at "DEST-A" is assigned to load "LOAD-21"
    When the consignment for "CONS-B" at "DEST-B" is assigned to load "LOAD-21"
    Then assignment is refused mentioning "weight capacity"
    And load "LOAD-21" carries 3000 kg

  Scenario: A consignment cannot be booked onto two loads
    # Double-booked freight is billed twice and loaded once. The unique
    # constraint on consignment_id is what makes this impossible under
    # concurrency, not the status check in front of it.
    Given a lorry "MH-01-AA-3333" with payload 10000 kg and volume 40 m3
    And a lorry "MH-01-AA-4444" with payload 10000 kg and volume 40 m3
    And an order "SO-22" for "CUST" from "ORIGIN"
    And order "SO-22" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "SO-22" is validated
    And consignments are generated for order "SO-22"
    And a load "LOAD-22A" is opened at "ORIGIN" on lorry "MH-01-AA-3333"
    And a load "LOAD-22B" is opened at "ORIGIN" on lorry "MH-01-AA-4444"
    And the consignment for "CONS-A" at "DEST-A" is assigned to load "LOAD-22A"
    When the consignment for "CONS-A" at "DEST-A" is assigned to load "LOAD-22B"
    Then assignment is refused mentioning "cannot be added to a load"

  Scenario: Dangerous goods are refused on an uncertified lorry
    Given a lorry "MH-01-AA-5555" with payload 10000 kg and volume 40 m3
    And an order "SO-23" for "CUST" from "ORIGIN"
    And order "SO-23" has line 1 of 500 kg of FLAMMABLE UN "UN1203" for "CONS-A" at "DEST-A"
    And order "SO-23" is validated
    And consignments are generated for order "SO-23"
    And a load "LOAD-23" is opened at "ORIGIN" on lorry "MH-01-AA-5555"
    When the consignment for "CONS-A" at "DEST-A" is assigned to load "LOAD-23"
    Then assignment is refused mentioning "not certified"

  Scenario: Dangerous goods travel on a certified lorry
    Given a hazmat-certified lorry "MH-01-AA-6666" with payload 10000 kg and volume 40 m3
    And an order "SO-24" for "CUST" from "ORIGIN"
    And order "SO-24" has line 1 of 500 kg of FLAMMABLE UN "UN1203" for "CONS-A" at "DEST-A"
    And order "SO-24" is validated
    And consignments are generated for order "SO-24"
    And a load "LOAD-24" is opened at "ORIGIN" on lorry "MH-01-AA-6666"
    When the consignment for "CONS-A" at "DEST-A" is assigned to load "LOAD-24"
    Then load "LOAD-24" carries 500 kg
    And load "LOAD-24" requires hazmat handling

  Scenario: Incompatible consignments are refused on the same lorry
    # The compatibility check has to run again here. Each consignment is
    # internally compatible and each passed order validation; it is putting
    # them on one vehicle that creates the hazard, and only planning sees that.
    Given a hazmat-certified lorry "MH-01-AA-7777" with payload 10000 kg and volume 40 m3
    And an order "SO-25" for "CUST" from "ORIGIN"
    And order "SO-25" has line 1 of 500 kg of FOOD for "CONS-A" at "DEST-A"
    And order "SO-25" has line 2 of 500 kg of TOXIC UN "UN2810" for "CONS-B" at "DEST-B"
    And order "SO-25" is validated
    And consignments are generated for order "SO-25"
    And a load "LOAD-25" is opened at "ORIGIN" on lorry "MH-01-AA-7777"
    And the consignment for "CONS-A" at "DEST-A" is assigned to load "LOAD-25"
    When the consignment for "CONS-B" at "DEST-B" is assigned to load "LOAD-25"
    Then assignment is refused mentioning "must not travel with"

  Scenario: An empty load cannot be planned
    Given a lorry "MH-01-AA-8888" with payload 10000 kg and volume 40 m3
    And a load "LOAD-26" is opened at "ORIGIN" on lorry "MH-01-AA-8888"
    When load "LOAD-26" is planned
    Then planning the load is refused mentioning "no consignments"

  Scenario: A built load is closed and offered for sourcing
    Given a lorry "MH-01-AA-9999" with payload 10000 kg and volume 40 m3
    And an order "SO-27" for "CUST" from "ORIGIN"
    And order "SO-27" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "SO-27" is validated
    And consignments are generated for order "SO-27"
    And a load "LOAD-27" is opened at "ORIGIN" on lorry "MH-01-AA-9999"
    And the consignment for "CONS-A" at "DEST-A" is assigned to load "LOAD-27"
    When load "LOAD-27" is planned
    Then load "LOAD-27" is "PLANNED"

  Scenario: A load cannot be built against a vehicle in maintenance
    Given a lorry "MH-01-BB-1111" with payload 10000 kg and volume 40 m3
    And lorry "MH-01-BB-1111" is taken off the road
    When a load "LOAD-28" is opened at "ORIGIN" on lorry "MH-01-BB-1111"
    Then opening the load is refused mentioning "not available"
