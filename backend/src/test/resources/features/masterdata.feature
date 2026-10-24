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

Feature: Master data -- the geospatial terminal network
  Vision document 3.2.3.

  Terminals are the anchors of the whole platform: geofence crossings drive the
  trip state machine, so a terminal that is wrong in a way nobody notices does
  not raise an error -- it silently moves trips to the wrong state, or fails to
  move them at all.

  These scenarios exercise the geometry through the real command service and a
  real database, not through GeoUtils directly, so the bounding-box candidate
  filter is covered along with the exact test it feeds.

  Background:
    Given a tenant "acme" named "Acme Logistics"
    And tenant "acme" has an organisational unit "/acme/" named "Acme HQ" of type "HQ"
    And the tenant scope is "acme" at "/acme/"

  Scenario: A polygon terminal is registered
    When a WAREHOUSE terminal "WH-1" is registered with the square from 19.00,72.80 to 19.01,72.81
    Then the terminal is registered
    And terminal "WH-1" contains the point 19.005,72.805
    And terminal "WH-1" does not contain the point 19.050,72.850

  Scenario: Overlapping terminals of the same category are rejected
    Given a WAREHOUSE terminal "WH-1" is registered with the square from 19.00,72.80 to 19.01,72.81
    When a WAREHOUSE terminal "WH-2" is registered with the square from 19.005,72.805 to 19.015,72.815
    Then registration is refused because the terminals overlap

  Scenario: A terminal drawn entirely inside another is rejected
    # The case that edge-intersection tests alone miss, and the one that matters
    # most operationally: a small yard drawn wholly within an existing one. No
    # edges cross, so a naive implementation says "no overlap".
    Given a WAREHOUSE terminal "WH-1" is registered with the square from 19.00,72.80 to 19.01,72.81
    When a WAREHOUSE terminal "WH-INNER" is registered with the square from 19.002,72.802 to 19.003,72.803
    Then registration is refused because the terminals overlap

  Scenario: Overlap is permitted across different functional categories
    # A warehouse inside a port is a normal arrangement, not a data error.
    Given a PORT terminal "PORT-1" is registered with the square from 19.00,72.80 to 19.01,72.81
    When a WAREHOUSE terminal "WH-IN-PORT" is registered with the square from 19.002,72.802 to 19.003,72.803
    Then the terminal is registered

  Scenario: Non-overlapping terminals of the same category are allowed
    Given a WAREHOUSE terminal "WH-1" is registered with the square from 19.00,72.80 to 19.01,72.81
    When a WAREHOUSE terminal "WH-FAR" is registered with the square from 20.00,73.80 to 20.01,73.81
    Then the terminal is registered

  Scenario: Point-radius terminals reject an overlapping circle
    Given a HUB terminal "HUB-1" is registered at 19.00,72.80 with radius 500 m
    When a HUB terminal "HUB-2" is registered at 19.002,72.80 with radius 500 m
    Then registration is refused because the terminals overlap

  Scenario: Point-radius terminals far enough apart are allowed
    Given a HUB terminal "HUB-1" is registered at 19.00,72.80 with radius 500 m
    When a HUB terminal "HUB-3" is registered at 19.100,72.80 with radius 500 m
    Then the terminal is registered

  Scenario: A duplicate terminal code is rejected
    Given a WAREHOUSE terminal "WH-1" is registered with the square from 19.00,72.80 to 19.01,72.81
    When a WAREHOUSE terminal "WH-1" is registered with the square from 20.00,73.80 to 20.01,73.81
    Then registration is refused because the code is taken

  Scenario: A self-intersecting polygon is rejected outright
    # Point-in-polygon is genuinely ambiguous for a bow-tie, so the honest
    # answer is to refuse the input rather than pick a lobe and be quietly wrong.
    When a WAREHOUSE terminal "WH-BOWTIE" is registered with a self-intersecting ring
    Then registration is refused as invalid geometry

  Scenario: A terminal spanning an implausible distance is rejected
    # The planar approximation is sound for yards and ports and wrong for
    # continents. Rejecting beats answering incorrectly.
    When a WAREHOUSE terminal "WH-HUGE" is registered with the square from 10.00,70.00 to 20.00,80.00
    Then registration is refused as invalid geometry

  Scenario: Terminals of another tenant are invisible to the overlap check
    # Overlap is a per-tenant concern. Two customers of the platform may operate
    # depots on the same physical site, and neither may learn of the other.
    Given a WAREHOUSE terminal "WH-1" is registered with the square from 19.00,72.80 to 19.01,72.81
    And a tenant "globex" named "Globex Freight"
    And tenant "globex" has an organisational unit "/globex/" named "Globex HQ" of type "HQ"
    And the tenant scope is "globex" at "/globex/"
    When a WAREHOUSE terminal "WH-1" is registered with the square from 19.00,72.80 to 19.01,72.81
    Then the terminal is registered

  Scenario: A terminal is renamed and resized in place, and the change is audited
    Given a WAREHOUSE terminal "WH-1" is registered with the square from 19.00,72.80 to 19.01,72.81
    When terminal "WH-1" is renamed "Main warehouse" with 8 docks
    Then the terminal is updated
    And terminal "WH-1" is named "Main warehouse" with 8 docks
    And the audit trail has 1 "TERMINAL_UPDATED" entry for terminal "WH-1"

  Scenario: A terminal's geofence can be moved
    Given a WAREHOUSE terminal "WH-1" is registered with the square from 19.00,72.80 to 19.01,72.81
    When terminal "WH-1" is moved to the square from 19.20,72.80 to 19.21,72.81
    Then the terminal is updated
    And terminal "WH-1" contains the point 19.205,72.805
    And terminal "WH-1" does not contain the point 19.005,72.805

  Scenario: A terminal's geofence can change shape
    Given a WAREHOUSE terminal "WH-1" is registered with the square from 19.00,72.80 to 19.01,72.81
    When terminal "WH-1" is moved to the circle at 19.40,72.80 with radius 300 m
    Then the terminal is updated
    And terminal "WH-1" contains the point 19.4005,72.8005
    And terminal "WH-1" does not contain the point 19.005,72.805

  Scenario: A terminal may grow over its own old footprint
    # Its own footprint is not a clash with itself.
    Given a WAREHOUSE terminal "WH-1" is registered with the square from 19.00,72.80 to 19.01,72.81
    When terminal "WH-1" is moved to the square from 18.99,72.79 to 19.02,72.82
    Then the terminal is updated

  Scenario: A terminal may not be moved onto a neighbour of the same category
    Given a WAREHOUSE terminal "WH-1" is registered with the square from 19.00,72.80 to 19.01,72.81
    And a WAREHOUSE terminal "WH-2" is registered with the square from 19.10,72.80 to 19.11,72.81
    When terminal "WH-2" is moved to the square from 19.005,72.805 to 19.015,72.815
    Then the update is refused with "terminal-overlap"
    And terminal "WH-2" contains the point 19.105,72.805

  Scenario: A terminal may be moved across a terminal of a different category
    Given a PORT terminal "PORT-1" is registered with the square from 19.00,72.80 to 19.01,72.81
    And a WAREHOUSE terminal "WH-2" is registered with the square from 19.10,72.80 to 19.11,72.81
    When terminal "WH-2" is moved to the square from 19.002,72.802 to 19.003,72.803
    Then the terminal is updated

  Scenario: A badly drawn new geofence is refused and the old one stays
    Given a WAREHOUSE terminal "WH-1" is registered with the square from 19.00,72.80 to 19.01,72.81
    When terminal "WH-1" is moved to the square from 10.00,70.00 to 20.00,80.00
    Then the update is refused with "IllegalArgumentException"
    And terminal "WH-1" contains the point 19.005,72.805

  Scenario: Two people editing the same terminal cannot overwrite each other
    Given a WAREHOUSE terminal "WH-1" is registered with the square from 19.00,72.80 to 19.01,72.81
    And terminal "WH-1" is renamed "First edit" with 5 docks
    When terminal "WH-1" is renamed "Second edit" from the version before the last change
    Then the update is refused with "concurrent-modification"
    And terminal "WH-1" is named "First edit" with 5 docks

  Scenario: An unreferenced terminal is deleted
    Given a WAREHOUSE terminal "WH-1" is registered with the square from 19.00,72.80 to 19.01,72.81
    When terminal "WH-1" is deleted
    Then the terminal is deleted
    And terminal "WH-1" no longer exists
    And the audit trail has 1 "TERMINAL_DELETED" entry for terminal "WH-1"

  Scenario: A deleted terminal's code and ground can be used again
    Given a WAREHOUSE terminal "WH-1" is registered with the square from 19.00,72.80 to 19.01,72.81
    And terminal "WH-1" is deleted
    When a WAREHOUSE terminal "WH-1" is registered with the square from 19.00,72.80 to 19.01,72.81
    Then the terminal is registered

  Scenario: Another tenant cannot change or delete a terminal and learns nothing about it
    Given a WAREHOUSE terminal "WH-1" is registered with the square from 19.00,72.80 to 19.01,72.81
    And a tenant "globex" named "Globex Freight"
    And tenant "globex" has an organisational unit "/globex/" named "Globex HQ" of type "HQ"
    And the tenant scope is "globex" at "/globex/"
    When terminal "WH-1" is deleted
    Then deleting the terminal is refused as not found
    And terminal "WH-1" still exists

