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
package com.lms.tracking.query;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import com.lms.shared.tenant.TenantContext;
import com.lms.tracking.command.TrackingLinkService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The public read: a token in, a small view out, with no caller to authorise.
 *
 * <p>Two steps, each in its own transaction. First the token's hash goes through
 * {@code resolve_tracking_link()}, the one SECURITY DEFINER lookup that needs no
 * tenant, and yields a tenant and a consignment -- or nothing, whether the token
 * was never issued, has expired or was revoked. Then the tenant is bound and the
 * view is read through row-level security exactly like an authenticated request,
 * so this route cannot reach a row its own tenant could not.
 */
@Service
public class PublicTrackingQueryService {

    private static final int MAX_TOKEN_LENGTH = 128;

    private record Target(UUID tenantId, UUID consignmentId) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate readOnly;

    public PublicTrackingQueryService(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    public Optional<PublicTrackingView> view(String token) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            return Optional.empty();
        }
        String hash = TrackingLinkService.hash(token);
        Target target = readOnly.execute(status -> jdbc
                .sql("SELECT tenant_id, consignment_id FROM resolve_tracking_link(:h)")
                .param("h", hash)
                .query((rs, row) -> new Target(rs.getObject("tenant_id", UUID.class),
                        rs.getObject("consignment_id", UUID.class)))
                .optional().orElse(null));
        if (target == null) {
            return Optional.empty();
        }
        // Scope first, transaction second: the transaction manager publishes the
        // scope onto the connection as the transaction starts.
        return TenantContext.callWith(target.tenantId(), null,
                () -> readOnly.execute(status -> read(target.consignmentId())));
    }

    private Optional<PublicTrackingView> read(UUID consignmentId) {
        return jdbc.sql("""
                        SELECT c.tracking_ref, c.status,
                               ot.name AS origin_name, dt.name AS destination_name,
                               o.requested_delivery_at,
                               EXISTS (SELECT 1 FROM load_consignment lc
                                         JOIN trip t ON t.load_id = lc.load_id
                                        WHERE lc.consignment_id = c.id
                                          AND t.status IN ('DISPATCHED', 'IN_TRANSIT',
                                                           'AT_DESTINATION', 'COMPLETED')) AS departed,
                               (SELECT max(t.completed_at) FROM load_consignment lc
                                         JOIN trip t ON t.load_id = lc.load_id
                                        WHERE lc.consignment_id = c.id
                                          AND t.status = 'COMPLETED') AS trip_completed_at,
                               (SELECT p.delivered_at FROM delivery_proof p
                                 WHERE p.consignment_id = c.id
                                   AND p.status = 'CONFIRMED') AS proof_at
                          FROM consignment c
                          JOIN sales_order o ON o.id = c.order_id
                          LEFT JOIN terminal ot ON ot.id = o.origin_terminal_id
                          JOIN terminal dt ON dt.id = c.destination_terminal_id
                         WHERE c.id = :id
                        """)
                .param("id", consignmentId)
                .query((rs, row) -> assemble(rs, consignmentId))
                .optional();
    }

    private PublicTrackingView assemble(ResultSet rs, UUID consignmentId) throws SQLException {
        String stored = rs.getString("status");
        Instant proofAt = instant(rs, "proof_at");
        Instant tripCompletedAt = instant(rs, "trip_completed_at");
        boolean departed = rs.getBoolean("departed");

        String status;
        if ("CANCELLED".equals(stored)) {
            status = "CANCELLED";
        } else if (proofAt != null || tripCompletedAt != null || "DELIVERED".equals(stored)) {
            status = "DELIVERED";
        } else if (departed || "IN_TRANSIT".equals(stored)) {
            status = "IN_TRANSIT";
        } else {
            status = "PLANNED";
        }
        Instant deliveredAt = "DELIVERED".equals(status)
                ? (proofAt != null ? proofAt : tripCompletedAt) : null;
        return new PublicTrackingView(rs.getString("tracking_ref"), status,
                rs.getString("origin_name"), rs.getString("destination_name"),
                instant(rs, "requested_delivery_at"), deliveredAt, proofAt != null,
                "IN_TRANSIT".equals(status) ? lastPosition(consignmentId) : null);
    }

    private PublicTrackingView.Position lastPosition(UUID consignmentId) {
        return jdbc.sql("""
                        SELECT ROUND(g.lat, 2) AS lat, ROUND(g.lon, 2) AS lon, g.recorded_at
                          FROM gps_ping g
                          JOIN trip t ON t.id = g.trip_id
                          JOIN load_consignment lc ON lc.load_id = t.load_id
                         WHERE lc.consignment_id = :id
                         ORDER BY g.recorded_at DESC
                         LIMIT 1
                        """)
                .param("id", consignmentId)
                .query((rs, row) -> new PublicTrackingView.Position(
                        rs.getBigDecimal("lat"), rs.getBigDecimal("lon"), instant(rs, "recorded_at")))
                .optional().orElse(null);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
