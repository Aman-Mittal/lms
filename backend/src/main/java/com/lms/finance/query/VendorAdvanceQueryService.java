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

import com.lms.identity.access.AccessSubject;
import com.lms.shared.query.Slice;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of vendor advances, newest first (keyset on creation time and id,
 * {@code vendor_advance_list_idx}; one trip's advances on
 * {@code vendor_advance_trip_idx}). A vendor-bound caller sees only that
 * vendor's own.
 */
@Service
@Transactional(readOnly = true)
@PreAuthorize("hasAnyAuthority('ADVANCE_REQUEST', 'ADVANCE_APPROVE', 'PAYABLES_MANAGE', 'INVOICE_READ')")
public class VendorAdvanceQueryService {

    static final String SELECT = """
            SELECT a.id, a.advance_no, a.trip_id, t.trip_no, a.vendor_partner_id, vp.legal_name AS vendor_name,
                   a.mode, a.amount, a.currency, a.reason, a.freight_reference, a.freight_source, a.max_percent,
                   a.status, a.approval_id, a.paid_reference, a.paid_on, a.deducted_invoice_id, a.cancel_reason,
                   a.version, a.created_at
              FROM vendor_advance a
              JOIN trip t ON t.id = a.trip_id
              JOIN business_partner vp ON vp.id = a.vendor_partner_id
            """;

    private final JdbcClient jdbc;

    public VendorAdvanceQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Slice<VendorAdvanceView> list(String status, UUID tripId, UUID vendorId, String cursor, Integer limit) {
        int pageSize = Slice.clampLimit(limit);
        Keyset after = Keyset.parseTime(cursor);
        List<VendorAdvanceView> rows = jdbc.sql(SELECT + """
                         WHERE (CAST(:status AS text) IS NULL OR a.status = CAST(:status AS text))
                           AND (CAST(:tripId AS uuid) IS NULL OR a.trip_id = CAST(:tripId AS uuid))
                           AND (CAST(:vendorId AS uuid) IS NULL OR a.vendor_partner_id = CAST(:vendorId AS uuid))
                           AND (CAST(:partner AS uuid) IS NULL OR a.vendor_partner_id = CAST(:partner AS uuid))
                           AND (CAST(:atBefore AS timestamptz) IS NULL
                                OR (a.created_at, a.id) < (CAST(:atBefore AS timestamptz), CAST(:idBefore AS uuid)))
                         ORDER BY a.created_at DESC, a.id DESC
                         LIMIT :limit
                        """)
                .param("status", status)
                .param("tripId", tripId)
                .param("vendorId", vendorId)
                .param("partner", AccessSubject.current().partnerScope())
                .param("atBefore", after == null ? null : after.atParam())
                .param("idBefore", after == null ? null : after.id())
                .param("limit", pageSize + 1)
                .query(VendorAdvanceView.class)
                .list();
        return Slice.of(rows, pageSize, row -> Keyset.ofTime(row.createdAt(), row.id()));
    }

    public Optional<VendorAdvanceView> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE a.id = :id AND (CAST(:partner AS uuid) IS NULL "
                        + "OR a.vendor_partner_id = CAST(:partner AS uuid))")
                .param("id", id)
                .param("partner", AccessSubject.current().partnerScope())
                .query(VendorAdvanceView.class)
                .optional();
    }
}
