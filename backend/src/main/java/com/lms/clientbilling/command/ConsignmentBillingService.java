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
package com.lms.clientbilling.command;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import com.lms.clientbilling.query.ClientAccess;
import com.lms.identity.access.AccessSubject;
import com.lms.shared.audit.AuditTrail;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Marking a consignment billable by hand, without waiting for its proof of
 * delivery.
 *
 * <p>A client is invoiced for a trip's transport once each of its consignments
 * on the trip has a confirmed proof. When a proof will never come -- the
 * consignee signed a paper copy, the code was never used -- somebody who may
 * run the client's billing says so here, with a reason, and the consignment
 * counts as proved for billing. It changes nothing about the consignment's own
 * status, nor about the carrier's settlement, which waits for clean proofs on
 * its own terms.
 */
@Service
public class ConsignmentBillingService {

    private static final int MAX_REASON = 500;

    private final JdbcClient jdbc;
    private final ClientAccess access;
    private final AuditTrail audit;

    public ConsignmentBillingService(JdbcClient jdbc, ClientAccess access, AuditTrail audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
    }

    /** What a consignment is, for this decision. */
    private record Subject(String status, String freightMode, UUID clientPartnerId) {
    }

    /**
     * Marks the consignment billable. A second mark changes nothing and audits
     * nothing.
     *
     * @return whether this call marked it; false when it was already marked
     */
    @Transactional
    @PreAuthorize("hasAuthority('CLIENT_BILLING_RUN')")
    public boolean markBillable(UUID consignmentId, String reason) {
        UUID tenantId = TenantContext.requireTenantId();
        // Row-level security hides another tenant's consignment, which then
        // reads as absent: the same answer as one that does not exist.
        Subject subject = jdbc.sql("""
                        SELECT c.status, c.freight_mode, so.customer_partner_id AS client_partner_id
                          FROM consignment c
                          LEFT JOIN sales_order so ON so.id = c.order_id
                         WHERE c.id = :id
                        """)
                .param("id", consignmentId)
                .query(Subject.class)
                .optional()
                .orElseThrow(() -> new ResourceNotFoundException("Consignment", consignmentId));
        if (subject.clientPartnerId() != null) {
            access.requireAccessTo(subject.clientPartnerId());
        }
        if ("LTL".equals(subject.freightMode()) || subject.clientPartnerId() == null) {
            throw new BusinessRuleViolationException("consignment-not-trip-billed",
                    "This consignment is not billed per trip -- a docket is billed as a receivable"
                            + " and a warehouse hand-off when it is handed over -- so there is"
                            + " nothing for a mark to release");
        }
        if ("CANCELLED".equals(subject.status()) || "SPLIT".equals(subject.status())) {
            throw new BusinessRuleViolationException("consignment-not-billable",
                    "A " + subject.status().toLowerCase(java.util.Locale.ROOT)
                            + " consignment is never billed");
        }
        String note = reason == null ? "" : reason.strip();
        if (note.isEmpty() || note.length() > MAX_REASON) {
            throw new BusinessRuleViolationException("billing-mark-reason-required",
                    "Say why the consignment is billable without its proof, in at most "
                            + MAX_REASON + " characters");
        }

        int marked = jdbc.sql("""
                        INSERT INTO consignment_billing_mark (tenant_id, consignment_id, marked_at,
                                                              marked_by, reason)
                        VALUES (:tenantId, :consignmentId, :at, :by, :reason)
                        ON CONFLICT (tenant_id, consignment_id) DO NOTHING
                        """)
                .param("tenantId", tenantId)
                .param("consignmentId", consignmentId)
                .param("at", Instant.now().atOffset(ZoneOffset.UTC))
                .param("by", actorId())
                .param("reason", note)
                .update();
        if (marked == 0) {
            return false;
        }
        audit.recordAtomically("CONSIGNMENT_BILLING_ELIGIBLE_MARKED", "CONSIGNMENT", consignmentId,
                Json.object("billingEligible", "false"),
                Json.object("billingEligible", "true", "reason", note,
                        "client", subject.clientPartnerId().toString()));
        return true;
    }

    private static UUID actorId() {
        String userId = AccessSubject.current().userId();
        try {
            return userId == null ? null : UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
