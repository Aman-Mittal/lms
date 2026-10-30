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
package com.lms.delivery.query;

import java.util.Optional;
import java.util.UUID;

import com.lms.shared.query.Slice;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The read side of proof of delivery and claims. */
@Service
public class DeliveryQueryService {

    private static final String CLAIM_SELECT = """
            SELECT id, claim_no, consignment_id, claim_type, amount, currency, description,
                   status, approved_amount, resolution_note, decided_at, created_at
              FROM claim
            """;

    private final JdbcClient jdbc;

    public DeliveryQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('POD_READ')")
    public Optional<DeliveryProofView> proofOf(UUID consignmentId) {
        return jdbc.sql("""
                        SELECT consignment_id, status, recipient_name, photo_ref, delivered_at,
                               lat, lon, otp_expires_at
                          FROM delivery_proof
                         WHERE consignment_id = :consignmentId
                        """)
                .param("consignmentId", consignmentId)
                .query(DeliveryProofView.class).optional();
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('CLAIM_READ')")
    public Slice<ClaimView> listClaims(String status, UUID consignmentId, String cursor, Integer limit) {
        int pageSize = Slice.clampLimit(limit);
        // claim_no is unique per tenant and sorts by the day it was raised, so it is the cursor.
        var rows = jdbc.sql(CLAIM_SELECT + """
                         WHERE (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))
                           AND (CAST(:consignmentId AS uuid) IS NULL
                                OR consignment_id = CAST(:consignmentId AS uuid))
                           AND (CAST(:cursor AS text) IS NULL OR claim_no > CAST(:cursor AS text))
                         ORDER BY claim_no
                         LIMIT :limit
                        """)
                .param("status", status)
                .param("consignmentId", consignmentId)
                .param("cursor", cursor)
                .param("limit", pageSize + 1)
                .query(ClaimView.class).list();
        return Slice.of(rows, pageSize, ClaimView::claimNo);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('CLAIM_READ')")
    public Optional<ClaimView> findClaim(UUID id) {
        return jdbc.sql(CLAIM_SELECT + " WHERE id = :id").param("id", id)
                .query(ClaimView.class).optional();
    }
}
