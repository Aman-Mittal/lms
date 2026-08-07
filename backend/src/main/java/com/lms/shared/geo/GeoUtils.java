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

import java.util.ArrayList;
import java.util.List;

/**
 * The platform's entire geospatial toolkit.
 *
 * <p>PostGIS is GPLv2 and would compromise this project's Apache-2.0
 * distribution, so the handful of operations the platform actually needs are
 * implemented here instead. See DOCS/adr/0004 for the full reasoning and the
 * limitations this accepts.
 *
 * <h2>Model and its limits</h2>
 *
 * <p>Distances use the haversine formula on a sphere of mean earth radius --
 * accurate to roughly 0.5% against the WGS-84 ellipsoid, which is far inside the
 * tolerance of anything here (geofences are tens to hundreds of metres, route
 * deviation thresholds are kilometres).
 *
 * <p>Polygon operations treat the region as locally planar, using longitude as
 * x and latitude as y. That is sound for terminal-sized shapes -- warehouses,
 * plants, ports -- and wrong for continent-sized ones. Rather than return a
 * quietly incorrect answer, {@link #validateRing} rejects rings that span more
 * than {@value #MAX_RING_SPAN_DEGREES} degrees or reach beyond
 * {@value #MAX_ABSOLUTE_LATITUDE} degrees of latitude, and rings that would
 * cross the antimeridian.
 *
 * <h2>Boundary semantics</h2>
 *
 * <p>A point exactly on a polygon edge counts as <em>inside</em>. Ray casting
 * alone leaves this case undefined, so it is tested for explicitly. The choice
 * matters operationally: a vehicle stopped on a geofence line should be treated
 * as at the terminal, not oscillating in and out of it.
 */
public final class GeoUtils {

    /** Mean earth radius in metres (IUGG). */
    public static final double EARTH_RADIUS_METRES = 6_371_008.8;

    /** Metres per degree of latitude; constant enough for bounding-box padding. */
    public static final double METRES_PER_DEGREE_LATITUDE = 111_320.0;

    /** Widest span a ring may cover in either axis before it is rejected. */
    public static final double MAX_RING_SPAN_DEGREES = 5.0;

    /** Beyond this latitude the planar approximation stops being defensible. */
    public static final double MAX_ABSOLUTE_LATITUDE = 85.0;

    /** Tolerance for "on the edge" and degenerate-segment tests, in degrees. */
    private static final double EPSILON = 1e-12;

    private GeoUtils() {
    }

    // ------------------------------------------------------------------
    // Distance
    // ------------------------------------------------------------------

    /** Great-circle distance between two coordinates, in metres. */
    public static double haversineMetres(LatLon a, LatLon b) {
        double lat1 = Math.toRadians(a.lat());
        double lat2 = Math.toRadians(b.lat());
        double deltaLat = lat2 - lat1;
        double deltaLon = Math.toRadians(b.lon() - a.lon());

        double h = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);

        // Clamp guards against a hair over 1.0 from floating-point error, which
        // would make asin produce NaN for two nearly antipodal points.
        return 2 * EARTH_RADIUS_METRES * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }

    /**
     * Initial bearing from {@code a} to {@code b}, in degrees clockwise from
     * true north.
     */
    public static double bearingDegrees(LatLon a, LatLon b) {
        double lat1 = Math.toRadians(a.lat());
        double lat2 = Math.toRadians(b.lat());
        double deltaLon = Math.toRadians(b.lon() - a.lon());

        double y = Math.sin(deltaLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(deltaLon);

        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
    }

    // ------------------------------------------------------------------
    // Point in polygon
    // ------------------------------------------------------------------

    /**
     * True if the point lies inside the ring, or exactly on its boundary.
     *
     * <p>Even-odd ray casting. The ring may be given open or closed; a repeated
     * final vertex is handled either way.
     */
    public static boolean pointInPolygon(LatLon point, List<LatLon> ring) {
        List<LatLon> r = openRing(ring);
        if (r.size() < 3) {
            return false;
        }
        if (isOnBoundary(point, r)) {
            return true;
        }

        double x = point.lon();
        double y = point.lat();
        boolean inside = false;

        for (int i = 0, j = r.size() - 1; i < r.size(); j = i++) {
            double xi = r.get(i).lon();
            double yi = r.get(i).lat();
            double xj = r.get(j).lon();
            double yj = r.get(j).lat();

            // The asymmetric comparison (> vs >=) is what makes a vertex lying
            // exactly on the ray count once rather than twice.
            boolean straddles = (yi > y) != (yj > y);
            if (straddles && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** True if the point lies on any edge of the ring, within tolerance. */
    public static boolean isOnBoundary(LatLon point, List<LatLon> ring) {
        List<LatLon> r = openRing(ring);
        for (int i = 0, j = r.size() - 1; i < r.size(); j = i++) {
            if (isOnSegment(r.get(j), r.get(i), point)) {
                return true;
            }
        }
        return false;
    }

    /** True if the point is within {@code radiusMetres} of the centre. */
    public static boolean pointInRadius(LatLon point, LatLon centre, double radiusMetres) {
        return haversineMetres(point, centre) <= radiusMetres;
    }

    // ------------------------------------------------------------------
    // Polygon overlap
    // ------------------------------------------------------------------

    /**
     * True if two rings share any area.
     *
     * <p>Backs the rule in §3.2.3 that a new terminal must not overlap an
     * existing terminal of the same functional category. Three cases must all
     * be covered: crossing edges, {@code a} entirely inside {@code b}, and
     * {@code b} entirely inside {@code a}. Testing edge intersection alone
     * misses containment, which is the case that matters most -- a small
     * warehouse drawn wholly inside an existing port boundary.
     */
    public static boolean polygonsOverlap(List<LatLon> a, List<LatLon> b) {
        List<LatLon> ra = openRing(a);
        List<LatLon> rb = openRing(b);
        if (ra.size() < 3 || rb.size() < 3) {
            return false;
        }

        // Cheap rejection first.
        if (!boundingBoxOf(ra).intersects(boundingBoxOf(rb))) {
            return false;
        }

        for (int i = 0, j = ra.size() - 1; i < ra.size(); j = i++) {
            for (int k = 0, l = rb.size() - 1; k < rb.size(); l = k++) {
                if (segmentsIntersect(ra.get(j), ra.get(i), rb.get(l), rb.get(k))) {
                    return true;
                }
            }
        }

        return pointInPolygon(ra.get(0), rb) || pointInPolygon(rb.get(0), ra);
    }

    /** True if segment p1-p2 intersects segment p3-p4, touching included. */
    public static boolean segmentsIntersect(LatLon p1, LatLon p2, LatLon p3, LatLon p4) {
        double d1 = cross(p3, p4, p1);
        double d2 = cross(p3, p4, p2);
        double d3 = cross(p1, p2, p3);
        double d4 = cross(p1, p2, p4);

        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0))
                && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) {
            return true;
        }

        // Collinear touching cases.
        return (Math.abs(d1) < EPSILON && isOnSegment(p3, p4, p1))
                || (Math.abs(d2) < EPSILON && isOnSegment(p3, p4, p2))
                || (Math.abs(d3) < EPSILON && isOnSegment(p1, p2, p3))
                || (Math.abs(d4) < EPSILON && isOnSegment(p1, p2, p4));
    }

    // ------------------------------------------------------------------
    // Route deviation
    // ------------------------------------------------------------------

    /**
     * Shortest distance in metres from a point to a polyline.
     *
     * <p>Drives the route-deviation alert in §3.7.2. Returns
     * {@link Double#MAX_VALUE} for an empty line so that "no planned route" can
     * never be mistaken for "on route".
     */
    public static double distanceToPolylineMetres(LatLon point, List<LatLon> polyline) {
        if (polyline == null || polyline.isEmpty()) {
            return Double.MAX_VALUE;
        }
        if (polyline.size() == 1) {
            return haversineMetres(point, polyline.get(0));
        }

        double best = Double.MAX_VALUE;
        for (int i = 1; i < polyline.size(); i++) {
            best = Math.min(best, distanceToSegmentMetres(point, polyline.get(i - 1), polyline.get(i)));
        }
        return best;
    }

    /**
     * Shortest distance in metres from a point to a single segment.
     *
     * <p>Projects into a local east-north plane centred on the query point,
     * which keeps the trigonometry to a few cosines and stays accurate over the
     * distances involved.
     */
    public static double distanceToSegmentMetres(LatLon point, LatLon start, LatLon end) {
        double cosLat = Math.cos(Math.toRadians(point.lat()));

        double px = 0.0;
        double py = 0.0;
        double ax = (start.lon() - point.lon()) * cosLat * METRES_PER_DEGREE_LATITUDE;
        double ay = (start.lat() - point.lat()) * METRES_PER_DEGREE_LATITUDE;
        double bx = (end.lon() - point.lon()) * cosLat * METRES_PER_DEGREE_LATITUDE;
        double by = (end.lat() - point.lat()) * METRES_PER_DEGREE_LATITUDE;

        double dx = bx - ax;
        double dy = by - ay;
        double lengthSquared = dx * dx + dy * dy;

        if (lengthSquared < EPSILON) {
            // Degenerate segment: both endpoints coincide.
            return haversineMetres(point, start);
        }

        // Parameter of the closest point on the infinite line, clamped to the
        // segment so the result is a distance to the segment, not to the line.
        double t = Math.max(0.0, Math.min(1.0, ((px - ax) * dx + (py - ay) * dy) / lengthSquared));
        double closestX = ax + t * dx;
        double closestY = ay + t * dy;

        return Math.hypot(px - closestX, py - closestY);
    }

    // ------------------------------------------------------------------
    // Bounding boxes and validation
    // ------------------------------------------------------------------

    /** Axis-aligned bounding box of a ring. */
    public static BoundingBox boundingBoxOf(List<LatLon> ring) {
        if (ring == null || ring.isEmpty()) {
            throw new IllegalArgumentException("Cannot compute a bounding box for an empty ring");
        }
        double minLat = Double.MAX_VALUE;
        double maxLat = -Double.MAX_VALUE;
        double minLon = Double.MAX_VALUE;
        double maxLon = -Double.MAX_VALUE;

        for (LatLon p : ring) {
            minLat = Math.min(minLat, p.lat());
            maxLat = Math.max(maxLat, p.lat());
            minLon = Math.min(minLon, p.lon());
            maxLon = Math.max(maxLon, p.lon());
        }
        return new BoundingBox(minLat, maxLat, minLon, maxLon);
    }

    /**
     * Rejects rings this implementation cannot handle correctly.
     *
     * <p>Deliberately strict. Every rule here marks a case where the planar
     * approximation would produce a plausible but wrong answer, and a wrong
     * geofence silently mis-drives the trip state machine.
     *
     * @throws IllegalArgumentException with a message naming the specific rule
     */
    public static void validateRing(List<LatLon> ring) {
        if (ring == null) {
            throw new IllegalArgumentException("Polygon ring must not be null");
        }
        List<LatLon> r = openRing(ring);
        if (r.size() < 3) {
            throw new IllegalArgumentException(
                    "Polygon needs at least 3 distinct vertices, got " + r.size());
        }

        BoundingBox box = boundingBoxOf(r);

        if (Math.abs(box.minLat()) > MAX_ABSOLUTE_LATITUDE
                || Math.abs(box.maxLat()) > MAX_ABSOLUTE_LATITUDE) {
            throw new IllegalArgumentException(
                    "Polygon extends beyond " + MAX_ABSOLUTE_LATITUDE
                            + " degrees latitude, where the planar approximation is unsafe");
        }

        double lonSpan = box.maxLon() - box.minLon();
        double latSpan = box.maxLat() - box.minLat();

        if (lonSpan > 180.0) {
            throw new IllegalArgumentException(
                    "Polygon appears to cross the antimeridian, which is not supported");
        }
        if (lonSpan > MAX_RING_SPAN_DEGREES || latSpan > MAX_RING_SPAN_DEGREES) {
            throw new IllegalArgumentException(
                    "Polygon spans more than " + MAX_RING_SPAN_DEGREES
                            + " degrees; terminals are expected to be local features");
        }
        if (isSelfIntersecting(r)) {
            throw new IllegalArgumentException(
                    "Polygon is self-intersecting; point-in-polygon results would be ambiguous");
        }
    }

    /** True if any two non-adjacent edges of the ring cross. */
    public static boolean isSelfIntersecting(List<LatLon> ring) {
        List<LatLon> r = openRing(ring);
        int n = r.size();
        if (n < 4) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            LatLon a1 = r.get(i);
            LatLon a2 = r.get((i + 1) % n);
            for (int j = i + 1; j < n; j++) {
                // Skip edges that share a vertex -- they always "touch".
                if (j == i || (j + 1) % n == i || (i + 1) % n == j) {
                    continue;
                }
                if (segmentsIntersect(a1, a2, r.get(j), r.get((j + 1) % n))) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // GeoJSON-order conversion -- the single place lon/lat order is handled
    // ------------------------------------------------------------------

    /** Builds a ring from GeoJSON-order {@code [lon, lat]} pairs. */
    public static List<LatLon> ringFromLonLat(double[][] lonLatPairs) {
        if (lonLatPairs == null) {
            throw new IllegalArgumentException("Ring coordinates must not be null");
        }
        List<LatLon> ring = new ArrayList<>(lonLatPairs.length);
        for (double[] pair : lonLatPairs) {
            if (pair == null || pair.length < 2) {
                throw new IllegalArgumentException("Each coordinate must be a [lon, lat] pair");
            }
            ring.add(new LatLon(pair[1], pair[0]));
        }
        return ring;
    }

    /** Serialises a ring back to GeoJSON-order {@code [lon, lat]} pairs. */
    public static double[][] ringToLonLat(List<LatLon> ring) {
        double[][] out = new double[ring.size()][2];
        for (int i = 0; i < ring.size(); i++) {
            out[i][0] = ring.get(i).lon();
            out[i][1] = ring.get(i).lat();
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Drops a duplicated closing vertex so callers may pass rings in either
     * convention without every algorithm having to care.
     */
    private static List<LatLon> openRing(List<LatLon> ring) {
        if (ring == null || ring.size() < 2) {
            return ring == null ? List.of() : ring;
        }
        LatLon first = ring.get(0);
        LatLon last = ring.get(ring.size() - 1);
        if (Math.abs(first.lat() - last.lat()) < EPSILON && Math.abs(first.lon() - last.lon()) < EPSILON) {
            return ring.subList(0, ring.size() - 1);
        }
        return ring;
    }

    /** Twice the signed area of triangle (a, b, c); sign gives orientation. */
    /**
     * Rounds a coordinate to six decimal places.
     *
     * <p>About eleven centimetres at the equator, which is finer than any
     * vehicle GPS is accurate to. Serialising the full double instead would put
     * seventeen significant figures of floating-point noise into every stored
     * track, which on the highest-volume table in the platform is real bytes
     * spent representing precision that does not exist.
     */
    public static java.math.BigDecimal round6(double degrees) {
        return java.math.BigDecimal.valueOf(degrees)
                .setScale(6, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }

    private static double cross(LatLon a, LatLon b, LatLon c) {
        return (b.lon() - a.lon()) * (c.lat() - a.lat())
                - (b.lat() - a.lat()) * (c.lon() - a.lon());
    }

    /** True if c lies on segment a-b, assuming the three are near-collinear. */
    private static boolean isOnSegment(LatLon a, LatLon b, LatLon c) {
        if (Math.abs(cross(a, b, c)) > EPSILON) {
            return false;
        }
        return c.lon() >= Math.min(a.lon(), b.lon()) - EPSILON
                && c.lon() <= Math.max(a.lon(), b.lon()) + EPSILON
                && c.lat() >= Math.min(a.lat(), b.lat()) - EPSILON
                && c.lat() <= Math.max(a.lat(), b.lat()) + EPSILON;
    }
}
