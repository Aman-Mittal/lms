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
package com.lms.multimodal.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.query.Slice;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side for itineraries.
 *
 * <p>The itinerary's status and cost are derived from its legs here rather
 * than stored on the header. Storing them would make every leg movement write
 * two aggregates, and the two would disagree the first time a trip event and
 * a rail milestone landed together.
 */
@Service
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('MULTIMODAL_READ')")
public class ItineraryQueryService {

    /**
     * Each correlated subquery is bounded by the leg index on
     * (tenant_id, itinerary_id, seq); an itinerary has at most ten legs.
     */
    private static final String SUMMARY = """
            SELECT i.id, i.itinerary_no, i.shipment_ref, i.currency, i.created_by, i.created_at,
                   (SELECT count(*) FROM itinerary_leg l
                     WHERE l.tenant_id = i.tenant_id AND l.itinerary_id = i.id)::int AS leg_count,
                   (SELECT coalesce(sum(l.cost_amount), 0) FROM itinerary_leg l
                     WHERE l.tenant_id = i.tenant_id AND l.itinerary_id = i.id) AS total_cost,
                   EXISTS (SELECT 1 FROM itinerary_leg l
                            WHERE l.tenant_id = i.tenant_id AND l.itinerary_id = i.id
                              AND l.connection_missed_at IS NOT NULL) AS connection_missed,
                   CASE
                       WHEN (SELECT l.arrived_at IS NOT NULL FROM itinerary_leg l
                              WHERE l.tenant_id = i.tenant_id AND l.itinerary_id = i.id
                              ORDER BY l.seq DESC LIMIT 1) THEN 'COMPLETED'
                       WHEN EXISTS (SELECT 1 FROM itinerary_leg l
                                     WHERE l.tenant_id = i.tenant_id AND l.itinerary_id = i.id
                                       AND l.status <> 'PLANNED') THEN 'IN_PROGRESS'
                       ELSE 'PLANNED'
                   END AS status
              FROM itinerary i
            """;

    private final JdbcClient jdbc;

    public ItineraryQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ItineraryView> findById(UUID id) {
        return jdbc.sql(SUMMARY + " WHERE i.id = :id")
                .param("id", id)
                .query((rs, row) -> new Header(
                        rs.getObject("id", UUID.class),
                        rs.getString("itinerary_no"),
                        rs.getString("shipment_ref"),
                        rs.getString("status"),
                        rs.getString("currency"),
                        rs.getInt("leg_count"),
                        rs.getBigDecimal("total_cost"),
                        rs.getBoolean("connection_missed"),
                        rs.getString("created_by"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .optional()
                .map(header -> new ItineraryView(header.id(), header.itineraryNo(),
                        header.shipmentRef(), header.status(), header.currency(),
                        header.legCount(), header.totalCost(), header.connectionMissed(),
                        header.createdBy(), header.createdAt(), legsOf(header.id()),
                        costByCarrier(header.id())));
    }

    /** A page of itineraries, newest first. */
    public Slice<ItinerarySummaryView> list(String cursor, Integer limit) {
        int pageSize = Slice.clampLimit(limit);
        Cursor after = Cursor.parse(cursor);

        List<ItinerarySummaryView> rows = jdbc.sql(SUMMARY + """
                         WHERE (CAST(:afterAt AS timestamptz) IS NULL
                                OR (i.created_at, i.id) < (CAST(:afterAt AS timestamptz),
                                                           CAST(:afterId AS uuid)))
                         ORDER BY i.created_at DESC, i.id DESC
                         LIMIT :limit
                        """)
                .param("afterAt", after == null ? null : after.createdAt())
                .param("afterId", after == null ? null : after.id())
                .param("limit", pageSize + 1)
                .query(ItinerarySummaryView.class)
                .list();

        return Slice.of(rows, pageSize, view -> new Cursor(
                view.createdAt().atOffset(ZoneOffset.UTC), view.id()).encode());
    }

    private List<LegView> legsOf(UUID itineraryId) {
        return jdbc.sql("""
                        SELECT l.id, l.seq, l.mode, l.status,
                               l.from_terminal_id, f.code AS from_terminal_code,
                               l.to_terminal_id, t.code AS to_terminal_code,
                               l.planned_departure_at, l.planned_arrival_at,
                               l.transfer_dwell_minutes,
                               l.trip_id, tr.trip_no,
                               l.unit_type, l.unit_count, l.booking_ref, l.train_no,
                               l.carrier_partner_id, p.code AS carrier_code, l.cost_amount,
                               l.placed_at, l.loaded_at, l.departed_at, l.arrived_at,
                               l.released_at, l.started_early, l.connection_missed_at,
                               l.earliest_start_at
                          FROM itinerary_leg l
                          JOIN terminal f ON f.id = l.from_terminal_id
                          JOIN terminal t ON t.id = l.to_terminal_id
                          JOIN business_partner p ON p.id = l.carrier_partner_id
                          LEFT JOIN trip tr ON tr.id = l.trip_id
                         WHERE l.itinerary_id = :itineraryId
                         ORDER BY l.seq
                        """)
                .param("itineraryId", itineraryId)
                .query(LegView.class)
                .list();
    }

    private List<CarrierCostView> costByCarrier(UUID itineraryId) {
        return jdbc.sql("""
                        SELECT l.carrier_partner_id, p.code AS carrier_code,
                               count(*)::int AS leg_count, sum(l.cost_amount) AS total_cost
                          FROM itinerary_leg l
                          JOIN business_partner p ON p.id = l.carrier_partner_id
                         WHERE l.itinerary_id = :itineraryId
                         GROUP BY l.carrier_partner_id, p.code
                         ORDER BY min(l.seq)
                        """)
                .param("itineraryId", itineraryId)
                .query(CarrierCostView.class)
                .list();
    }

    private record Header(UUID id, String itineraryNo, String shipmentRef, String status,
                          String currency, int legCount, BigDecimal totalCost,
                          boolean connectionMissed, String createdBy, Instant createdAt) {
    }

    /** Where the previous page stopped. */
    record Cursor(OffsetDateTime createdAt, UUID id) {

        String encode() {
            return createdAt.toInstant() + "_" + id;
        }

        static Cursor parse(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return null;
            }
            int split = cursor.lastIndexOf('_');
            try {
                return new Cursor(Instant.parse(cursor.substring(0, split)).atOffset(ZoneOffset.UTC),
                        UUID.fromString(cursor.substring(split + 1)));
            } catch (DateTimeParseException | IllegalArgumentException
                     | StringIndexOutOfBoundsException e) {
                throw new BusinessRuleViolationException("invalid-cursor",
                        "The cursor was not issued by this listing");
            }
        }
    }
}
