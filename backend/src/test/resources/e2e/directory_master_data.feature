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
Feature: The directory of partners, vehicles, drivers and terminals is readable and bounded, over the wire
  Master data end to end through the published API only: what was registered
  reads back field for field, listings page by keyset and filter, a terminal
  that breaks the geofence rules is refused with its own problem code, and a
  tenant sees only its own directory. Reading needs its own permission and
  nothing else gives it.

  Background:
    Given the master-data directory is open to the demo administrator, a second tenant, a read-only user and a user with no permissions

  Scenario: Partners are read back, paged, filtered and kept to their tenant
    When the directory registers a customer and two vendors, the last of them active, under a code prefix of its own
    Then each directory partner reads back with its code, legal name, type, status and tax id
    And directory partners page two at a time from the cursor in code order
    And directory partner filters by type and status pick out exactly the right one of the three
    And an unknown directory partner is a 404 problem and so is another tenant's partner
    And another tenant's directory does not list these partners but lists its own
    And directory partners are refused as 403 to a user with no permissions and readable by a read-only user

  Scenario: Vehicles are read back with their capacity and documents, paged, filtered and refused to strangers
    When the directory registers two lorries of 8000 and 12000 kg payload under a registration prefix of its own
    Then directory vehicle A reads back with its registration, capacity, configuration and document summary
    And directory vehicle B reads back with its registration, capacity, configuration and document summary
    And the directory lists the two lorries in registration order, pages them and filters them by status
    And the directory shows the insurance filed with lorry A, and no documents yet for a fresh owner
    And an unknown vehicle, another tenant's vehicle and a caller who may not read are each refused

  Scenario: Drivers are read back with their licence and employer, paged, filtered and refused to strangers
    When the directory registers two drivers, the second employed by an active vendor, under a licence prefix of its own
    Then each directory driver reads back with its licence, endorsement, status and employer
    And the directory lists the two drivers in licence order, pages them and filters them by status
    And an unknown driver, another tenant's driver and a caller who may not read are each refused

  Scenario: Terminals of both geofence shapes are read back and the overlap rules hold
    When the directory registers a point-radius depot and a polygon hub in its own corner of the map
    Then the directory shows each terminal with its geofence, docks and hours
    And the directory lists terminals by category from a cursor and pages them
    And a second hub over the same ground, a repeated code and a malformed ring are each refused with their own code
    And another tenant may register the very same hub, and neither tenant can see the other's terminals
    And terminals are readable by a read-only user, who may not register one, and invisible to a user with no permissions
