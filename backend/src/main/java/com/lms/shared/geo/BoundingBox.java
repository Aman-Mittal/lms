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

/**
 * An axis-aligned bounding box in degrees.
 *
 * <p>This is the substitute for a spatial index (see DOCS/adr/0004). Terminals
 * persist their box as four indexed columns, so "which terminals could possibly
 * contain this point" is an ordinary B-tree range query, and the exact
 * point-in-polygon test then runs in Java over the handful of survivors.
 */
public record BoundingBox(double minLat, double maxLat, double minLon, double maxLon) {

    public BoundingBox {
        if (minLat > maxLat) {
            throw new IllegalArgumentException("minLat " + minLat + " exceeds maxLat " + maxLat);
        }
        if (minLon > maxLon) {
            throw new IllegalArgumentException("minLon " + minLon + " exceeds maxLon " + maxLon);
        }
    }

    /** True if the point falls within the box, boundary inclusive. */
    public boolean contains(LatLon point) {
        return point.lat() >= minLat && point.lat() <= maxLat
                && point.lon() >= minLon && point.lon() <= maxLon;
    }

    /**
     * True if the two boxes share any area, edges included. Used to shortlist
     * candidate terminals before the expensive polygon overlap test.
     */
    public boolean intersects(BoundingBox other) {
        return this.minLat <= other.maxLat && this.maxLat >= other.minLat
                && this.minLon <= other.maxLon && this.maxLon >= other.minLon;
    }

    /** Expands the box by the given distance in metres, for radius searches. */
    public BoundingBox expandedBy(double metres) {
        if (metres < 0) {
            throw new IllegalArgumentException("Expansion must not be negative: " + metres);
        }
        double latDelta = metres / GeoUtils.METRES_PER_DEGREE_LATITUDE;

        // A degree of longitude shrinks towards the poles. Use the latitude
        // furthest from the equator so the box is never too small -- a box that
        // is slightly too large costs an extra candidate, one that is too small
        // silently loses a match.
        double worstLat = Math.max(Math.abs(minLat), Math.abs(maxLat));
        double cos = Math.cos(Math.toRadians(Math.min(worstLat, 89.0)));
        double lonDelta = metres / (GeoUtils.METRES_PER_DEGREE_LATITUDE * Math.max(cos, 1e-6));

        return new BoundingBox(
                Math.max(minLat - latDelta, -90.0),
                Math.min(maxLat + latDelta, 90.0),
                Math.max(minLon - lonDelta, -180.0),
                Math.min(maxLon + lonDelta, 180.0));
    }
}
