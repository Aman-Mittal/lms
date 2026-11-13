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

import java.time.Instant;
import java.util.List;
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
        record Head(UUID id, DeliveryProofView view) {
        }
        return jdbc.sql("""
                        SELECT id, consignment_id, status, recipient_name, photo_ref, delivered_at,
                               lat, lon, otp_expires_at, goods_condition, remark, paper, keyed_by,
                               reported_at, unloading_started_at, unloading_ended_at
                          FROM delivery_proof
                         WHERE consignment_id = :consignmentId
                        """)
                .param("consignmentId", consignmentId)
                .query((rs, row) -> new Head(rs.getObject("id", UUID.class), new DeliveryProofView(
                        rs.getObject("consignment_id", UUID.class), rs.getString("status"),
                        rs.getString("recipient_name"), rs.getString("photo_ref"),
                        instant(rs, "delivered_at"), rs.getBigDecimal("lat"), rs.getBigDecimal("lon"),
                        instant(rs, "otp_expires_at"), rs.getString("goods_condition"), rs.getString("remark"),
                        rs.getBoolean("paper"), rs.getObject("keyed_by", UUID.class),
                        instant(rs, "reported_at"), instant(rs, "unloading_started_at"),
                        instant(rs, "unloading_ended_at"), List.of(), List.of())))
                .optional()
                .map(head -> withLinesAndDocuments(head.id(), consignmentId, head.view()));
    }

    private DeliveryProofView withLinesAndDocuments(UUID proofId, UUID consignmentId, DeliveryProofView v) {
        List<DeliveryProofView.Line> lines = jdbc.sql("""
                        SELECT ol.id, ol.line_no, ol.material_description, ol.uom, ol.quantity,
                               pl.received_qty, pl.short_qty, pl.damaged_qty, pl.excess_qty
                          FROM consignment_line cl
                          JOIN order_line ol ON ol.id = cl.order_line_id
                          LEFT JOIN delivery_proof_line pl ON pl.proof_id = :proofId AND pl.order_line_id = ol.id
                         WHERE cl.consignment_id = :consignmentId
                         ORDER BY ol.line_no
                        """)
                .param("proofId", proofId).param("consignmentId", consignmentId)
                .query((rs, row) -> new DeliveryProofView.Line(rs.getObject("id", UUID.class),
                        rs.getInt("line_no"), rs.getString("material_description"), rs.getString("uom"),
                        rs.getBigDecimal("quantity"), rs.getBigDecimal("received_qty"),
                        rs.getBigDecimal("short_qty"), rs.getBigDecimal("damaged_qty"),
                        rs.getBigDecimal("excess_qty")))
                .list();
        List<DeliveryProofView.Document> documents = jdbc.sql("""
                        SELECT doc_type, reference FROM delivery_proof_document
                         WHERE proof_id = :proofId ORDER BY position
                        """)
                .param("proofId", proofId)
                .query((rs, row) -> new DeliveryProofView.Document(rs.getString("doc_type"),
                        rs.getString("reference")))
                .list();
        return new DeliveryProofView(v.consignmentId(), v.status(), v.recipientName(), v.photoRef(),
                v.deliveredAt(), v.lat(), v.lon(), v.otpExpiresAt(), v.goodsCondition(), v.remark(), v.paper(),
                v.keyedBy(), v.reportedAt(), v.unloadingStartedAt(), v.unloadingEndedAt(), lines, documents);
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Timestamp at = rs.getTimestamp(column);
        return at == null ? null : at.toInstant();
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
