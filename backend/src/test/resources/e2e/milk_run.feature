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

@milkrun
Feature: A milk run is published, collected and billed, over the wire
  Inbound milk-run logistics, end to end through the published API only. A
  route calls at suppliers in turn and ends at the plant; a circuit is
  generated from it, carried by a trip, recorded stop by stop, completed, and
  priced as a fixed rate for the run plus a fee for every supplier visited.
  Finance turns the completed circuit into the carrier's payable.

  Background:
    Given the demo administrator is signed in at the milk-run desk
    And two supplier sites, a plant and an active carrier are registered for the milk run
    And a daily milk-run route is published at 5000 per run and 750 per stop calling at both suppliers

  Scenario: A circuit is generated, collected stop by stop, and billed to the carrier
    When the circuit for that day is generated
    And a trip from the first supplier to the plant is dispatched and carries the circuit
    And both suppliers are reached and left within their windows collecting 1200 kg at the first
    And the circuit is recorded as reaching the plant
    Then the circuit is completed having visited 2 suppliers and skipped none
    And the circuit is charged 6500 and collected 1200 kg
    And a freight bill of 6500 is raised for the circuit as a milk-run payable
