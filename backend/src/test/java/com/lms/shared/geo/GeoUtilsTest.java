/*
 * Copyright 2026 Aman Mittal
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lms.shared.geo;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * This class replaces PostGIS (DOCS/adr/0004), so its edge cases are the
 * platform's edge cases. A wrong point-in-polygon result does not surface as a
 * visible error -- it silently drives the trip state machine to the wrong
 * state, which then blocks or wrongly permits dispatch and invoicing.
 */
class GeoUtilsTest {

    /** A 0.01-degree square near Mumbai, roughly 1.1 km on a side. */
    private static final List<LatLon> SQUARE = List.of(
            new LatLon(19.00, 72.80),
            new LatLon(19.00, 72.81),
            new LatLon(19.01, 72.81),
            new LatLon(19.01, 72.80));

    @Nested
    @DisplayName("distance")
    class Distance {

        @Test
        @DisplayName("matches a known great-circle distance")
        void haversineMatchesKnownDistance() {
            // Mumbai to Delhi, ~1148 km by great circle.
            LatLon mumbai = new LatLon(19.0760, 72.8777);
            LatLon delhi = new LatLon(28.7041, 77.1025);

            double metres = GeoUtils.haversineMetres(mumbai, delhi);

            assertThat(metres).isCloseTo(1_148_000, within(10_000.0));
        }

        @Test
        @DisplayName("is zero for identical points")
        void zeroForIdenticalPoints() {
            LatLon p = new LatLon(19.0, 72.8);

            assertThat(GeoUtils.haversineMetres(p, p)).isZero();
        }

        @Test
        @DisplayName("is symmetric")
        void isSymmetric() {
            LatLon a = new LatLon(19.0, 72.8);
            LatLon b = new LatLon(28.7, 77.1);

            assertThat(GeoUtils.haversineMetres(a, b))
                    .isCloseTo(GeoUtils.haversineMetres(b, a), within(1e-6));
        }

        @Test
        @DisplayName("does not produce NaN for antipodal points")
        void handlesAntipodalPoints() {
            // The classic haversine failure: floating-point error pushes the
            // argument of asin just over 1.0 and the result becomes NaN.
            double metres = GeoUtils.haversineMetres(new LatLon(0.0, 0.0), new LatLon(0.0, 180.0));

            assertThat(metres).isNotNaN();
            assertThat(metres).isCloseTo(Math.PI * GeoUtils.EARTH_RADIUS_METRES, within(1.0));
        }

        @Test
        @DisplayName("reports bearing clockwise from north")
        void bearingIsClockwiseFromNorth() {
            LatLon origin = new LatLon(0.0, 0.0);

            assertThat(GeoUtils.bearingDegrees(origin, new LatLon(1.0, 0.0))).isCloseTo(0.0, within(0.5));
            assertThat(GeoUtils.bearingDegrees(origin, new LatLon(0.0, 1.0))).isCloseTo(90.0, within(0.5));
            assertThat(GeoUtils.bearingDegrees(origin, new LatLon(-1.0, 0.0))).isCloseTo(180.0, within(0.5));
            assertThat(GeoUtils.bearingDegrees(origin, new LatLon(0.0, -1.0))).isCloseTo(270.0, within(0.5));
        }
    }

    @Nested
    @DisplayName("point in polygon")
    class PointInPolygon {

        @Test
        @DisplayName("finds a point in the interior")
        void interiorPointIsInside() {
            assertThat(GeoUtils.pointInPolygon(new LatLon(19.005, 72.805), SQUARE)).isTrue();
        }

        @Test
        @DisplayName("excludes a point outside")
        void exteriorPointIsOutside() {
            assertThat(GeoUtils.pointInPolygon(new LatLon(19.05, 72.85), SQUARE)).isFalse();
        }

        @Test
        @DisplayName("treats a point exactly on an edge as inside")
        void edgePointIsInside() {
            // Documented contract: a vehicle stopped on the fence line is at the
            // terminal. Ray casting alone leaves this undefined, so it must be
            // handled explicitly -- otherwise a parked truck oscillates in and
            // out of the geofence and spams state transitions.
            assertThat(GeoUtils.pointInPolygon(new LatLon(19.00, 72.805), SQUARE)).isTrue();
        }

        @Test
        @DisplayName("treats a vertex as inside")
        void vertexIsInside() {
            assertThat(GeoUtils.pointInPolygon(new LatLon(19.00, 72.80), SQUARE)).isTrue();
        }

        @Test
        @DisplayName("counts a vertex on the test ray only once")
        void vertexOnRayCountedOnce() {
            // A point whose latitude exactly equals a vertex's latitude sends
            // the ray through that vertex. Naive implementations count the
            // crossing twice and invert the answer.
            List<LatLon> diamond = List.of(
                    new LatLon(10.0, 0.0),
                    new LatLon(0.0, 10.0),
                    new LatLon(-10.0, 0.0),
                    new LatLon(0.0, -10.0));

            assertThat(GeoUtils.pointInPolygon(new LatLon(0.0, 0.0), diamond)).isTrue();
            assertThat(GeoUtils.pointInPolygon(new LatLon(0.0, 20.0), diamond)).isFalse();
        }

        @Test
        @DisplayName("handles a concave polygon")
        void handlesConcavePolygon() {
            // An L-shape. The notch must read as outside even though it sits
            // inside the bounding box.
            List<LatLon> lShape = List.of(
                    new LatLon(0.0, 0.0),
                    new LatLon(0.0, 10.0),
                    new LatLon(5.0, 10.0),
                    new LatLon(5.0, 5.0),
                    new LatLon(10.0, 5.0),
                    new LatLon(10.0, 0.0));

            assertThat(GeoUtils.pointInPolygon(new LatLon(2.0, 2.0), lShape)).isTrue();
            assertThat(GeoUtils.pointInPolygon(new LatLon(8.0, 8.0), lShape)).isFalse();
        }

        @Test
        @DisplayName("accepts a ring given closed or open")
        void acceptsClosedAndOpenRings() {
            List<LatLon> closed = List.of(
                    new LatLon(19.00, 72.80),
                    new LatLon(19.00, 72.81),
                    new LatLon(19.01, 72.81),
                    new LatLon(19.01, 72.80),
                    new LatLon(19.00, 72.80));
            LatLon inside = new LatLon(19.005, 72.805);

            assertThat(GeoUtils.pointInPolygon(inside, closed)).isTrue();
            assertThat(GeoUtils.pointInPolygon(inside, SQUARE)).isTrue();
        }

        @Test
        @DisplayName("returns false for a degenerate ring rather than throwing")
        void degenerateRingIsNeverInside() {
            assertThat(GeoUtils.pointInPolygon(new LatLon(0.0, 0.0), List.of())).isFalse();
            assertThat(GeoUtils.pointInPolygon(
                    new LatLon(0.0, 0.0),
                    List.of(new LatLon(0.0, 0.0), new LatLon(1.0, 1.0)))).isFalse();
        }

        @Test
        @DisplayName("supports point-radius terminals")
        void pointRadiusWorks() {
            LatLon centre = new LatLon(19.0, 72.8);

            assertThat(GeoUtils.pointInRadius(new LatLon(19.001, 72.8), centre, 200)).isTrue();
            assertThat(GeoUtils.pointInRadius(new LatLon(19.010, 72.8), centre, 200)).isFalse();
        }
    }

    @Nested
    @DisplayName("polygon overlap")
    class PolygonOverlap {

        @Test
        @DisplayName("detects crossing edges")
        void detectsCrossingEdges() {
            List<LatLon> other = List.of(
                    new LatLon(19.005, 72.805),
                    new LatLon(19.005, 72.815),
                    new LatLon(19.015, 72.815),
                    new LatLon(19.015, 72.805));

            assertThat(GeoUtils.polygonsOverlap(SQUARE, other)).isTrue();
        }

        @Test
        @DisplayName("detects full containment, which edge tests alone miss")
        void detectsContainment() {
            // The case that matters most operationally: a small warehouse drawn
            // entirely inside an existing port boundary. No edges cross, so an
            // implementation that only tests segment intersection says "no
            // overlap" and lets the duplicate terminal through.
            List<LatLon> inner = List.of(
                    new LatLon(19.002, 72.802),
                    new LatLon(19.002, 72.803),
                    new LatLon(19.003, 72.803),
                    new LatLon(19.003, 72.802));

            assertThat(GeoUtils.polygonsOverlap(SQUARE, inner)).isTrue();
            assertThat(GeoUtils.polygonsOverlap(inner, SQUARE)).isTrue();
        }

        @Test
        @DisplayName("reports no overlap for disjoint polygons")
        void disjointPolygonsDoNotOverlap() {
            List<LatLon> far = List.of(
                    new LatLon(20.00, 73.80),
                    new LatLon(20.00, 73.81),
                    new LatLon(20.01, 73.81),
                    new LatLon(20.01, 73.80));

            assertThat(GeoUtils.polygonsOverlap(SQUARE, far)).isFalse();
        }

        @Test
        @DisplayName("treats touching edges as overlapping")
        void touchingEdgesOverlap() {
            List<LatLon> adjacent = List.of(
                    new LatLon(19.00, 72.81),
                    new LatLon(19.00, 72.82),
                    new LatLon(19.01, 72.82),
                    new LatLon(19.01, 72.81));

            assertThat(GeoUtils.polygonsOverlap(SQUARE, adjacent)).isTrue();
        }

        @Test
        @DisplayName("is symmetric")
        void overlapIsSymmetric() {
            List<LatLon> other = List.of(
                    new LatLon(19.005, 72.805),
                    new LatLon(19.005, 72.815),
                    new LatLon(19.015, 72.815),
                    new LatLon(19.015, 72.805));

            assertThat(GeoUtils.polygonsOverlap(SQUARE, other))
                    .isEqualTo(GeoUtils.polygonsOverlap(other, SQUARE));
        }
    }

    @Nested
    @DisplayName("route deviation")
    class RouteDeviation {

        private final List<LatLon> route = List.of(
                new LatLon(19.00, 72.80),
                new LatLon(19.00, 72.90),
                new LatLon(19.10, 72.90));

        @Test
        @DisplayName("is near zero for a point on the route")
        void onRouteIsNearZero() {
            assertThat(GeoUtils.distanceToPolylineMetres(new LatLon(19.00, 72.85), route))
                    .isLessThan(1.0);
        }

        @Test
        @DisplayName("measures perpendicular distance from the route")
        void measuresPerpendicularDistance() {
            // 0.01 degrees of latitude is about 1113 m.
            double metres = GeoUtils.distanceToPolylineMetres(new LatLon(19.01, 72.85), route);

            assertThat(metres).isCloseTo(1113.0, within(30.0));
        }

        @Test
        @DisplayName("clamps to the segment rather than the infinite line")
        void clampsToSegmentEnds() {
            // A point beyond the start of the route must measure to the
            // endpoint, not to a projection onto the extended line -- otherwise
            // a vehicle heading away from the origin looks like it is still on
            // route.
            double metres = GeoUtils.distanceToPolylineMetres(new LatLon(19.00, 72.70), route);

            assertThat(metres).isGreaterThan(10_000.0);
        }

        @Test
        @DisplayName("treats an absent route as maximally distant, never as on-route")
        void emptyRouteIsMaximallyDistant() {
            assertThat(GeoUtils.distanceToPolylineMetres(new LatLon(19.0, 72.8), List.of()))
                    .isEqualTo(Double.MAX_VALUE);
            assertThat(GeoUtils.distanceToPolylineMetres(new LatLon(19.0, 72.8), null))
                    .isEqualTo(Double.MAX_VALUE);
        }

        @Test
        @DisplayName("handles a single-point route")
        void singlePointRoute() {
            assertThat(GeoUtils.distanceToPolylineMetres(
                    new LatLon(19.01, 72.80), List.of(new LatLon(19.00, 72.80))))
                    .isCloseTo(1113.0, within(30.0));
        }

        @Test
        @DisplayName("handles a zero-length segment without dividing by zero")
        void degenerateSegment() {
            LatLon p = new LatLon(19.01, 72.80);
            List<LatLon> degenerate = List.of(new LatLon(19.00, 72.80), new LatLon(19.00, 72.80));

            assertThat(GeoUtils.distanceToSegmentMetres(p, degenerate.get(0), degenerate.get(1)))
                    .isCloseTo(1113.0, within(30.0));
        }
    }

    @Nested
    @DisplayName("bounding boxes")
    class Boxes {

        @Test
        @DisplayName("computes the extent of a ring")
        void computesExtent() {
            BoundingBox box = GeoUtils.boundingBoxOf(SQUARE);

            assertThat(box.minLat()).isEqualTo(19.00);
            assertThat(box.maxLat()).isEqualTo(19.01);
            assertThat(box.minLon()).isEqualTo(72.80);
            assertThat(box.maxLon()).isEqualTo(72.81);
        }

        @Test
        @DisplayName("contains points on its own boundary")
        void containsBoundaryPoints() {
            BoundingBox box = GeoUtils.boundingBoxOf(SQUARE);

            assertThat(box.contains(new LatLon(19.00, 72.80))).isTrue();
            assertThat(box.contains(new LatLon(19.005, 72.805))).isTrue();
            assertThat(box.contains(new LatLon(19.02, 72.805))).isFalse();
        }

        @Test
        @DisplayName("detects intersection between boxes")
        void detectsIntersection() {
            BoundingBox a = new BoundingBox(19.00, 19.01, 72.80, 72.81);
            BoundingBox overlapping = new BoundingBox(19.005, 19.02, 72.805, 72.82);
            BoundingBox disjoint = new BoundingBox(20.00, 20.01, 73.80, 73.81);

            assertThat(a.intersects(overlapping)).isTrue();
            assertThat(a.intersects(disjoint)).isFalse();
        }

        @Test
        @DisplayName("expands by at least the requested distance")
        void expandsGenerously() {
            // A box that is slightly too large costs one extra candidate to
            // check. One that is too small silently loses a match, so the
            // expansion must never under-shoot.
            BoundingBox box = new BoundingBox(19.00, 19.01, 72.80, 72.81);
            BoundingBox expanded = box.expandedBy(1000);

            LatLon justOutside = new LatLon(19.0, 72.7912); // ~930 m west
            assertThat(box.contains(justOutside)).isFalse();
            assertThat(expanded.contains(justOutside)).isTrue();
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("accepts a well-formed terminal polygon")
        void acceptsValidRing() {
            GeoUtils.validateRing(SQUARE);
        }

        @Test
        @DisplayName("rejects a ring with too few vertices")
        void rejectsTooFewVertices() {
            assertThatThrownBy(() -> GeoUtils.validateRing(
                    List.of(new LatLon(0.0, 0.0), new LatLon(1.0, 1.0))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least 3");
        }

        @Test
        @DisplayName("rejects a ring spanning an implausible distance")
        void rejectsOversizedRing() {
            List<LatLon> continental = List.of(
                    new LatLon(10.0, 70.0),
                    new LatLon(10.0, 80.0),
                    new LatLon(20.0, 80.0),
                    new LatLon(20.0, 70.0));

            assertThatThrownBy(() -> GeoUtils.validateRing(continental))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("spans more than");
        }

        @Test
        @DisplayName("rejects a ring near the poles")
        void rejectsPolarRing() {
            List<LatLon> polar = List.of(
                    new LatLon(87.0, 0.0),
                    new LatLon(87.0, 1.0),
                    new LatLon(88.0, 1.0),
                    new LatLon(88.0, 0.0));

            assertThatThrownBy(() -> GeoUtils.validateRing(polar))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("latitude");
        }

        @Test
        @DisplayName("rejects a self-intersecting ring")
        void rejectsSelfIntersectingRing() {
            // A bow-tie. Point-in-polygon is genuinely ambiguous here, so the
            // right answer is to refuse the input rather than pick a lobe.
            List<LatLon> bowTie = List.of(
                    new LatLon(0.0, 0.0),
                    new LatLon(1.0, 1.0),
                    new LatLon(0.0, 1.0),
                    new LatLon(1.0, 0.0));

            assertThatThrownBy(() -> GeoUtils.validateRing(bowTie))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("self-intersecting");
        }

        @Test
        @DisplayName("rejects coordinates outside the valid range at construction")
        void rejectsOutOfRangeCoordinates() {
            assertThatThrownBy(() -> new LatLon(91.0, 0.0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Latitude");
            assertThatThrownBy(() -> new LatLon(0.0, 181.0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Longitude");
            assertThatThrownBy(() -> new LatLon(Double.NaN, 0.0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("finite");
        }
    }

    @Nested
    @DisplayName("GeoJSON coordinate order")
    class CoordinateOrder {

        @Test
        @DisplayName("round-trips through [lon, lat] pairs")
        void roundTrips() {
            // Getting this backwards is the single most common geospatial bug,
            // and it produces coordinates that are still valid -- just in the
            // wrong hemisphere -- so nothing throws.
            double[][] lonLat = {{72.80, 19.00}, {72.81, 19.00}, {72.81, 19.01}};

            List<LatLon> ring = GeoUtils.ringFromLonLat(lonLat);

            assertThat(ring.get(0).lat()).isEqualTo(19.00);
            assertThat(ring.get(0).lon()).isEqualTo(72.80);
            assertThat(GeoUtils.ringToLonLat(ring)).isDeepEqualTo(lonLat);
        }

        @Test
        @DisplayName("rejects malformed coordinate pairs")
        void rejectsMalformedPairs() {
            assertThatThrownBy(() -> GeoUtils.ringFromLonLat(new double[][]{{72.8}}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("[lon, lat]");
        }
    }
}
