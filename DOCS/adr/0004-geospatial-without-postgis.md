<!--
Copyright 2026 Aman Mittal

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# 4. Geospatial without PostGIS

Status: **Accepted** · 2026-08-04

## Context

The platform is fundamentally spatial. §3.2.3 requires terminals defined as
polygons or point-radius circles, and requires rejecting a new terminal that
overlaps an existing one of the same functional category. §3.7.2 requires
continuous point-in-polygon evaluation against streaming GPS coordinates to
trigger trip state changes, plus route-deviation detection against a planned
polyline.

The obvious tool is PostGIS. Two problems:

1. **PostGIS is licensed GPLv2.** Per ADR-0003 this project ships an Apache-2.0
   distribution and excludes copyleft from its dependency set. A required
   database extension under GPLv2 is a dependency in every sense that matters,
   even though it is reached over a wire protocol. Treating it as "not really a
   dependency" because it runs in another process is the kind of reasoning that
   makes a compliance claim untrustworthy.
2. **It is not guaranteed available.** The free Postgres instance is disposable
   and re-provisioned roughly monthly. Depending on an extension being
   installable adds a failure mode to a recovery path that must stay trivial.

## Decision

Implement the geometry the platform actually needs, in plain Java, in
`com.lms.shared.geo.GeoUtils`. No JTS, no GeoTools, no database extension.

**Storage.** A terminal's polygon is a JSONB array of `[lon, lat]` pairs, stored
alongside four precomputed bounding-box columns (`min_lat`, `max_lat`,
`min_lon`, `max_lon`) with a plain B-tree index.

**Query strategy.** Every spatial question is answered in two phases: a cheap
indexed bounding-box filter in SQL to find candidates, then exact geometry in
Java over that small candidate set. For a tenant with tens of terminals this is
not a compromise — the candidate set is almost always zero or one.

**Operations implemented.**

| Operation | Algorithm | Used for |
|---|---|---|
| `pointInPolygon` | ray casting (even-odd rule) | geofence entry/exit (§3.7.2) |
| `haversine` | great-circle distance | point-radius terminals, remaining distance |
| `polygonsOverlap` | segment intersection + containment | terminal overlap rejection (§3.2.3) |
| `distanceToPolyline` | perpendicular distance to segments | route deviation (§3.7.2) |

**Simplifying assumption, stated explicitly:** geometry is computed on a
spherical earth, and polygon operations treat small regions as locally planar.
For terminal-sized polygons — warehouses, plants, ports, typically well under
10 km across — the error is negligible. This is **not** correct for polygons
spanning large distances or crossing the antimeridian, and the code rejects
such inputs rather than returning a quietly wrong answer.

## Consequences

**What this buys.** A clean Apache-2.0 dependency graph. No extension to
provision when the database is recreated. No native geometry library to
configure for GraalVM. Evaluation is fast enough to run synchronously inside the
ping-ingest transaction, which is what allows geofence crossings to drive the
trip state machine without a queue.

**What it costs.**

- **No spatial index.** The bounding-box filter is a substitute for R-tree/GiST
  indexing and is adequate at MVP data volumes. It will degrade for a tenant
  with thousands of terminals in a small area, where bounding boxes overlap
  heavily and the candidate set stops being small.
- **A narrow set of operations.** There is no buffering, no convex hull, no
  spatial joins, no projection handling, no topology validation beyond what is
  written. Each new spatial requirement is code to write and test, not a
  function call.
- **Correctness is now this project's problem.** Ray casting has genuine edge
  cases — vertices exactly on the ray, points exactly on an edge, self-
  intersecting polygons. These are covered by unit tests, and any change to
  `GeoUtils` needs tests for them.
- **No antimeridian or polar support.** Rejected at input rather than
  mishandled. A tenant operating across the Pacific dateline would need this
  decision revisited.

**When to revisit.** If the platform needs genuine spatial querying — nearest-
neighbour search over many thousands of terminals, route corridor analysis,
isochrones — writing that by hand stops being sensible. At that point the
options are a permissively licensed geometry library (JTS is EPL/BSD dual
licensed and worth re-examining) or accepting PostGIS and relicensing the
distribution accordingly. Both are ADR-sized decisions.
