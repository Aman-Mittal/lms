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

Feature: A permission says what kind of thing; a policy says how far
  Vision document 3.1, after NIST SP 800-162.

  A permission code answers "may this user approve invoices at all". It cannot
  answer "up to how much", "in which branch" or "whose invoices". Those are
  decided by attribute-based policies layered on top: rows a tenant
  administers, read against the user's attributes (carried in their token) and
  the record's. Every tenant starts with defaults that only bite on an
  attribute a user actually carries -- so a user with no limit, no partner and
  no mode list can do exactly what their permissions allowed before.

  Background:
    Given a tenant "acme" named "Acme Logistics"
    And tenant "acme" has an organisational unit "/acme/" named "Acme HQ" of type "HQ"
    And the tenant scope is "acme" at "/acme/"
    And a trading partner "CUST"
    And a trading partner "CONS-A"
    And a depot "ORIGIN" at 19.100,72.900
    And a depot "DEST-A" at 19.300,73.100
    And a carrier "VENDOR-1"
    And a lorry "MH-01-TL-0001" with payload 20000 kg and volume 60 m3
    And lorry "MH-01-TL-0001" has an "INSURANCE" certificate valid for 365 days
    And a lorry driver "Ravi" licensed for 365 days
    And a rate card "WEST-2026" from "ORIGIN" to "DEST-A" for "VENDOR-1" effective 30 days ago:
      | free hours         | 2   |
      | detention per hour | 300 |
      | additional drop    | 750 |
      | minimum charge     | 0   |
    And the card charges 4.00 per kg for any weight

  # ------------------------------------------------------ the defaults

  Scenario: Every tenant starts with the default policies
    Then the tenant has access policy "invoice-approval-limit" on "INVOICE_APPROVE"
    And the tenant has access policy "exception-close-holding-limit" on "EXCEPTION_CLOSE"
    And the tenant has access policy "partner-write-org-subtree" on "PARTNER_CREATE"
    And the tenant has access policy "vehicle-write-org-subtree" on "VEHICLE_CREATE"
    And the tenant has access policy "order-write-org-subtree" on "ORDER_CREATE"
    And the tenant has access policy "load-write-org-subtree" on "LOAD_CREATE"
    And the tenant has access policy "trip-write-org-subtree" on "TRIP_EXECUTE"
    And the tenant has access policy "routing-guide-write-org-subtree" on "ROUTING_GUIDE_MANAGE"
    And the tenant has access policy "warehouse-setup-org-subtree" on "WAREHOUSE_SETUP"
    And the tenant has access policy "client-contract-write-org-subtree" on "CLIENT_CONTRACT_MANAGE"
    And the tenant has access policy "rail-tariff-write-org-subtree" on "TARIFF_MANAGE"

  # ------------------------------------------------ monetary: invoices

  Scenario: An approver may approve an invoice inside their limit
    Given a trip "T1" carrying 5000 kg for "VENDOR-1"
    And trip "T1" is run, dispatched 2 days ago after 30 minutes at the origin
    And trip "T1" is completed
    And the signed-in user has an approval limit of 50000
    When the signed-in user matches a claim of 20000 against "FB-T1"
    Then the access policy allows it
    And bill "FB-T1" is "APPROVED_FOR_PAYMENT"

  Scenario: An approver cannot approve an invoice beyond their limit
    Given a trip "T1" carrying 5000 kg for "VENDOR-1"
    And trip "T1" is run, dispatched 2 days ago after 30 minutes at the origin
    And trip "T1" is completed
    And the signed-in user has an approval limit of 10000
    When the signed-in user matches a claim of 20000 against "FB-T1"
    Then the access policy refuses it
    And bill "FB-T1" is "DRAFT"

  Scenario: Settling a dispute is held to the limit on what the vendor claimed
    # Settling in the vendor's favour is agreeing to pay the claim, so the
    # claim -- not the platform's lower figure -- is what the limit is held to.
    Given a trip "T1" carrying 5000 kg for "VENDOR-1"
    And trip "T1" is run, dispatched 2 days ago after 30 minutes at the origin
    And trip "T1" is completed
    And "VENDOR-1" invoices 26000 against "FB-T1"
    And bill "FB-T1" is "DISPUTED"
    And the signed-in user has an approval limit of 25000
    When the signed-in user settles the dispute on "FB-T1"
    Then the access policy refuses it
    And bill "FB-T1" is "DISPUTED"

  # ----------------------------------- monetary: releasing held money

  Scenario: Closing a ticket that holds money needs a large enough limit
    Given a dispatched trip "TRIP-T1" on lorry "MH-01-TL-0001" from "ORIGIN" to "DEST-A"
    And "ACCIDENT" is reported on trip "TRIP-T1" as ticket "X1"
    And ticket "X1" is assigned to "desk@acme.test"
    And ticket "X1" is taken under investigation
    And ticket "X1" is resolved by "asha" because "Driver unhurt; load transshipped"
    When "vikram" with an approval limit of 50000 closes ticket "X1"
    Then the access policy refuses it
    And ticket "X1" is "RESOLVED" with severity "P1"

  Scenario: A manager with a large enough limit closes it
    Given a dispatched trip "TRIP-T1" on lorry "MH-01-TL-0001" from "ORIGIN" to "DEST-A"
    And "ACCIDENT" is reported on trip "TRIP-T1" as ticket "X1"
    And ticket "X1" is assigned to "desk@acme.test"
    And ticket "X1" is taken under investigation
    And ticket "X1" is resolved by "asha" because "Driver unhurt; load transshipped"
    When "vikram" with an approval limit of 250000 closes ticket "X1"
    Then the access policy allows it
    And ticket "X1" was closed by "vikram"

  Scenario: A ticket holding no money needs no limit to close
    Given a dispatched trip "TRIP-T1" on lorry "MH-01-TL-0001" from "ORIGIN" to "DEST-A"
    And "ETA_DELAY" is reported on trip "TRIP-T1" as ticket "X1"
    And ticket "X1" is assigned to "desk@acme.test"
    And ticket "X1" is taken under investigation
    And ticket "X1" is resolved by "asha" because "Arrived late"
    When "vikram" with an approval limit of 1000 closes ticket "X1"
    Then the access policy allows it

  # ------------------------------------------------ organisational subtree

  Scenario: A branch registers partners in its own subtree and nowhere else
    Given tenant "acme" has an organisational unit "/acme/north/" named "North" of type "REGION"
    And tenant "acme" has an organisational unit "/acme/north/pune/" named "Pune" of type "BRANCH"
    And tenant "acme" has an organisational unit "/acme/south/" named "South" of type "REGION"
    And the tenant scope is "acme" at "/acme/north/"
    When a partner "P-NORTH" is registered under "/acme/north/pune/"
    Then the access policy allows it
    When a partner "P-SOUTH" is registered under "/acme/south/"
    Then the access policy refuses it
    When a partner "P-HQ" is registered under "/acme/"
    Then the access policy refuses it

  Scenario: Head office may register anywhere beneath it
    Given tenant "acme" has an organisational unit "/acme/south/" named "South" of type "REGION"
    When a partner "P-SOUTH" is registered under "/acme/south/"
    Then the access policy allows it

  Scenario: A tenant that switches the subtree rule off gets the old behaviour back
    Given tenant "acme" has an organisational unit "/acme/north/" named "North" of type "REGION"
    And tenant "acme" has an organisational unit "/acme/south/" named "South" of type "REGION"
    And access policy "partner-write-org-subtree" is disabled
    And the tenant scope is "acme" at "/acme/north/"
    When a partner "P-SOUTH" is registered under "/acme/south/"
    Then the access policy allows it

  Scenario: Every create command is held to the caller's subtree
    # One record from each module group. A record with no unit of its own is
    # held to the unit of what it hangs from: a trip to its load's, a bin to
    # its warehouse's.
    Given tenant "acme" has an organisational unit "/acme/north/" named "North" of type "REGION"
    And tenant "acme" has an organisational unit "/acme/south/" named "South" of type "REGION"
    And a warehouse "WH-SOUTH" under "/acme/south/"
    And a load "L-SOUTH" awarded to "VENDOR-1" under "/acme/south/"
    And the tenant scope is "acme" at "/acme/north/"
    When a vehicle is registered under "/acme/north/"
    Then the access policy allows it
    When a vehicle is registered under "/acme/south/"
    Then the access policy refuses it
    When an order is raised under "/acme/south/"
    Then the access policy refuses it
    When a load is opened under "/acme/south/"
    Then the access policy refuses it
    When a routing guide is defined under "/acme/south/"
    Then the access policy refuses it
    When a trip is raised against load "L-SOUTH"
    Then the access policy refuses it
    When a bin is laid out in warehouse "WH-SOUTH"
    Then the access policy refuses it

  Scenario: Head office files records anywhere beneath it
    Given tenant "acme" has an organisational unit "/acme/south/" named "South" of type "REGION"
    And a warehouse "WH-SOUTH" under "/acme/south/"
    When a vehicle is registered under "/acme/south/"
    Then the access policy allows it
    When an order is raised under "/acme/south/"
    Then the access policy allows it
    When a bin is laid out in warehouse "WH-SOUTH"
    Then the access policy allows it

  # ------------------------------------------------------ partner scope

  Scenario: A vendor-portal user sees only their own company's bills
    Given a carrier "VENDOR-2"
    And a trip "T1" carrying 5000 kg for "VENDOR-1"
    And trip "T1" is run, dispatched 2 days ago after 30 minutes at the origin
    And trip "T1" is completed
    And a trip "T2" carrying 4000 kg for "VENDOR-2"
    And trip "T2" is run, dispatched 1 days ago after 30 minutes at the origin
    And trip "T2" is completed
    When the signed-in user is a portal user for "VENDOR-1"
    Then the bill listing shows "FB-T1" only
    And bill "FB-T2" cannot be seen
    And the lines of bill "FB-T2" cannot be seen
    When the signed-in user is tenant staff
    Then the bill listing shows "FB-T1" and "FB-T2"

  # --------------------------------------------- policies are data

  Scenario: A tenant writes its own rule, and it applies from the next request
    Given access policy "west-no-partners" denies "PARTNER_CREATE" when:
      """
      {"attr": "subject.region", "op": "eq", "value": "WEST"}
      """
    And the signed-in user works in region "WEST"
    When a partner "P-WEST" is registered under "/acme/"
    Then the access policy refuses it
    Given the signed-in user works in region "EAST"
    When a partner "P-EAST" is registered under "/acme/"
    Then the access policy allows it

  Scenario: A user confined to a freight mode is refused outside it
    # Freight bills carry no mode yet, so this tenant's rule treats every bill
    # as road freight: a user whose modes leave out FTL may not approve one.
    Given a trip "T1" carrying 5000 kg for "VENDOR-1"
    And trip "T1" is run, dispatched 2 days ago after 30 minutes at the origin
    And trip "T1" is completed
    And access policy "road-bills-need-ftl" denies "INVOICE_APPROVE" when:
      """
      {"allOf": [{"attr": "subject.freightModes", "op": "present"},
                 {"not": {"attr": "subject.freightModes", "op": "contains", "value": "FTL"}}]}
      """
    And the signed-in user works only in "RAIL"
    When the signed-in user matches a claim of 20000 against "FB-T1"
    Then the access policy refuses it

  Scenario: A rule that does not parse is refused when it is written
    When an access policy "typo" is written for "PARTNER_CREATE" with:
      """
      {"attr": "subject.region", "op": "approximately", "value": "WEST"}
      """
    Then writing the policy is refused mentioning "Unknown operator"
    When an access policy "typo" is written for "PARTNER_CREATE" with:
      """
      {"attr": "subject.regoin", "op": "eq", "value": "WEST"}
      """
    Then writing the policy is refused mentioning "Unknown attribute"
    When an access policy "typo" is written for "NOT_A_PERMISSION" with:
      """
      {"attr": "subject.region", "op": "eq", "value": "WEST"}
      """
    Then writing the policy is refused mentioning "not a permission code"

  # ------------------------------------------ attributes travel in the token

  Scenario: A user's attributes are in their token, and a change arrives on refresh
    Given tenant "acme" has a user "meera@acme.test" with password "s3cret-pass" in unit "/acme/"
    And "meera@acme.test" has an approval limit of 75000
    When "meera@acme.test" signs in to tenant "acme" with password "s3cret-pass"
    Then the access token carries an approval limit of 75000
    Given "meera@acme.test" has an approval limit of 5000
    When the access token is refreshed
    Then the access token carries an approval limit of 5000

  # ------------------------------------------- partner scope beyond finance

  Scenario: A vendor-portal user sees only the trips and loads awarded to them
    Given a carrier "VENDOR-2"
    And trip "P1" is raised for "VENDOR-1"
    And trip "P2" is raised for "VENDOR-2"
    When the signed-in user is a portal user for "VENDOR-1"
    Then the trip listing shows "P1" only
    And trip "P1" can be read
    And trip "P2" reads as not found
    And the load listing shows the load of "P1" only
    And the load of "P2" reads as not found
    When the signed-in user is tenant staff
    Then the trip listing shows "P1" and "P2"

  Scenario: A customer-portal user sees only their own orders
    Given a trading partner "CUST-2"
    And order "O1" is raised for "CUST"
    And order "O2" is raised for "CUST-2"
    When the signed-in user is a portal user for "CUST"
    Then the order listing shows "O1" only
    And order "O2" reads as not found
    When the signed-in user is a portal user for "VENDOR-1"
    Then the order listing shows nothing
