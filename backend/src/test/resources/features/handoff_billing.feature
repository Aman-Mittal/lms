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


Feature: A warehouse hand-off is billed to the client that owns the goods
  A handling unit handed to transport becomes a consignment with no order, so
  no completed trip ever charges it. Client billing therefore charges the
  hand-off itself: handling out (a pallet per unit) and transport (the unit's
  gross weight), at the rates of the contract of the client that owns the SKUs
  inside. A redelivered hand-off charges once, and goods whose owner holds no
  client contract are nobody's to bill.

  Background:
    Given a tenant "acme" named "Acme Logistics"
    And tenant "acme" has an organisational unit "/acme/" named "Acme HQ" of type "HQ"
    And the tenant scope is "acme" at "/acme/"
    And a warehouse "WH-1" at 19.100,72.900
    And warehouse "WH-1" has a bin "A-01" holding at most 50 units
    And a trading partner "CONS-A"
    And a depot "DEST-A" at 19.30,73.10
    And a trading partner "CLIENT-A"
    And a trading partner "CLIENT-B"
    And a trading partner "NOT-A-CLIENT"
    And client "CLIENT-A" holds contract "A-2026" effective 90 days ago:
      | transport per kg        | 5.00 |
      | transport minimum       | 0    |
      | handling out per pallet | 30   |
    And client "CLIENT-B" holds contract "B-2026" effective 90 days ago:
      | transport per kg        | 6.00 |
      | transport minimum       | 0    |
      | handling out per pallet | 20   |

  Scenario: A hand-off is charged on the owner's contract and shows on the invoice and statement
    Given a stock item "A-WIDGET" owned by "CLIENT-A"
    And 30 of "A-WIDGET" have been received into "WH-1" and put away in bin "A-01"
    And an outbound order "OUT-1" from "WH-1" for 30 of "A-WIDGET"
    And outbound order "OUT-1" is picked
    And outbound order "OUT-1" is packed into handling unit "HU-1"
    When handling unit "HU-1" is dispatched as "LOAD-9" to "DEST-A" for "CONS-A" weighing 420 kg
    Then "CLIENT-A" was charged 1 "HANDLING_OUT" for handling unit "HU-1"
    And "CLIENT-A" was charged 420 "TRANSPORT" for handling unit "HU-1"
    And the hand-off charges of "CLIENT-A" are dated day 10 of last month
    When last month is billed for "CLIENT-A"
    Then the invoice of "CLIENT-A" has a "HANDLING_OUT" line of 30
    And the invoice of "CLIENT-A" has a "TRANSPORT" line of 2100
    When the statement of "CLIENT-A" is read 10 entries a page
    Then the statement reads:
      | type    | debit | credit | balance |
      | INVOICE | 2130  | 0      | 2130    |

  Scenario: A redelivered hand-off is charged once
    Given a stock item "A-WIDGET" owned by "CLIENT-A"
    And 30 of "A-WIDGET" have been received into "WH-1" and put away in bin "A-01"
    And an outbound order "OUT-1" from "WH-1" for 30 of "A-WIDGET"
    And outbound order "OUT-1" is picked
    And outbound order "OUT-1" is packed into handling unit "HU-1"
    And handling unit "HU-1" is dispatched as "LOAD-9" to "DEST-A" for "CONS-A" weighing 420 kg
    When the hand-off of handling unit "HU-1" is delivered again
    Then "CLIENT-A" has 2 charges for handling unit "HU-1"
    And the hand-off charges of "CLIENT-A" are dated day 10 of last month
    When last month is billed for "CLIENT-A"
    Then the invoice of "CLIENT-A" has a "HANDLING_OUT" line of 30
    And the invoice of "CLIENT-A" has a "TRANSPORT" line of 2100

  Scenario: Goods whose owner holds no client contract are not billed
    Given a stock item "N-WIDGET" owned by "NOT-A-CLIENT"
    And a stock item "OWN-WIDGET" described as "The tenant's own"
    And 30 of "N-WIDGET" have been received into "WH-1" and put away in bin "A-01"
    And 10 of "OWN-WIDGET" have been received into "WH-1" and put away in bin "A-01"
    And an outbound order "OUT-1" from "WH-1" for 30 of "N-WIDGET" and 10 of "OWN-WIDGET"
    And outbound order "OUT-1" is picked
    And outbound order "OUT-1" is packed into handling unit "HU-1"
    When handling unit "HU-1" is dispatched as "LOAD-9" to "DEST-A" for "CONS-A" weighing 420 kg
    Then handling unit "HU-1" became one consignment for "CONS-A" at "DEST-A" carrying 420 kg
    And no client was charged for handling unit "HU-1"

  Scenario: A unit holding two clients' goods is split between them by units held
    Given a stock item "A-WIDGET" owned by "CLIENT-A"
    And a stock item "B-WIDGET" owned by "CLIENT-B"
    And 30 of "A-WIDGET" have been received into "WH-1" and put away in bin "A-01"
    And 10 of "B-WIDGET" have been received into "WH-1" and put away in bin "A-01"
    And an outbound order "OUT-1" from "WH-1" for 30 of "A-WIDGET" and 10 of "B-WIDGET"
    And outbound order "OUT-1" is picked
    And outbound order "OUT-1" is packed into handling unit "HU-1"
    When handling unit "HU-1" is dispatched as "LOAD-9" to "DEST-A" for "CONS-A" weighing 400 kg
    Then "CLIENT-A" was charged 0.75 "HANDLING_OUT" for handling unit "HU-1"
    And "CLIENT-A" was charged 300 "TRANSPORT" for handling unit "HU-1"
    And "CLIENT-B" was charged 0.25 "HANDLING_OUT" for handling unit "HU-1"
    And "CLIENT-B" was charged 100 "TRANSPORT" for handling unit "HU-1"

  Scenario: A unit that leaves some other way than to transport is not billed
    Given a stock item "A-WIDGET" owned by "CLIENT-A"
    And 30 of "A-WIDGET" have been received into "WH-1" and put away in bin "A-01"
    And an outbound order "OUT-1" from "WH-1" for 30 of "A-WIDGET"
    And outbound order "OUT-1" is picked
    And outbound order "OUT-1" is packed into handling unit "HU-1"
    When handling unit "HU-1" is dispatched as "COLLECTED"
    Then no client was charged for handling unit "HU-1"
