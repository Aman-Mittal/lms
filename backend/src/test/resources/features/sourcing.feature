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

Feature: Finding a vendor to move the load
  Vision document 3.5.

  A planned load is freight that exists and nobody has agreed to carry. The
  cascade walks the contracted vendors in order until one accepts, and stops
  when it runs out -- which is the outcome that most needs to be visible,
  because a load nobody will take does not fix itself.

  Background:
    Given a tenant "acme" named "Acme Logistics"
    And tenant "acme" has an organisational unit "/acme/" named "Acme HQ" of type "HQ"
    And the tenant scope is "acme" at "/acme/"
    And a trading partner "CUST"
    And a trading partner "CONS-A"
    And a depot "ORIGIN" at 19.10,72.90
    And a depot "DEST-A" at 19.30,73.10
    And a carrier "VENDOR-1"
    And a carrier "VENDOR-2"
    And a carrier "VENDOR-3"
    And a lorry "MH-01-SR-0001" with payload 10000 kg and volume 40 m3
    And a load "LOAD-S" ready for sourcing from "ORIGIN" to "CONS-A" at "DEST-A" on lorry "MH-01-SR-0001"

  Scenario: The rank one vendor accepts
    Given a routing guide from "ORIGIN" to "DEST-A" for "RIGID"
    And the guide ranks "VENDOR-1" first with a 60 minute SLA
    And the guide ranks "VENDOR-2" second with a 60 minute SLA
    When load "LOAD-S" is allocated
    Then the offer is with "VENDOR-1" at rank 1
    When "VENDOR-1" accepts the offer
    Then the allocation is "AWARDED" to "VENDOR-1"
    And load "LOAD-S" is "AWARDED"

  Scenario: A rejection cascades to the next rank
    Given a routing guide from "ORIGIN" to "DEST-A" for "RIGID"
    And the guide ranks "VENDOR-1" first with a 60 minute SLA
    And the guide ranks "VENDOR-2" second with a 60 minute SLA
    And load "LOAD-S" is allocated
    When "VENDOR-1" rejects the offer
    Then the offer is with "VENDOR-2" at rank 2
    And the allocation is "OFFERED"
    When "VENDOR-2" accepts the offer
    Then the allocation is "AWARDED" to "VENDOR-2"

  Scenario: An unanswered offer lapses and cascades
    # The SLA is the whole point of the rank cascade. Without enforcement an
    # unanswered offer holds the load indefinitely while the allocation looks
    # perfectly healthy.
    Given a routing guide from "ORIGIN" to "DEST-A" for "RIGID"
    And the guide ranks "VENDOR-1" first with a 60 minute SLA
    And the guide ranks "VENDOR-2" second with a 60 minute SLA
    And load "LOAD-S" is allocated
    And the deadline for the offer to "VENDOR-1" has passed
    When lapsed offers are swept
    Then the offer is with "VENDOR-2" at rank 2
    And the offer to "VENDOR-1" is recorded as "TIMED_OUT"

  Scenario: The cascade stops when every vendor has been asked
    Given a routing guide from "ORIGIN" to "DEST-A" for "RIGID"
    And the guide ranks "VENDOR-1" first with a 60 minute SLA
    And the guide ranks "VENDOR-2" second with a 60 minute SLA
    And load "LOAD-S" is allocated
    When "VENDOR-1" rejects the offer
    And "VENDOR-2" rejects the offer
    Then the allocation is "EXHAUSTED"
    And load "LOAD-S" is "PLANNED"

  Scenario: An exhausted allocation is not quietly retried
    # A load nobody will take is a commercial problem -- the rate is wrong, or
    # the lane is unserviceable today. Re-offering it automatically would spin
    # against unwilling vendors while the freight sits in the yard.
    Given a routing guide from "ORIGIN" to "DEST-A" for "RIGID"
    And the guide ranks "VENDOR-1" first with a 60 minute SLA
    And load "LOAD-S" is allocated
    And "VENDOR-1" rejects the offer
    When "VENDOR-1" accepts the offer
    Then the response is refused mentioning "no offer outstanding"

  Scenario: A blacklisted vendor is skipped
    # The guide is a standing arrangement and outlives the standing of the
    # vendors in it, so eligibility is checked at offer time rather than
    # trusted from when the guide was written.
    Given a routing guide from "ORIGIN" to "DEST-A" for "RIGID"
    And the guide ranks "VENDOR-1" first with a 60 minute SLA
    And the guide ranks "VENDOR-2" second with a 60 minute SLA
    And carrier "VENDOR-1" is blacklisted
    When load "LOAD-S" is allocated
    Then the offer is with "VENDOR-2" at rank 2

  Scenario: Allocation is refused when every vendor on the lane is blacklisted
    Given a routing guide from "ORIGIN" to "DEST-A" for "RIGID"
    And the guide ranks "VENDOR-1" first with a 60 minute SLA
    And carrier "VENDOR-1" is blacklisted
    When load "LOAD-S" is allocated
    Then allocation is refused mentioning "inactive or blacklisted"

  Scenario: Allocation is refused when no guide covers the lane
    When load "LOAD-S" is allocated
    Then allocation is refused mentioning "No active routing guide"

  Scenario: A load can only be allocated once
    # Two live allocations for one load is how the same trip gets sold to two
    # vendors, and it is not recoverable after the fact -- both will have
    # committed a lorry.
    Given a routing guide from "ORIGIN" to "DEST-A" for "RIGID"
    And the guide ranks "VENDOR-1" first with a 60 minute SLA
    And load "LOAD-S" is allocated
    When load "LOAD-S" is allocated
    Then allocation is refused mentioning "already has an allocation"

  Scenario: Answering an offer that has moved on is refused
    Given a routing guide from "ORIGIN" to "DEST-A" for "RIGID"
    And the guide ranks "VENDOR-1" first with a 60 minute SLA
    And the guide ranks "VENDOR-2" second with a 60 minute SLA
    And load "LOAD-S" is allocated
    And "VENDOR-1" rejects the offer
    When "VENDOR-1" accepts the offer
    Then the response is refused mentioning "outstanding offer is with"

  Scenario: The cascade trail records every vendor asked
    Given a routing guide from "ORIGIN" to "DEST-A" for "RIGID"
    And the guide ranks "VENDOR-1" first with a 60 minute SLA
    And the guide ranks "VENDOR-2" second with a 60 minute SLA
    And the guide ranks "VENDOR-3" third with a 60 minute SLA
    And load "LOAD-S" is allocated
    When "VENDOR-1" rejects the offer
    And "VENDOR-2" rejects the offer
    And "VENDOR-3" accepts the offer
    Then 3 offers were made
    And the offer to "VENDOR-1" is recorded as "REJECTED"
    And the offer to "VENDOR-3" is recorded as "ACCEPTED"

  Scenario: Round robin picks the vendor with the fewest awards this month
    Given a round-robin routing guide from "ORIGIN" to "DEST-A" for "RIGID"
    And the guide ranks "VENDOR-1" first with a 60 minute SLA
    And the guide ranks "VENDOR-2" second with a 60 minute SLA
    And "VENDOR-1" has already been awarded 2 loads this month
    When load "LOAD-S" is allocated
    Then the offer is with "VENDOR-2" at rank 2

  Scenario: Round robin is deterministic when nobody has been awarded yet
    # The first award of a month has every vendor on zero. A strategy that
    # broke that tie arbitrarily would give different answers to the same
    # question and could not be tested at all.
    Given a round-robin routing guide from "ORIGIN" to "DEST-A" for "RIGID"
    And the guide ranks "VENDOR-2" second with a 60 minute SLA
    And the guide ranks "VENDOR-1" first with a 60 minute SLA
    When load "LOAD-S" is allocated
    Then the offer is with "VENDOR-1" at rank 1

  Scenario: Only a planned load can be offered
    Given a routing guide from "ORIGIN" to "DEST-A" for "RIGID"
    And the guide ranks "VENDOR-1" first with a 60 minute SLA
    And load "LOAD-S" is allocated
    And "VENDOR-1" accepts the offer
    And a load "LOAD-DRAFT" is left in draft from "ORIGIN" on lorry "MH-01-SR-0001"
    When load "LOAD-DRAFT" is allocated
    Then allocation is refused mentioning "only a PLANNED load"
