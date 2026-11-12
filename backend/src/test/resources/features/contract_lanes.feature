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

Feature: An order is placed under the client's contract and travels on one of its lanes
  Issue #219.

  A client contract version carries lanes: where its freight moves, how it is
  priced and how many days it takes. An order names the contract version in
  force on its date and, once its lines say where freight goes, the lane that
  serves it. An order no lane serves is accepted as a direct order when the
  contract allows direct orders, and refused by name when it does not. A
  customer with no contract is raised for and validated exactly as before. A
  setting can be kept for the contract itself, and it beats the customer's.

  Background:
    Given a tenant "acme" named "Acme Logistics"
    And tenant "acme" has an organisational unit "/acme/" named "Acme HQ" of type "HQ"
    And the tenant scope is "acme" at "/acme/"
    And the tenant asks for no verification of contracts
    And a trading partner "CLIENT-A"
    And a trading partner "BUYER"
    And a depot "MUMBAI" at 19.10,72.90
    And a depot "DELHI" at 28.40,77.00
    And a depot "PUNE" at 18.52,73.86

  Scenario: An order is placed on the lane that serves it, with the days that lane promises
    Given contract "C-1" for "CLIENT-A" is in force with a lane "MUM-DEL" from "MUMBAI" to "DELHI" at 9000 per tonne in 4 days
    And an order "O-1" for "CLIENT-A" from "MUMBAI"
    And order "O-1" has line 1 of 100 kg of GENERAL for "BUYER" at "DELHI"
    When order "O-1" is checked against its contract
    Then order "O-1" was checked and passed
    And order "O-1" was resolved against contract "C-1"
    And order "O-1" travels on lane "MUM-DEL"
    And order "O-1" is promised in 4 days

  Scenario: An order no lane serves is refused when the contract refuses direct orders
    Given contract "C-1" for "CLIENT-A" refusing direct orders is in force with a lane "MUM-DEL" from "MUMBAI" to "DELHI" at 9000 per tonne in 4 days
    And an order "O-2" for "CLIENT-A" from "MUMBAI"
    And order "O-2" has line 1 of 100 kg of GENERAL for "BUYER" at "PUNE"
    When order "O-2" is checked against its contract
    Then the contract check is refused with "order-outside-contract-lanes"
    And order "O-2" is "DRAFT"

  Scenario: An order no lane serves is accepted as a direct order when the contract allows them
    Given contract "C-1" for "CLIENT-A" is in force with a lane "MUM-DEL" from "MUMBAI" to "DELHI" at 9000 per tonne in 4 days
    And an order "O-3" for "CLIENT-A" from "MUMBAI"
    And order "O-3" has line 1 of 100 kg of GENERAL for "BUYER" at "PUNE"
    When order "O-3" is checked against its contract
    Then order "O-3" was checked and passed
    And order "O-3" was resolved against contract "C-1"
    And order "O-3" travels on no lane

  Scenario: A customer with no contract is raised for and validated as before
    Given an order "O-4" for "BUYER" from "MUMBAI"
    And order "O-4" has line 1 of 100 kg of GENERAL for "CLIENT-A" at "DELHI"
    When order "O-4" is checked against its contract
    Then order "O-4" was checked and passed
    And order "O-4" has no contract
    And order "O-4" travels on no lane

  Scenario: A setting kept for the contract beats the one kept for the tenant
    Given contract "C-1" for "CLIENT-A" is in force with a lane "MUM-DEL" from "MUMBAI" to "DELHI" at 9000 per tonne in 4 days
    And a contract profile for "C-1" sets the close tolerance to 8 percent
    Then the close tolerance for contract "C-1" is 8 percent from "Contract: {unique}"
    And the close tolerance for no contract is 0 percent
