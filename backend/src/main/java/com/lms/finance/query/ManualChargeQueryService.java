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
import com.lms.shared.query.Search;
import com.lms.shared.query.Slice;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of manual charges, newest first, keyset on creation time and id
 * ({@code manual_charge_list_idx}, or {@code manual_charge_subject_idx} when a
 * subject is named, which is how a trip, consignment or order view lists its
 * charges). A partner-bound caller sees only the charges that name that
 * partner.
 */
@Service
@Transactional(readOnly = true)
@PreAuthorize("hasAnyAuthority('MANUAL_CHARGE_RECORD', 'MANUAL_CHARGE_APPROVE', 'INVOICE_READ')")
public class ManualChargeQueryService {

    private static final String SELECT = """
            SELECT m.id, m.charge_no, m.catalogue_id, m.charge_code, m.charge_name, m.basis, m.direction,
                   m.subject_type, m.subject_id, m.subject_ref, m.trip_id,
                   m.customer_partner_id, cp.legal_name AS customer_name,
                   m.vendor_partner_id, vp.legal_name AS vendor_name,
                   m.quantity, m.rate, m.amount, m.currency, m.reason, m.evidence_ref, m.status, m.approval_id,
                   m.client_charge_id, cc.invoice_id AS client_invoice_id,
                   m.freight_bill_id, fb.bill_no AS freight_bill_no,
                   CASE WHEN m.status <> 'APPROVED' THEN false
                        WHEN m.direction = 'RECEIVABLE' THEN (cc.invoice_id IS NOT NULL)
                        ELSE (fb.id IS NOT NULL AND fb.status <> 'DRAFT') END AS billed,
                   m.cancel_reason, m.version, m.created_at, m.updated_at
              FROM manual_charge m
              LEFT JOIN business_partner cp ON cp.id = m.customer_partner_id
              LEFT JOIN business_partner vp ON vp.id = m.vendor_partner_id
              LEFT JOIN client_charge cc ON cc.id = m.client_charge_id
              LEFT JOIN freight_bill fb ON fb.id = m.freight_bill_id
            """;

    private static final String PARTNER_SCOPE = """
               AND (CAST(:partner AS uuid) IS NULL OR m.vendor_partner_id = CAST(:partner AS uuid)
                    OR m.customer_partner_id = CAST(:partner AS uuid))
            """;

    private final JdbcClient jdbc;

    public ManualChargeQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Slice<ManualChargeView> list(String status, String direction, String subjectType, UUID subjectId,
                                        UUID tripId, UUID vendorId, UUID customerId, String q, String cursor,
                                        Integer limit) {
        int pageSize = Slice.clampLimit(limit);
        Keyset after = Keyset.parseTime(cursor);
        List<ManualChargeView> rows = jdbc.sql(SELECT + """
                         WHERE (CAST(:status AS text) IS NULL OR m.status = CAST(:status AS text))
                           AND (CAST(:direction AS text) IS NULL OR m.direction = CAST(:direction AS text))
                           AND (CAST(:subjectType AS text) IS NULL OR m.subject_type = CAST(:subjectType AS text))
                           AND (CAST(:subjectId AS uuid) IS NULL OR m.subject_id = CAST(:subjectId AS uuid))
                           AND (CAST(:tripId AS uuid) IS NULL OR m.trip_id = CAST(:tripId AS uuid))
                           AND (CAST(:vendorId AS uuid) IS NULL OR m.vendor_partner_id = CAST(:vendorId AS uuid))
                           AND (CAST(:customerId AS uuid) IS NULL OR m.customer_partner_id = CAST(:customerId AS uuid))
                           AND (CAST(:q AS text) IS NULL OR lower(m.charge_no) LIKE CAST(:q AS text)
                                OR lower(m.subject_ref) LIKE CAST(:q AS text)
                                OR lower(m.charge_code) LIKE CAST(:q AS text))
                           AND (CAST(:atBefore AS timestamptz) IS NULL
                                OR (m.created_at, m.id) < (CAST(:atBefore AS timestamptz), CAST(:idBefore AS uuid)))
                        """ + PARTNER_SCOPE + """
                         ORDER BY m.created_at DESC, m.id DESC
                         LIMIT :limit
                        """)
                .param("status", status)
                .param("direction", direction)
                .param("subjectType", subjectType)
                .param("subjectId", subjectId)
                .param("tripId", tripId)
                .param("vendorId", vendorId)
                .param("customerId", customerId)
                .param("q", Search.containsPattern(q))
                .param("atBefore", after == null ? null : after.atParam())
                .param("idBefore", after == null ? null : after.id())
                .param("partner", AccessSubject.current().partnerScope())
                .param("limit", pageSize + 1)
                .query(ManualChargeView.class)
                .list();
        return Slice.of(rows, pageSize, row -> Keyset.ofTime(row.createdAt(), row.id()));
    }

    public Optional<ManualChargeView> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE m.id = :id" + PARTNER_SCOPE)
                .param("id", id)
                .param("partner", AccessSubject.current().partnerScope())
                .query(ManualChargeView.class)
                .optional();
    }
}
