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

package com.lms.finance.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.shared.query.Search;
import com.lms.shared.query.Slice;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the charge catalogue. Paged by code, which is unique per tenant
 * (served by {@code charge_catalogue_code_unique}); anyone who keeps the
 * catalogue or raises or approves a charge may read it.
 */
@Service
@Transactional(readOnly = true)
@PreAuthorize("hasAnyAuthority('CHARGE_CATALOGUE_MANAGE', 'MANUAL_CHARGE_RECORD', 'MANUAL_CHARGE_APPROVE')")
public class ChargeCatalogueQueryService {

    private static final String SELECT = """
            SELECT id, code, name, basis, default_rate, currency, direction, active, version, created_at, updated_at
              FROM charge_catalogue
            """;

    private final JdbcClient jdbc;

    public ChargeCatalogueQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param direction when given, entries usable for that side: RECEIVABLE or PAYABLE also match BOTH
     */
    public Slice<ChargeCatalogueView> list(Boolean active, String direction, String q, String cursor, Integer limit) {
        int pageSize = Slice.clampLimit(limit);
        List<ChargeCatalogueView> rows = jdbc.sql(SELECT + """
                         WHERE (CAST(:active AS boolean) IS NULL OR active = CAST(:active AS boolean))
                           AND (CAST(:direction AS text) IS NULL OR direction = 'BOTH'
                                OR direction = CAST(:direction AS text))
                           AND (CAST(:q AS text) IS NULL OR lower(code) LIKE CAST(:q AS text)
                                OR lower(name) LIKE CAST(:q AS text))
                           AND (CAST(:cursor AS text) IS NULL OR code > CAST(:cursor AS text))
                         ORDER BY code
                         LIMIT :limit
                        """)
                .param("active", active)
                .param("direction", direction)
                .param("q", Search.containsPattern(q))
                .param("cursor", cursor)
                .param("limit", pageSize + 1)
                .query(ChargeCatalogueView.class)
                .list();
        return Slice.of(rows, pageSize, ChargeCatalogueView::code);
    }

    public Optional<ChargeCatalogueView> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE id = :id").param("id", id).query(ChargeCatalogueView.class).optional();
    }
}
