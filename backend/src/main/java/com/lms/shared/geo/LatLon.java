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
 * A WGS-84 geographic coordinate.
 *
 * <p>Note the field order: latitude first. Serialized polygon rings use
 * {@code [lon, lat]} pairs to match GeoJSON, so conversion between the two
 * orders happens at exactly one place -- {@link GeoUtils#ringFromLonLat} --
 * rather than being open-coded wherever polygons are read.
 */
public record LatLon(double lat, double lon) {

    public LatLon {
        if (Double.isNaN(lat) || Double.isNaN(lon) || Double.isInfinite(lat) || Double.isInfinite(lon)) {
            throw new IllegalArgumentException("Coordinate must be finite: lat=" + lat + ", lon=" + lon);
        }
        if (lat < -90.0 || lat > 90.0) {
            throw new IllegalArgumentException("Latitude out of range [-90, 90]: " + lat);
        }
        if (lon < -180.0 || lon > 180.0) {
            throw new IllegalArgumentException("Longitude out of range [-180, 180]: " + lon);
        }
    }
}
