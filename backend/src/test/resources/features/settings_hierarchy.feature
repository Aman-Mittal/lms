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
Feature: Settings are overridden organisation-wide, then by country, zone and branch, then by combination
  The organisation is a tree of levels: head office, line of business,
  country, zone (or region), branch, hub. A unit ranks strictly below its
  parent. A setting folds from the organisation default down the tree, the
  deeper unit winning, and the value says which level it came from. Above a
  vehicle's, a customer's or a vendor's own profile sits a combination: a
  profile that applies only where every one of its conditions holds -- a
  branch and a customer, say. Two combinations the fold could not choose
  between may not set the same key.

  Background:
    Given a tenant "acme" named "Acme Logistics"
    And tenant "acme" has an organisational unit "/acme/" named "Acme HQ" of type "HQ"
    And the tenant scope is "acme" at "/acme/"
    And a trading partner "CUST"
    And a trading partner "OTHER"
    And country unit "in" named "India" with code "IN" is added beneath "/acme/"
    And unit "north" named "North" is added as a ZONE beneath "/acme/in/"
    And unit "gurgaon" named "Gurgaon" is added as a BRANCH beneath "/acme/in/north/"

  Scenario: The deepest unit that sets a value wins, and says which level it is
    Given the tenant's close tolerance is 1 percent
    And org unit "/acme/in/" has a profile setting "order.close.tolerancePercent" to "2"
    And org unit "/acme/in/north/" has a profile setting "order.close.tolerancePercent" to "3"
    Then the close tolerance in force at unit "/acme/in/north/gurgaon/" is 3 percent from "Zone: North"
    And the close tolerance in force at unit "/acme/in/" is 2 percent from "Country: India"
    Given org unit "/acme/in/north/gurgaon/" has a profile setting "order.close.tolerancePercent" to "4"
    Then the close tolerance in force at unit "/acme/in/north/gurgaon/" is 4 percent from "Branch: Gurgaon"

  Scenario: A branch-and-customer combination beats the customer's own profile, at that branch only
    Given customer "CUST" has its own close tolerance of 5 percent
    And a combination of unit "/acme/in/north/gurgaon/" and customer "CUST" sets the close tolerance to 7 percent
    Then the close tolerance for customer "CUST" at unit "/acme/in/north/gurgaon/" is 7 percent from "Branch Gurgaon + Customer CUST Trading"
    And the close tolerance for customer "CUST" at unit "/acme/in/north/" is 5 percent from "Customer: CUST terms"
    And the close tolerance for customer "OTHER" at unit "/acme/in/north/gurgaon/" is 0 percent from "System default"

  Scenario: Two combinations the fold could not choose between may not both set a key
    Given a combination of unit "/acme/in/north/gurgaon/" and customer "CUST" sets the close tolerance to 7 percent
    Then a combination of unit "/acme/in/north/gurgaon/" and freight mode "LTL" setting the close tolerance is refused with "setting-combination-ambiguous"
    And a combination of unit "/acme/in/north/" and freight mode "LTL" setting the close tolerance is accepted

  Scenario: A unit ranks strictly below its parent
    Then adding a ZONE unit "east" beneath "/acme/in/north/gurgaon/" is refused with "org-unit-level-invalid"
    And adding a COUNTRY unit "lk" beneath "/acme/in/" is refused with "org-unit-level-invalid"
    And adding a HUB unit "dock" beneath "/acme/in/north/gurgaon/" is accepted
