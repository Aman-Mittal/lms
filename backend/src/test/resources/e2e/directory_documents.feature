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

@e2e_directory
Feature: Statutory documents are filed, listed by owner and swept for expiry, over the wire
  Certificates belong to a partner, a vehicle or a driver. Each owner lists
  its own soonest first, the expiry watch spans all three and includes what
  has already lapsed, a permanent certificate never appears in it, and a
  tenant sees only its own. Filing needs the update permission, rejects an
  incomplete certificate and is safe to retry.

  Background:
    Given the document directory is open to the demo administrator, a second tenant, a read-only user and a user with no permissions

  Scenario: Each owner lists its own certificates soonest first
    When certificates are filed against a partner, a lorry and a driver: one lapsed and one due in ten days
    Then each owner's documents are listed soonest first, labelled with the owner's name
    And the partner and the lorry each count one expired document and the lorry shows the lapsed date as its earliest expiry

  Scenario: The expiry watch finds what has lapsed or lapses inside the window and nothing else
    When certificates lapsing at known distances are filed against a lorry, a driver and a partner
    Then the expiry watch lists the lapsed, the soon and the thirty-day certificates, soonest first, and no others
    And widening the window to sixty days adds the later certificates and a window of zero keeps only what has lapsed
    And another tenant's certificates never appear in the watch and ours never appear in theirs
    And the expiry watch is refused as 403 to a user with no permissions and open to a read-only user

  Scenario: Filing a certificate is validated, permissioned and safe to retry
    Given a partner, a lorry and a driver are registered to file documents against
    Then a certificate with no type or no number is a 400 problem naming the field, for each kind of owner
    And filing a certificate is refused as 403 to a read-only user and to a user with no permissions, for each kind of owner
    And a certificate filed twice under one idempotency key is stored once, and the key refuses a different certificate
    And a certificate with only a type, a number and an expiry is accepted and reads back without issue date or authority

  Scenario: A certificate with no expiry date lists cleanly for every kind of owner
    Given a partner, a lorry and a driver are registered to file documents against
    Then a certificate with no expiry date is listed for a partner, a lorry and a driver as not expired, with no days to expiry
