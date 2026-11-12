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
package com.lms.telematics.command;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.masterdata.api.TerminalGeofencePort;
import com.lms.shared.audit.AuditTrail;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.geo.LatLon;
import com.lms.shared.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The road a lane is expected to take, imported rather than drawn (issue #223).
 *
 * <p>A lane is an origin and a destination terminal -- what a trip has -- and
 * holds at most one corridor: a polyline of GeoJSON-order {@code [lon, lat]}
 * positions, two to 5,000 of them, with an optional width. Importing for a lane
 * that has one replaces it; clearing one returns its trips to the straight line.
 * The platform measures against it ({@link RouteDeviationService}) and draws no
 * routes of its own (DOCS/adr/0004). The right is {@code TELEMATICS_INGEST},
 * the integration's, since corridors come from a routing system.
 */
@Service
public class RouteCorridorService {

    /** A corridor as a trip is measured against it: the positions, and its own width when it has one. */
    public record Corridor(List<LatLon> points, BigDecimal widthMetres) {
    }

    private static final int MAX_POINTS = 5000;

    private final JdbcClient jdbc;
    private final TerminalGeofencePort terminals;
    private final AuditTrail audit;

    public RouteCorridorService(JdbcClient jdbc, TerminalGeofencePort terminals, AuditTrail audit) {
        this.jdbc = jdbc;
        this.terminals = terminals;
        this.audit = audit;
    }

    /**
     * Imports (or replaces) the corridor of a lane.
     *
     * @param points     {@code [lon, lat]} pairs, in the order travelled
     * @param widthMetres how far off the polyline a vehicle may be; null takes
     *                   the trip's {@code telematics.routeDeviation.corridorMetres}
     * @return the corridor's identifier
     */
    @Transactional
    @PreAuthorize("hasAuthority('TELEMATICS_INGEST')")
    public UUID importCorridor(UUID originTerminalId, UUID destinationTerminalId, String name,
                               List<double[]> points, BigDecimal widthMetres) {
        UUID tenantId = TenantContext.requireTenantId();
        if (originTerminalId.equals(destinationTerminalId)) {
            throw new BusinessRuleViolationException("route-corridor-lane-invalid",
                    "A corridor runs between two different terminals");
        }
        for (UUID terminal : List.of(originTerminalId, destinationTerminalId)) {
            if (terminals.centreOf(terminal).isEmpty()) {
                throw new ResourceNotFoundException("Terminal", terminal);
            }
        }
        if (points == null || points.size() < 2 || points.size() > MAX_POINTS) {
            throw new BusinessRuleViolationException("route-corridor-points-invalid",
                    "A corridor has between 2 and " + MAX_POINTS + " positions");
        }
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < points.size(); i++) {
            double[] point = points.get(i);
            if (point == null || point.length != 2) {
                throw new BusinessRuleViolationException("route-corridor-points-invalid",
                        "Position " + (i + 1) + " is not a [lon, lat] pair");
            }
            try {
                new LatLon(point[1], point[0]);
            } catch (IllegalArgumentException e) {
                throw new BusinessRuleViolationException("route-corridor-points-invalid",
                        "Position " + (i + 1) + " is not a valid coordinate: " + e.getMessage());
            }
            json.append(i == 0 ? "" : ",").append('[').append(point[0]).append(',').append(point[1]).append(']');
        }
        json.append(']');
        if (widthMetres != null && widthMetres.signum() <= 0) {
            throw new BusinessRuleViolationException("route-corridor-width-invalid",
                    "A corridor's width is a positive number of metres");
        }

        Optional<UUID> existing = jdbc.sql("""
                        SELECT id FROM route_corridor
                         WHERE tenant_id = :tenantId AND origin_terminal_id = :origin
                           AND destination_terminal_id = :destination
                        """)
                .param("tenantId", tenantId).param("origin", originTerminalId)
                .param("destination", destinationTerminalId).query(UUID.class).optional();
        UUID id = existing.orElseGet(UUID::randomUUID);
        if (existing.isPresent()) {
            jdbc.sql("""
                            UPDATE route_corridor
                               SET name = :name, points = CAST(:points AS jsonb), point_count = :count,
                                   width_metres = :width, version = coalesce(version, 0) + 1, updated_at = now()
                             WHERE tenant_id = :tenantId AND id = :id
                            """)
                    .param("name", name.strip()).param("points", json.toString()).param("count", points.size())
                    .param("width", widthMetres).param("tenantId", tenantId).param("id", id).update();
        } else {
            jdbc.sql("""
                            INSERT INTO route_corridor (id, tenant_id, origin_terminal_id, destination_terminal_id,
                                                        name, points, point_count, width_metres, version)
                            VALUES (:id, :tenantId, :origin, :destination, :name, CAST(:points AS jsonb), :count,
                                    :width, 0)
                            """)
                    .param("id", id).param("tenantId", tenantId).param("origin", originTerminalId)
                    .param("destination", destinationTerminalId).param("name", name.strip())
                    .param("points", json.toString()).param("count", points.size()).param("width", widthMetres)
                    .update();
        }
        audit.recordAtomically(existing.isPresent() ? "ROUTE_CORRIDOR_REPLACED" : "ROUTE_CORRIDOR_IMPORTED",
                "ROUTE_CORRIDOR", id, null, Json.object("name", name.strip(), "originTerminalId",
                        originTerminalId.toString(), "destinationTerminalId", destinationTerminalId.toString(),
                        "points", String.valueOf(points.size())));
        return id;
    }

    /** Clears a corridor; its lane's trips are measured against the straight line again. */
    @Transactional
    @PreAuthorize("hasAuthority('TELEMATICS_INGEST')")
    public void clear(UUID id) {
        UUID tenantId = TenantContext.requireTenantId();
        String name = jdbc.sql("SELECT name FROM route_corridor WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", id).query(String.class).optional()
                .orElseThrow(() -> new ResourceNotFoundException("RouteCorridor", id));
        jdbc.sql("DELETE FROM route_corridor WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", id).update();
        audit.recordAtomically("ROUTE_CORRIDOR_CLEARED", "ROUTE_CORRIDOR", id, Json.object("name", name), null);
    }

    /**
     * The corridor of a lane, or empty when it has none. Joins the caller's
     * transaction and checks no permission: asked by position ingest, once per
     * trip per batch.
     */
    @Transactional(readOnly = true)
    public Optional<Corridor> forLane(UUID originTerminalId, UUID destinationTerminalId) {
        if (originTerminalId == null || destinationTerminalId == null) {
            return Optional.empty();
        }
        UUID tenantId = TenantContext.requireTenantId();
        Optional<BigDecimal> width = jdbc.sql("""
                        SELECT coalesce(width_metres, -1) FROM route_corridor
                         WHERE tenant_id = :tenantId AND origin_terminal_id = :origin
                           AND destination_terminal_id = :destination
                        """)
                .param("tenantId", tenantId).param("origin", originTerminalId)
                .param("destination", destinationTerminalId).query(BigDecimal.class).optional();
        if (width.isEmpty()) {
            return Optional.empty();
        }
        List<LatLon> points = jdbc.sql("""
                        SELECT (e.p ->> 1)::double precision AS lat, (e.p ->> 0)::double precision AS lon
                          FROM route_corridor c, jsonb_array_elements(c.points) WITH ORDINALITY AS e(p, n)
                         WHERE c.tenant_id = :tenantId AND c.origin_terminal_id = :origin
                           AND c.destination_terminal_id = :destination
                         ORDER BY e.n
                        """)
                .param("tenantId", tenantId).param("origin", originTerminalId)
                .param("destination", destinationTerminalId)
                .query((rs, row) -> new LatLon(rs.getDouble("lat"), rs.getDouble("lon"))).list();
        return Optional.of(new Corridor(points, width.get().signum() < 0 ? null : width.get()));
    }
}
