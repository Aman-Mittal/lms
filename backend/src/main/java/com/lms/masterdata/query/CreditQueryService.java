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
package com.lms.masterdata.query;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.query.Slice;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of credit: a customer's position and the change log of its limits.
 * Reads change nothing, however often they are repeated.
 */
@Service
@Transactional(readOnly = true)
public class CreditQueryService {

    private static final String CHANGE_SELECT = """
            SELECT c.id, c.partner_id, p.code AS partner_code, c.status, c.previous_limit,
                   c.previous_soft_limit, c.requested_limit, c.requested_soft_limit, c.reason,
                   c.requested_by, c.requested_at, c.decided_by, c.decided_at, c.decision_note
              FROM credit_limit_change c
              JOIN business_partner p ON p.id = c.partner_id
            """;

    private final JdbcClient jdbc;
    private final CreditExposureService exposure;

    public CreditQueryService(JdbcClient jdbc, CreditExposureService exposure) {
        this.jdbc = jdbc;
        this.exposure = exposure;
    }

    @PreAuthorize("hasAuthority('PARTNER_READ')")
    public CreditPositionView position(UUID partnerId) {
        return exposure.assess(partnerId, null)
                .orElseThrow(() -> new ResourceNotFoundException("BusinessPartner", partnerId));
    }

    /** One partner's change log, newest first. */
    @PreAuthorize("hasAuthority('PARTNER_READ')")
    public Slice<CreditLimitChangeView> changesOf(UUID partnerId, String cursor, Integer limit) {
        boolean known = jdbc.sql("SELECT count(*) FROM business_partner WHERE id = :id")
                .param("id", partnerId).query(Long.class).single() > 0;
        if (!known) {
            throw new ResourceNotFoundException("BusinessPartner", partnerId);
        }
        return page("c.partner_id = :partnerId", partnerId, null, cursor, limit);
    }

    /** Requests across partners, newest first, optionally of one status. */
    @PreAuthorize("hasAuthority('PARTNER_READ')")
    public Slice<CreditLimitChangeView> changes(String status, String cursor, Integer limit) {
        return page("(CAST(:status AS text) IS NULL OR c.status = CAST(:status AS text))", null,
                status, cursor, limit);
    }

    private Slice<CreditLimitChangeView> page(String filter, UUID partnerId, String status,
                                              String cursor, Integer limit) {
        int pageSize = Slice.clampLimit(limit);
        Instant afterTime = null;
        UUID afterId = null;
        if (cursor != null) {
            String[] parts = cursor.split("_", 2);
            try {
                afterTime = Instant.parse(parts[0]);
                afterId = UUID.fromString(parts[1]);
            } catch (DateTimeParseException | IllegalArgumentException
                     | ArrayIndexOutOfBoundsException e) {
                throw new BusinessRuleViolationException("cursor-invalid",
                        "That cursor was not issued by this listing");
            }
        }
        var statement = jdbc.sql(CHANGE_SELECT + " WHERE " + filter + """
                           AND (CAST(:afterTime AS timestamptz) IS NULL
                                OR (c.requested_at, c.id)
                                   < (CAST(:afterTime AS timestamptz), CAST(:afterId AS uuid)))
                         ORDER BY c.requested_at DESC, c.id DESC
                         LIMIT :limit
                        """)
                .param("afterTime", afterTime == null ? null : java.sql.Timestamp.from(afterTime))
                .param("afterId", afterId)
                .param("limit", pageSize + 1);
        if (partnerId != null) {
            statement = statement.param("partnerId", partnerId);
        } else {
            statement = statement.param("status", status);
        }
        List<CreditLimitChangeView> rows = statement.query(CreditLimitChangeView.class).list();
        return Slice.of(rows, pageSize, v -> v.requestedAt() + "_" + v.id());
    }
}
