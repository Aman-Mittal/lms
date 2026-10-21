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

/** Read side for rail rate cards: every version, newest first. */
@Service
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('MULTIMODAL_READ')")
public class RailTariffQueryService {

    private static final String SELECT = """
            SELECT r.id, r.code, r.carrier_partner_id, p.code AS carrier_code,
                   r.origin_terminal_id, o.code AS origin_terminal_code,
                   r.destination_terminal_id, d.code AS destination_terminal_code,
                   r.currency, r.per_wagon_rate, r.per_container_rate, r.minimum_charge,
                   r.effective_from, r.effective_to, r.created_by, r.created_at
              FROM rail_tariff r
              JOIN business_partner p ON p.id = r.carrier_partner_id
              JOIN terminal o ON o.id = r.origin_terminal_id
              JOIN terminal d ON d.id = r.destination_terminal_id
            """;

    private final JdbcClient jdbc;

    public RailTariffQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<RailTariffView> findById(UUID id) {
        return jdbc.sql(SELECT + " WHERE r.id = :id")
                .param("id", id)
                .query(RailTariffView.class)
                .optional();
    }

    /** A page of card versions, newest first; served by rail_tariff_listing_idx. */
    public Slice<RailTariffView> list(String cursor, Integer limit) {
        int pageSize = Slice.clampLimit(limit);
        Cursor after = Cursor.parse(cursor);

        List<RailTariffView> rows = jdbc.sql(SELECT + """
                         WHERE (CAST(:afterAt AS timestamptz) IS NULL
                                OR (r.created_at, r.id) < (CAST(:afterAt AS timestamptz),
                                                           CAST(:afterId AS uuid)))
                         ORDER BY r.created_at DESC, r.id DESC
                         LIMIT :limit
                        """)
                .param("afterAt", after == null ? null : after.createdAt())
                .param("afterId", after == null ? null : after.id())
                .param("limit", pageSize + 1)
                .query(RailTariffView.class)
                .list();

        return Slice.of(rows, pageSize, view -> new Cursor(
                view.createdAt().atOffset(ZoneOffset.UTC), view.id()).encode());
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
