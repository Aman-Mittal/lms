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


@settings
Feature: A customer's own settings win over the tenant's
  Settings are grouped into profiles, one per scope. The value in force for a
  context is folded from the profiles that apply to it, least specific first:
  the organisation default, then organisational units from the root down,
  then the customer. A key no profile sets falls back to the system default.
  The close tolerance is one such setting, and an order is closed by the
  tolerance in force for its own customer.

  Background:
    Given a tenant "acme" named "Acme Logistics"
    And tenant "acme" has an organisational unit "/acme/" named "Acme HQ" of type "HQ"
    And the tenant scope is "acme" at "/acme/"
    And a trading partner "CUST"
    And a trading partner "OTHER"
    And a trading partner "CONS-A"
    And a depot "ORIGIN" at 19.10,72.90
    And a depot "DEST-A" at 19.30,73.10
    And a lorry "MH-01-ST-0001" with payload 10000 kg and volume 40 m3
    And lorry "MH-01-ST-0001" has an "INSURANCE" certificate valid for 365 days
    And a lorry driver "Ravi" licensed for 365 days
    And a carrier "HAULIER"

  Scenario: The customer's tolerance closes its order where the tenant's would not
    Given the tenant's close tolerance is 0 percent
    And customer "CUST" has its own close tolerance of 5 percent
    Then the close tolerance in force for customer "CUST" is 5 percent from its CUSTOMER profile
    And the close tolerance in force for customer "OTHER" is 0 percent from its ORG_DEFAULT profile
    Given an order "ST-1" for "CUST" from "ORIGIN"
    And order "ST-1" has line 1 of 10000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "ST-1" is validated
    And consignments are generated for order "ST-1"
    And the shipment of order "ST-1" is split into parts of 9600 kg and 400 kg
    And a load "LOAD-ST-1" is opened at "ORIGIN" on lorry "MH-01-ST-0001"
    And both parts of the shipment of order "ST-1" are put on load "LOAD-ST-1"
    When the 9600 kg part of order "ST-1" is delivered on proof
    Then order "ST-1" is "CLOSED"

  Scenario: A customer held to no tolerance keeps its order open whatever the tenant allows
    Given the tenant's close tolerance is 5 percent
    And customer "CUST" has its own close tolerance of 0 percent
    And an order "ST-2" for "CUST" from "ORIGIN"
    And order "ST-2" has line 1 of 10000 kg of GENERAL for "CONS-A" at "DEST-A"
    And order "ST-2" is validated
    And consignments are generated for order "ST-2"
    And the shipment of order "ST-2" is split into parts of 9600 kg and 400 kg
    And a load "LOAD-ST-2" is opened at "ORIGIN" on lorry "MH-01-ST-0001"
    And both parts of the shipment of order "ST-2" are put on load "LOAD-ST-2"
    When the 9600 kg part of order "ST-2" is delivered on proof
    Then order "ST-2" is "FULLY_PLANNED"

  Scenario: One profile per customer, and nothing stored that the platform would refuse
    Given customer "CUST" has its own close tolerance of 2 percent
    Then a second settings profile for customer "CUST" is refused with "setting-profile-scope-taken"
    And setting "order.close.tolerancePercent" to "101" for customer "CUST" is refused with "setting-invalid-value"
    And setting "order.close.tolerence" to "1" for customer "CUST" is refused with "setting-unknown"
    And setting "controltower.detector.etaSlipTolerance" to "PT2H" for customer "CUST" is refused with "setting-scope-not-allowed"
    And the close tolerance in force for customer "CUST" is 2 percent from its CUSTOMER profile
