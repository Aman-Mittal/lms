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

  # -------------------------------------------------------------------- tax

  Scenario: Tax is charged on each line at the contract's warehouse rate and rounded on that line
    # Three labels at 0.25 are three lines of 0.045 tax each, rounded to 0.05:
    # 0.15 in all, not 18 % of 0.75 rounded once (0.14). The client can check
    # every line on its own.
    Given a trading partner "CLIENT-T"
    And client "CLIENT-T" holds contract "T-2026" effective 90 days ago:
      | storage per pallet-day | 12   |
      | handling in per pallet | 25   |
      | service LABEL per UNIT | 0.25 |
      | transport tax percent  | 5    |
      | warehouse tax percent  | 18   |
    And "CLIENT-T" stored 300 pallet-days on day 5 of last month
    And "CLIENT-T" had 1 units of value-added service "LABEL" on day 6 of last month
    And "CLIENT-T" had 1 units of value-added service "LABEL" on day 7 of last month
    And "CLIENT-T" had 1 units of value-added service "LABEL" on day 8 of last month
    When last month is billed for "CLIENT-T"
    Then the last-month invoice of "CLIENT-T" has a taxable amount of 3600.75, tax of 648.15 and a total of 4248.90
    And every line of the last-month invoice of "CLIENT-T" carries its own tax, rounded half-up

  # ----------------------------------------------------------- credit notes

  Scenario: A credit note corrects an issued invoice without changing it
    Given a trading partner "CLIENT-T"
    And client "CLIENT-T" holds contract "T-2026" effective 90 days ago:
      | storage per pallet-day | 12 |
      | handling in per pallet | 25 |
      | warehouse tax percent  | 18 |
    And "CLIENT-T" received 40 pallets into the warehouse on day 2 of last month
    And "CLIENT-T" stored 300 pallet-days on day 5 of last month
    And last month is billed for "CLIENT-T"
    When a credit note for 500 is issued against the last-month invoice of "CLIENT-T" because "Twenty pallets were stored on the client's own racking"
    # 500 spread over 1000 and 3600 in proportion: 108.70 and 391.30, whose
    # tax at 18 % is 19.57 and 70.43.
    Then the credit note credits 500 plus 90 tax
    And the last-month invoice of "CLIENT-T" has a taxable amount of 4600, tax of 828 and a total of 5428
    And the last-month invoice of "CLIENT-T" shows 590 credited
    And the credit note is in the audit trail with its reason
    And the margin on "CLIENT-T" for last month is a receivable of 4100 after 500 credited

  Scenario: A credit note can never take back more than the invoice charged
    Given a trading partner "CLIENT-T"
    And client "CLIENT-T" holds contract "T-2026" effective 90 days ago:
      | storage per pallet-day | 12 |
      | warehouse tax percent  | 18 |
    And "CLIENT-T" stored 100 pallet-days on day 5 of last month
    And last month is billed for "CLIENT-T"
    And a credit note for 1000 is issued against the last-month invoice of "CLIENT-T" because "Rate dispute"
    When a credit note for 200.01 is issued against the last-month invoice of "CLIENT-T" because "Second thoughts"
    Then client billing refuses mentioning "200.00 left to credit"
    When a credit note for line 1 in full is issued against the last-month invoice of "CLIENT-T" because "The rest of the line"
    Then the credit note credits 200 plus 36 tax
    And the last-month invoice of "CLIENT-T" shows 1416 credited
    When a credit note for 0.01 is issued against the last-month invoice of "CLIENT-T" because "One cent more"
    Then client billing refuses mentioning "left to credit"
    When a credit note for line 1 in full is issued against the last-month invoice of "CLIENT-T" because "Once more"
    Then client billing refuses mentioning "already been credited in full"

  Scenario: Issuing a credit note is its own permission
    Given "CLIENT-A" stored 100 pallet-days on day 5 of last month
    And last month is billed for "CLIENT-A"
    And the signed-in user lacks "CLIENT_CREDIT_NOTE_ISSUE"
    When a credit note for 100 is issued against the last-month invoice of "CLIENT-A" because "Goodwill"
    Then the client billing request is refused as unauthorised

  # -------------------------------------------------------------- statement

  Scenario: The statement shows invoices and credit notes with a running balance across pages
    Given a trading partner "CLIENT-T"
    And client "CLIENT-T" holds contract "T-2026" effective 100 days ago:
      | storage per pallet-day | 12 |
      | warehouse tax percent  | 18 |
    And "CLIENT-T" stored 100 pallet-days on day 5 of the month before last
    And the month before last is billed for "CLIENT-T"
    And "CLIENT-T" stored 50 pallet-days on day 5 of last month
    And last month is billed for "CLIENT-T"
    And a credit note for 100 is issued against the last-month invoice of "CLIENT-T" because "Goodwill"
    When the statement of "CLIENT-T" is read 2 entries a page
    Then the statement reads:
      | type        | debit | credit | balance |
      | INVOICE     | 1416  | 0      | 1416    |
      | INVOICE     | 708   | 0      | 2124    |
      | CREDIT_NOTE | 0     | 118    | 2006    |

  Scenario: A payment is a credit on the statement, and a client who overpays is in credit
    Given a trading partner "CLIENT-P"
    And client "CLIENT-P" holds contract "P-2026" effective 100 days ago:
      | storage per pallet-day | 12 |
      | warehouse tax percent  | 18 |
    And "CLIENT-P" stored 100 pallet-days on day 5 of last month
    And last month is billed for "CLIENT-P"
    When a payment of 1000 from "CLIENT-P" arrives referenced "NEFT-1"
    And a payment of 500 from "CLIENT-P" arrives referenced "NEFT-2"
    And the statement of "CLIENT-P" is read 2 entries a page
    Then the statement reads:
      | type    | debit | credit | balance |
      | INVOICE | 1416  | 0      | 1416    |
      | PAYMENT | 0     | 1000   | 416     |
      | PAYMENT | 0     | 500    | -84     |

  Scenario: A remittance is recorded once, and only for a client who has been invoiced
    Given a trading partner "CLIENT-Q"
    And client "CLIENT-Q" holds contract "Q-2026" effective 100 days ago:
      | storage per pallet-day | 12 |
    When a payment of 100 from "CLIENT-Q" arrives referenced "NEFT-9"
    Then client billing refuses mentioning "has not been invoiced"
    Given "CLIENT-Q" stored 10 pallet-days on day 5 of last month
    And last month is billed for "CLIENT-Q"
    When a payment of 100 from "CLIENT-Q" arrives referenced "NEFT-9"
    Then the payment is recorded in the audit trail
    When a payment of 100 from "CLIENT-Q" arrives referenced "NEFT-9"
    Then client billing refuses mentioning "already recorded"
    When a payment of 0 from "CLIENT-Q" arrives referenced "NEFT-10"
    Then client billing refuses mentioning "more than nothing"

  Scenario: Recording a payment is billing-desk work
    Given a trading partner "CLIENT-R"
    And client "CLIENT-R" holds contract "R-2026" effective 100 days ago:
      | storage per pallet-day | 12 |
    And "CLIENT-R" stored 10 pallet-days on day 5 of last month
    And last month is billed for "CLIENT-R"
    And the signed-in user lacks "CLIENT_BILLING_RUN"
    When a payment of 100 from "CLIENT-R" arrives referenced "NEFT-1"
    Then the client billing request is refused as unauthorised

  # -------------------------------------------------- storage from the ledger

  Scenario: Storage is computed from the stock the client owns in the warehouse, once
    # A pallet here is ten units of the client's SKUs, and a part pallet takes a
    # whole position: 95 units are 10 pallets for days 1-10, 50 units are 5
    # pallets for days 11-20, then nothing. 10 x 10 + 10 x 5 = 150 pallet-days.
    # Another client's stock in the same warehouse is not this client's bill.
    Given a warehouse "WH-1" at 19.100,72.900
    And a trading partner "CLIENT-L"
    And client "CLIENT-L" holds contract "L-2026" effective 90 days ago:
      | storage per pallet-day   | 12 |
      | storage units per pallet | 10 |
    And stock item "L-WIDGET" belongs to client "CLIENT-L"
    And stock item "B-GADGET" belongs to client "CLIENT-B"
    And 95 units of "L-WIDGET" came into "WH-1" on day 1 of last month
    And 500 units of "B-GADGET" came into "WH-1" on day 1 of last month
    And 45 units of "L-WIDGET" left "WH-1" on day 11 of last month
    And 50 units of "L-WIDGET" left "WH-1" on day 21 of last month
    When storage for last month is computed from the warehouse ledger for "CLIENT-L"
    And storage for last month is computed from the warehouse ledger for "CLIENT-L" again
    Then the ledger storage of "CLIENT-L" for last month is 150 pallet-days from 1450 unit-days
    And "CLIENT-L" has 1 charge for last month
    When last month is billed for "CLIENT-L"
    Then the last-month invoice of "CLIENT-L" has a "STORAGE" line of 1800

  Scenario: Stock received before the month is held from its first day
    Given a warehouse "WH-1" at 19.100,72.900
    And a trading partner "CLIENT-L"
    And client "CLIENT-L" holds contract "L-2026" effective 100 days ago:
      | storage per pallet-day   | 12 |
      | storage units per pallet | 10 |
    And stock item "L-WIDGET" belongs to client "CLIENT-L"
    And 100 units of "L-WIDGET" came into "WH-1" on day 20 of the month before last
    And 100 units of "L-WIDGET" left "WH-1" on day 11 of last month
    When storage for last month is computed from the warehouse ledger for "CLIENT-L"
    Then the ledger storage of "CLIENT-L" for last month is 100 pallet-days from 1000 unit-days

  Scenario: Storage is recorded by hand or computed from the ledger, never both
    Given a warehouse "WH-1" at 19.100,72.900
    And stock item "A-WIDGET" belongs to client "CLIENT-A"
    And 10 units of "A-WIDGET" came into "WH-1" on day 1 of last month
    And "CLIENT-A" stored 10 pallet-days on day 3 of last month
    When storage for last month is computed from the warehouse ledger for "CLIENT-A"
    Then client billing refuses mentioning "already recorded by hand"
    Given a trading partner "CLIENT-L"
    And client "CLIENT-L" holds contract "L-2026" effective 90 days ago:
      | storage per pallet-day | 12 |
    And stock item "L-WIDGET" belongs to client "CLIENT-L"
    And 10 units of "L-WIDGET" came into "WH-1" on day 1 of last month
    And storage for last month is computed from the warehouse ledger for "CLIENT-L"
    When "CLIENT-L" stored 10 pallet-days on day 3 of last month
    Then client billing refuses mentioning "already computed from the warehouse ledger"

  Scenario: Quarantined stock counts as held until it is written off
    # Damaged goods in quarantine are still in the building, occupying a
    # position. A release to a bin moves them without changing what is held;
    # only a write-off takes them out. 10 held on days 1-20, 4 after the
    # write-off of 6 on day 21 (the other 4 were released to a bin on day 11).
    Given a warehouse "WH-1" at 19.100,72.900
    And warehouse "WH-1" has a bin "A-01" holding at most 50 units
    And a trading partner "CLIENT-L"
    And client "CLIENT-L" holds contract "L-2026" effective 90 days ago:
      | storage per pallet-day   | 12 |
      | storage units per pallet | 5  |
    And stock item "L-WIDGET" belongs to client "CLIENT-L"
    And 10 damaged units of "L-WIDGET" were quarantined at "WH-1" on day 1 of last month
    And 4 quarantined units of "L-WIDGET" at "WH-1" were released to bin "A-01" on day 11 of last month
    And 6 quarantined units of "L-WIDGET" at "WH-1" were written off on day 21 of last month
    When storage for last month is computed from the warehouse ledger for "CLIENT-L"
    Then the warehouse ledger shows 10 units held for "CLIENT-L" on day 1 of last month
    And the warehouse ledger shows 10 units held for "CLIENT-L" on day 11 of last month
    And the warehouse ledger shows 10 units held for "CLIENT-L" on day 20 of last month
    And the warehouse ledger shows 4 units held for "CLIENT-L" on day 21 of last month
