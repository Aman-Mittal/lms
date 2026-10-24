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

Feature: Row-level security confines every table behind a by-id resource
  Vision document 2.1. identity.feature proves the guarantee for users and
  organisation units; this proves it for the tables behind consignments, loads,
  trips, freight bills, client invoices and the rest, at the database itself.

  Each scenario runs real SQL on a connection scoped to one tenant, as the
  application does: under the restricted role, with the tenant set for the
  transaction. The statements deliberately carry no tenant predicate, because
  the point is what the database allows when application code forgets one.

  Background:
    Given two tenants "acme" and "globex" each holding one row of everything
    And the session role is the unprivileged application role

  Scenario Outline: A tenant cannot read, change, remove or hijack another tenant's <table> row
    Then a session scoped to "globex" finds no <table> row with acme's id
    And a session scoped to "globex" sees only its own <table> rows
    And a session scoped to "globex" updating acme's <table> row changes nothing
    And a session scoped to "globex" deleting acme's <table> row removes nothing
    And a session scoped to "globex" cannot reassign its own <table> row to "acme"
    And acme's <table> row is untouched

    Examples:
      | table            |
      | sales_order      |
      | consignment      |
      | load_unit        |
      | trip             |
      | allocation       |
      | allocation_offer |
      | freight_bill     |
      | client_invoice   |
      | exception_ticket |
      | asn              |
      | outbound_order   |
      | handling_unit    |

  Scenario Outline: With no tenant bound a session sees no <table> rows at all
    Then a session with no tenant scope sees no <table> rows

    Examples:
      | table            |
      | sales_order      |
      | consignment      |
      | load_unit        |
      | trip             |
      | allocation       |
      | allocation_offer |
      | freight_bill     |
      | client_invoice   |
      | exception_ticket |
      | asn              |
      | outbound_order   |
      | handling_unit    |
