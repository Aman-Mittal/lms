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
package com.lms.masterdata.command.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import com.lms.shared.config.Json;
import com.lms.shared.geo.BoundingBox;
import com.lms.shared.geo.GeoUtils;
import com.lms.shared.geo.LatLon;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A physical location digitised into a spatial boundary (vision document
 * 3.2.3): plants, warehouses, ports, hubs, toll plazas, customer sites.
 *
 * <p>The bounding box columns are stored rather than derived on read. They are
 * this platform's substitute for a spatial index (DOCS/adr/0004): the geofence
 * evaluator filters candidate terminals with an indexed range query on these
 * four columns, then runs exact point-in-polygon in Java over the survivors.
 * Keeping them in the aggregate means they cannot drift from the polygon —
 * both are written in the same operation.
 */
@Table("terminal")
public record Terminal(
        @Id UUID id,
        UUID tenantId,
        UUID orgUnitId,
        String code,
        String name,
        FunctionalCategory functionalCategory,
        GeofenceType geofenceType,
        Json polygon,
        BigDecimal centreLat,
        BigDecimal centreLon,
        BigDecimal radiusM,
        BigDecimal minLat,
        BigDecimal maxLat,
        BigDecimal minLon,
        BigDecimal maxLon,
        Integer dockCount,
        LocalTime opensAt,
        LocalTime closesAt,
        Integer avgDwellMinutes,
        String[] permittedVehicleTypes,
        /*
         * Required for correctness, not just concurrency. Spring Data JDBC
         * treats a record with a pre-assigned @Id as an existing row and issues
         * an UPDATE, which matches nothing and persists nothing without error.
         * A null version marks the aggregate as new.
         */
        @Version Long version,
        Instant createdAt,
        Instant updatedAt) {

    public enum FunctionalCategory {
        PLANT, WAREHOUSE, PORT, HUB, TOLL_PLAZA, CUSTOMER_SITE
    }

    public enum GeofenceType {
        POLYGON, POINT_RADIUS
    }

    /**
     * Creates a polygon terminal, validating the ring and deriving its box.
     *
     * <p>{@link GeoUtils#validateRing} is strict on purpose: it rejects
     * self-intersecting rings, rings spanning implausible distances, and rings
     * near the poles or across the antimeridian. A geofence that is subtly
     * wrong does not raise an error at runtime — it silently fails to trigger a
     * trip transition, or triggers one in the wrong place.
     */
    public static Terminal ofPolygon(UUID id, UUID tenantId, UUID orgUnitId, String code, String name,
                                     FunctionalCategory category, List<LatLon> ring,
                                     Integer dockCount, LocalTime opensAt, LocalTime closesAt,
                                     Integer avgDwellMinutes, String[] permittedVehicleTypes) {
        GeoUtils.validateRing(ring);
        BoundingBox box = GeoUtils.boundingBoxOf(ring);

        return new Terminal(id, tenantId, orgUnitId, code, name, category, GeofenceType.POLYGON,
                Json.of(toGeoJsonRing(ring)), null, null, null,
                bd(box.minLat()), bd(box.maxLat()), bd(box.minLon()), bd(box.maxLon()),
                dockCount, opensAt, closesAt, avgDwellMinutes, permittedVehicleTypes,
                null, Instant.now(), Instant.now());
    }

    /** Creates a point-radius terminal, deriving a box that encloses the circle. */
    public static Terminal ofPointRadius(UUID id, UUID tenantId, UUID orgUnitId, String code, String name,
                                         FunctionalCategory category, LatLon centre, double radiusMetres,
                                         Integer dockCount, LocalTime opensAt, LocalTime closesAt,
                                         Integer avgDwellMinutes, String[] permittedVehicleTypes) {
        if (radiusMetres <= 0) {
            throw new IllegalArgumentException("Terminal radius must be positive: " + radiusMetres);
        }
        BoundingBox box = new BoundingBox(centre.lat(), centre.lat(), centre.lon(), centre.lon())
                .expandedBy(radiusMetres);

        return new Terminal(id, tenantId, orgUnitId, code, name, category, GeofenceType.POINT_RADIUS,
                null, bd(centre.lat()), bd(centre.lon()), BigDecimal.valueOf(radiusMetres),
                bd(box.minLat()), bd(box.maxLat()), bd(box.minLon()), bd(box.maxLon()),
                dockCount, opensAt, closesAt, avgDwellMinutes, permittedVehicleTypes,
                null, Instant.now(), Instant.now());
    }

    /** The stored bounding box, for candidate filtering. */
    public BoundingBox boundingBox() {
        return new BoundingBox(minLat.doubleValue(), maxLat.doubleValue(),
                minLon.doubleValue(), maxLon.doubleValue());
    }

    /** The polygon ring, or an empty list for a point-radius terminal. */
    public List<LatLon> ring() {
        return polygon == null ? List.of() : fromGeoJsonRing(polygon.value());
    }

    /**
     * Whether a coordinate is inside this geofence.
     *
     * <p>A point exactly on a polygon edge counts as inside — a vehicle stopped
     * on the fence line is at the terminal, not oscillating in and out of it and
     * spamming state transitions.
     */
    public boolean contains(LatLon point) {
        if (geofenceType == GeofenceType.POINT_RADIUS) {
            return GeoUtils.pointInRadius(point,
                    new LatLon(centreLat.doubleValue(), centreLon.doubleValue()),
                    radiusM.doubleValue());
        }
        return GeoUtils.pointInPolygon(point, ring());
    }

    /**
     * Whether this terminal shares any area with another.
     *
     * <p>Backs the 3.2.3 rule that a new terminal must not overlap an existing
     * one of the same functional category. Point-radius shapes are compared by
     * centre distance against the sum of radii; mixed shapes fall back to a
     * containment test, which is adequate at terminal scale and honest about
     * what it does not do (a circle clipping a polygon edge without containing
     * any vertex is not detected).
     */
    public boolean overlaps(Terminal other) {
        if (!boundingBox().intersects(other.boundingBox())) {
            return false;
        }
        if (geofenceType == GeofenceType.POLYGON && other.geofenceType == GeofenceType.POLYGON) {
            return GeoUtils.polygonsOverlap(ring(), other.ring());
        }
        if (geofenceType == GeofenceType.POINT_RADIUS && other.geofenceType == GeofenceType.POINT_RADIUS) {
            double separation = GeoUtils.haversineMetres(centre(), other.centre());
            return separation < radiusM.doubleValue() + other.radiusM.doubleValue();
        }
        Terminal circle = geofenceType == GeofenceType.POINT_RADIUS ? this : other;
        Terminal poly = circle == this ? other : this;
        return poly.contains(circle.centre())
                || poly.ring().stream().anyMatch(circle::contains);
    }

    private LatLon centre() {
        return new LatLon(centreLat.doubleValue(), centreLon.doubleValue());
    }

    // Hand-rolled rather than routed through Jackson: the payload is a fixed
    // array of numeric pairs, and this keeps a reflective serializer out of the
    // native image for no loss of clarity.
    private static String toGeoJsonRing(List<LatLon> ring) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < ring.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('[').append(ring.get(i).lon()).append(',').append(ring.get(i).lat()).append(']');
        }
        return json.append(']').toString();
    }

    private static List<LatLon> fromGeoJsonRing(String json) {
        String body = json.trim();
        if (body.length() < 2) {
            return List.of();
        }
        body = body.substring(1, body.length() - 1).trim();
        if (body.isEmpty()) {
            return List.of();
        }
        return java.util.Arrays.stream(body.split("\\]\\s*,\\s*\\["))
                .map(pair -> pair.replace("[", "").replace("]", "").split(","))
                .map(parts -> new LatLon(
                        Double.parseDouble(parts[1].trim()),
                        Double.parseDouble(parts[0].trim())))
                .toList();
    }

    private static BigDecimal bd(double value) {
        return BigDecimal.valueOf(value);
    }
}
