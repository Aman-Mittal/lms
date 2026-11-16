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
package com.lms.statutory.query;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.shared.query.Search;
import com.lms.shared.query.Slice;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the e-way bills. The desk lists newest first (keyset on creation
 * time and id, {@code eway_bill_list_idx}, or {@code eway_bill_status_idx} for
 * one status); the expiring-soon list is the live bills the sweep has flagged,
 * soonest to lapse first (keyset on validity and id, {@code eway_bill_flagged_idx});
 * a lookup by number uses the unique key; a consignment's bills use
 * {@code eway_bill_consignment_idx}.
 */
@Service
@Transactional(readOnly = true)
public class EwayBillQueryService {

    private static final String SELECT = """
            SELECT b.id, b.bill_no, b.status, b.consignment_id, c.tracking_ref, c.status AS consignment_status,
                   b.org_unit_id, b.generated_at, b.valid_until, b.vehicle_no, b.vehicle_updated_at, b.distance_km,
                   b.source, b.extension_count, b.cancelled_at, b.cancel_reason,
                   (b.expiring_flagged_at IS NOT NULL AND b.status IN ('ACTIVE', 'EXTENDED')) AS expiring_soon,
                   b.expiring_flagged_at, b.expired_at, b.version, b.created_at
              FROM eway_bill b
              JOIN consignment c ON c.id = b.consignment_id
            """;

    private final JdbcClient jdbc;

    public EwayBillQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<EwayBillView> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE b.id = :id AND b.tenant_id = app_current_tenant()")
                .param("id", id).query(EwayBillView.class).optional();
    }

    /** Finds the bill by its number: the lookup that answers which consignment it covers. */
    public Optional<EwayBillView> findByNumber(String billNo) {
        return jdbc.sql(SELECT + " WHERE b.bill_no = :billNo AND b.tenant_id = app_current_tenant()")
                .param("billNo", billNo == null ? "" : billNo.trim()).query(EwayBillView.class).optional();
    }

    /** A consignment's bills, newest first; a consignment has few, so the list is bounded rather than paged. */
    public List<EwayBillView> forConsignment(UUID consignmentId) {
        return jdbc.sql(SELECT + " WHERE b.consignment_id = :consignmentId AND b.tenant_id = app_current_tenant()"
                        + " ORDER BY b.generated_at DESC, b.id DESC LIMIT 50")
                .param("consignmentId", consignmentId).query(EwayBillView.class).list();
    }

    /** A page of bills, newest first, optionally of one status or whose number or consignment reference contains {@code q}. */
    public Slice<EwayBillView> list(String status, String q, String cursor, Integer limit) {
        int pageSize = Slice.clampLimit(limit);
        Position after = Position.parse(cursor);
        List<EwayBillView> rows = jdbc.sql(SELECT + """
                         WHERE b.tenant_id = app_current_tenant()
                           AND (CAST(:status AS text) IS NULL OR b.status = CAST(:status AS text))
                           AND (CAST(:q AS text) IS NULL OR lower(b.bill_no) LIKE CAST(:q AS text)
                                OR lower(c.tracking_ref) LIKE CAST(:q AS text))
                           AND (CAST(:atBefore AS timestamptz) IS NULL
                                OR (b.created_at, b.id) < (CAST(:atBefore AS timestamptz), CAST(:idBefore AS uuid)))
                         ORDER BY b.created_at DESC, b.id DESC
                         LIMIT :limit
                        """)
                .param("status", status)
                .param("q", Search.containsPattern(q))
                .param("atBefore", after == null ? null : Timestamp.from(after.at()))
                .param("idBefore", after == null ? null : after.id())
                .param("limit", pageSize + 1)
                .query(EwayBillView.class).list();
        return Slice.of(rows, pageSize, row -> Position.of(row.createdAt(), row.id()));
    }

    /** Live bills the sweep has flagged as close to lapsing, soonest first. */
    public Slice<EwayBillView> expiring(String cursor, Integer limit) {
        int pageSize = Slice.clampLimit(limit);
        Position after = Position.parse(cursor);
        List<EwayBillView> rows = jdbc.sql(SELECT + """
                         WHERE b.tenant_id = app_current_tenant()
                           AND b.status IN ('ACTIVE', 'EXTENDED') AND b.expiring_flagged_at IS NOT NULL
                           AND (CAST(:atAfter AS timestamptz) IS NULL
                                OR (b.valid_until, b.id) > (CAST(:atAfter AS timestamptz), CAST(:idAfter AS uuid)))
                         ORDER BY b.valid_until, b.id
                         LIMIT :limit
                        """)
                .param("atAfter", after == null ? null : Timestamp.from(after.at()))
                .param("idAfter", after == null ? null : after.id())
                .param("limit", pageSize + 1)
                .query(EwayBillView.class).list();
        return Slice.of(rows, pageSize, row -> Position.of(row.validUntil(), row.id()));
    }

    /** The cursor: a time to the microsecond, as stored, and the id that breaks ties. */
    private record Position(Instant at, UUID id) {

        static String of(Instant at, UUID id) {
            Instant micros = at.truncatedTo(ChronoUnit.MICROS);
            return "t" + micros.getEpochSecond() + "." + String.format("%06d", micros.getNano() / 1000) + "_" + id;
        }

        static Position parse(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return null;
            }
            try {
                if (!cursor.startsWith("t")) {
                    throw new IllegalArgumentException("wrong kind");
                }
                String[] parts = cursor.substring(1).split("_", 2);
                String[] seconds = parts[0].split("\\.", 2);
                return new Position(Instant.ofEpochSecond(Long.parseLong(seconds[0]),
                        Long.parseLong(seconds[1]) * 1000), UUID.fromString(parts[1]));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("The cursor is not one this list handed out", e);
            }
        }
    }
}
