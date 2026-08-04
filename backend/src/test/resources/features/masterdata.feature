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
