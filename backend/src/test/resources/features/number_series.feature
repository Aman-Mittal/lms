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

@number-series
Feature: A tenant decides how its documents are numbered
  A number series numbers one type of document, for the whole tenant or for
  one organisational unit and everything beneath it; the series of the deepest
  unit covering the document wins. Numbers carry an optional financial-year
  code and restart every financial year. A number given back on cancellation
  is logged, and handed out again first only by a series that reuses numbers.
  With no series configured, documents keep the numbering they always had.

  Background:
    Given a tenant "acme" named "Acme Logistics"
    And tenant "acme" has an organisational unit "/acme/" named "Acme HQ" of type "HQ"
    And tenant "acme" has an organisational unit "/acme/north/" named "North" of type "REGION"
    And tenant "acme" has an organisational unit "/acme/north/delhi/" named "Delhi" of type "BRANCH"
    And the tenant scope is "acme" at "/acme/"
    And a trading partner "CUST"
    And a trading partner "CONS-A"
    And a depot "ORIGIN" at 19.10,72.90
    And a depot "DEST-A" at 19.30,73.10

  Scenario: Orders take the most specific active series, and fall back when it is switched off
    Given an order "PLAIN" is raised at "/acme/" with no number
    Then order "PLAIN" carries this year's built-in number 1
    Given a number series "so" numbers ORDER for the whole tenant as "SO" with 4 digits and no year
    And a number series "so-delhi" numbers ORDER for "/acme/north/delhi/" as "DEL" with 4 digits and no year
    When an order "HQ-1" is raised at "/acme/" with no number
    And an order "BR-1" is raised at "/acme/north/delhi/" with no number
    And an order "NO-1" is raised at "/acme/north/" with no number
    Then order "HQ-1" carries number "SO-0001"
    And order "BR-1" carries number "DEL-0001"
    And order "NO-1" carries number "SO-0002"
    When number series "so-delhi" is deactivated
    And an order "BR-2" is raised at "/acme/north/delhi/" with no number
    Then order "BR-2" carries number "SO-0003"

  Scenario: A second active series for the same type and scope is refused
    Given a number series "lr" numbers CONSIGNMENT_NOTE for the whole tenant as "LR" with 5 digits and no year
    When a number series "lr-2" is set up numbering CONSIGNMENT_NOTE for the whole tenant as "LRB"
    Then setting up the series is refused with "number-series-scope-taken"
    When a number series "lr-3" is set up numbering CONSIGNMENT_NOTE for the whole tenant as "LR"
    Then setting up the series is refused with "number-series-prefix-taken"

  Scenario: Released numbers are logged, and reused first only where the series says so
    Given a number series "gp" numbers GATE_PASS for the whole tenant as "GP" with 3 digits and no year
    And a number series "ld" numbers LOAD for the whole tenant as "LD" with 3 digits and no year, reusing released numbers
    And 3 numbers have been issued from series "gp"
    And 3 numbers have been issued from series "ld"
    When number "GP-002" of GATE_PASS is given back because "pass torn"
    And number "LD-002" of LOAD is given back because "load undone"
    Then the next number of series "gp" is "GP-004"
    And the next number of series "ld" is "LD-002"
    And the next number of series "ld" is "LD-004"
    And number "GP-002" of GATE_PASS verifies as RELEASED in series "gp"
    And number "LD-002" of LOAD verifies as ISSUED in series "ld"
    And number "GP-009" of GATE_PASS verifies as NOT_ISSUED in series "gp"
    And number "XX-001" of GATE_PASS belongs to no series
    And giving back number "GP-002" of series "gp" again is refused with "number-already-released"

  Scenario: The counter restarts on the first day of the financial year
    Given a number series "cn" numbers CONSIGNMENT_NOTE for the whole tenant as "CN" with 5 digits and the start year, the year starting in month 4
    When a consignment note is numbered on "2027-03-31"
    And a consignment note is numbered on "2027-03-31"
    And a consignment note is numbered on "2027-04-01"
    Then the consignment notes were numbered "CN-2026-00001, CN-2026-00002, CN-2027-00001"

  Scenario: Lorry receipts stay random until the tenant opts into a consignment-note series
    Given an order "LR-1" for "CUST" from "ORIGIN"
    And order "LR-1" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "LR-1" is validated
    When consignments are generated for order "LR-1"
    Then the consignment of order "LR-1" has a random "LR-" reference
    Given a number series "cn" numbers CONSIGNMENT_NOTE for the whole tenant as "CN" with 6 digits and no year
    And an order "LR-2" for "CUST" from "ORIGIN"
    And order "LR-2" has line 1 of 1000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "LR-2" is validated
    When consignments are generated for order "LR-2"
    Then the consignment of order "LR-2" is numbered "CN-000001"

  Scenario: Administering number series needs its own right
    Given the signed-in user lacks "NUMBER_SERIES_MANAGE"
    When a number series "x" is set up numbering ORDER for the whole tenant as "X"
    Then setting up the series is refused as not permitted
