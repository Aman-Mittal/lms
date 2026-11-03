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

@ltl
Feature: A part load is booked on a docket and billed by zone, over the wire
  Part-load (LTL) freight, end to end through the published API only. Hubs
  belong to zones, the shipment is booked on a docket between two hubs, the
  docket rides a line-haul load, and the customer's receivable for that docket
  is priced from the zone rate card on the docket's own chargeable weight.

  Background:
    Given the demo administrator is signed in at the part-load desk
    And part-load hubs in zone "WEST" and zone "NORTH" are registered
    And a part-load customer, a consignee and a delivery site are registered
    And a zone rate card charges 12.00 per kg with a 100 docket charge between those zones

  Scenario: A part-load docket is booked, loaded and priced by zone
    When a part load of 200 kg is booked from the origin hub to the destination hub
    Then the shipment is a docket numbered in the "DKT-" series between the two hubs
    When the docket is put on a line-haul lorry from the origin hub and the load is planned
    Then the line-haul load is a part load holding 1 docket
    And the docket receivable is charged on 200 kg and totals 2500 at the zone price
