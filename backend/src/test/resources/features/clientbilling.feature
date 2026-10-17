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

Feature: A third-party logistics provider bills each client for what it did
  Issue #22.

  One tenant runs transport and warehousing for several clients. Each client
  has its own versioned contract, sees only its own charges and invoices, and
  is invoiced once a month. Three guarantees carry the rest: a charge is
  captured once however often its source is retried, a month is billed once
  however often the run is repeated, and a month that has been billed is closed
  -- whatever arrives later goes on next month's invoice, never on one that
  has already been sent.

  Background:
    Given a tenant "acme" named "Acme Logistics"
    And tenant "acme" has an organisational unit "/acme/" named "Acme HQ" of type "HQ"
    And the tenant scope is "acme" at "/acme/"
    And a trading partner "CLIENT-A"
    And a trading partner "CLIENT-B"
    And client "CLIENT-A" holds contract "A-2026" effective 90 days ago:
      | transport per kg        | 5.00 |
      | transport minimum       | 0    |
      | storage per pallet-day  | 12   |
      | handling in per pallet  | 25   |
      | handling out per pallet | 30   |
      | service LABEL per UNIT  | 2.00 |
    And client "CLIENT-B" holds contract "B-2026" effective 90 days ago:
      | transport per kg        | 6.00 |
      | transport minimum       | 0    |
      | storage per pallet-day  | 15   |
      | handling in per pallet  | 20   |
      | handling out per pallet | 20   |

  # ------------------------------------------------------------ the monthly run

  Scenario: A month of storage and handling becomes one invoice with a line per charge
    Given "CLIENT-A" received 40 pallets into the warehouse on day 2 of last month
    And "CLIENT-A" stored 300 pallet-days on day 5 of last month
    And "CLIENT-A" shipped 35 pallets out of the warehouse on day 20 of last month
    And "CLIENT-A" had 200 units of value-added service "LABEL" on day 21 of last month
    When last month is billed for "CLIENT-A"
    Then "CLIENT-A" has 1 invoice for last month
    And the last-month invoice of "CLIENT-A" totals 6050 over 4 lines
    And the last-month invoice of "CLIENT-A" has a "HANDLING_IN" line of 1000
    And the last-month invoice of "CLIENT-A" has a "STORAGE" line of 3600
    And the last-month invoice of "CLIENT-A" has a "HANDLING_OUT" line of 1050
    And the last-month invoice of "CLIENT-A" has a "VALUE_ADDED" line of 400
    And the last-month invoice of "CLIENT-A" adds up

  Scenario: Each client is billed on its own contract
    Given "CLIENT-A" stored 100 pallet-days on day 5 of last month
    And "CLIENT-B" stored 100 pallet-days on day 5 of last month
    When last month is billed for "CLIENT-A"
    And last month is billed for "CLIENT-B"
    Then the last-month invoice of "CLIENT-A" totals 1200 over 1 lines
    And the last-month invoice of "CLIENT-B" totals 1500 over 1 lines

  Scenario: Running the same month twice yields the same single invoice
    # Retries are normal: the free instance sleeps and the client that woke it
    # retries. A second run must answer with the first run's invoice, not bill
    # the month again.
    Given "CLIENT-A" stored 300 pallet-days on day 5 of last month
    And last month is billed for "CLIENT-A"
    When last month is billed for "CLIENT-A" again
    Then the rerun answered with the same invoice for "CLIENT-A"
    And "CLIENT-A" has 1 invoice for last month
    And the last-month invoice of "CLIENT-A" totals 3600 over 1 lines

  Scenario: Usage resent by the warehouse is charged once
    Given the warehouse reports usage "SNAP-0503" of 10 pallet-days for "CLIENT-A" on day 3 of last month
    When the warehouse resends usage "SNAP-0503" for "CLIENT-A"
    Then "CLIENT-A" has 1 charge for last month

  # ---------------------------------------------------------------- period close

  Scenario: A charge arriving after its month was billed goes on the next invoice
    # The invoice has been sent. Changing it underneath the client would make
    # the copy they hold disagree with ours.
    Given "CLIENT-A" stored 300 pallet-days on day 5 of last month
    And last month is billed for "CLIENT-A"
    When "CLIENT-A" stored 50 pallet-days on day 28 of last month
    Then the last-month invoice of "CLIENT-A" totals 3600 over 1 lines
    And "CLIENT-A" has 1 charge waiting in this month's period, marked late
    And "CLIENT-A" has 1 charge for last month

  Scenario: A month that has not ended cannot be billed
    When this month is billed for "CLIENT-A"
    Then client billing refuses mentioning "has not ended yet"

  Scenario: A month with nothing charged issues no invoice and stays open
    When last month is billed for "CLIENT-A"
    Then client billing refuses mentioning "Nothing was charged"
    And "CLIENT-A" has 0 invoices for last month

  Scenario: A value-added service the contract does not price refuses the run
    Given "CLIENT-B" had 10 units of value-added service "KITTING" on day 4 of last month
    When last month is billed for "CLIENT-B"
    Then client billing refuses mentioning "has no rate for value-added service KITTING"
    And "CLIENT-B" has 0 invoices for last month

  # --------------------------------------------------------------- contracts

  Scenario: The database refuses to edit a published client contract
    When the storage rate on client contract "A-2026" is edited directly
    Then client billing refuses mentioning "publish a new version"

  Scenario: Work done before a new contract version is still priced on the old one
    # The contract is chosen by the day the work was done, not by the day the
    # run happens -- the rule that makes a contract version worth keeping.
    Given client "CLIENT-A" holds contract "A-2026-B" effective 1 days ago:
      | storage per pallet-day | 20 |
    And "CLIENT-A" stored 100 pallet-days on day 5 of last month
    When last month is billed for "CLIENT-A"
    Then the last-month invoice of "CLIENT-A" totals 1200 over 1 lines

  # ---------------------------------------------------------------- isolation

  Scenario: A client's own user sees that client's invoices and is refused another's
    Given "CLIENT-A" stored 100 pallet-days on day 5 of last month
    And "CLIENT-B" stored 100 pallet-days on day 5 of last month
    And last month is billed for "CLIENT-A"
    And last month is billed for "CLIENT-B"
    And the signed-in user is a portal user of client "CLIENT-A"
    When the invoices of "CLIENT-A" are listed
    Then 1 client invoice is listed
    When the invoices of "CLIENT-B" are listed
    Then the client billing request is refused as unauthorised
    And the last-month invoice of "CLIENT-B" cannot be found by the signed-in user

  Scenario: A client's own user never sees the margin
    Given the signed-in user is a portal user of client "CLIENT-A"
    When the client margins for last month are asked for
    Then the client billing request is refused as unauthorised

  Scenario: A client's own user cannot bill
    Given the signed-in user is a portal user of client "CLIENT-A"
    When last month is billed for "CLIENT-A"
    Then the client billing request is refused as unauthorised

  # --------------------------------------------------- transport and margin

  Scenario: A completed trip is charged to the client and set against the carrier's bill
    Given a trading partner "CUST"
    And a trading partner "CONS-A"
    And client "CUST" holds contract "CUST-2026" effective 90 days ago:
      | transport per kg | 5.00 |
    And a depot "ORIGIN" at 19.100,72.900
    And a depot "DEST-A" at 19.300,73.100
    And a carrier "VENDOR-1"
    And a lorry "MH-01-TL-0001" with payload 20000 kg and volume 60 m3
    And lorry "MH-01-TL-0001" has an "INSURANCE" certificate valid for 365 days
    And a lorry driver "Ravi" licensed for 365 days
    And a rate card "WEST-2026" from "ORIGIN" to "DEST-A" for "VENDOR-1" effective 60 days ago:
      | free hours         | 2 |
      | detention per hour | 0 |
      | additional drop    | 0 |
      | minimum charge     | 0 |
    And the card charges 4.00 per kg for any weight
    And a trip "T1" carrying 5000 kg for "VENDOR-1"
    And trip "T1" is run, dispatched 35 days ago after 30 minutes at the origin
    When trip "T1" is completed
    And the completion of trip "T1" is delivered to client billing again
    Then "CUST" was charged transport on 5000 kg for trip "T1"
    And "CUST" has 1 transport charge for trip "T1"
    When the month 35 days ago is billed for "CUST"
    Then the margin on "CUST" for the month 35 days ago is 5000 on a receivable of 25000 and a carrier payable of 20000
