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
package com.lms.delivery.command;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.lms.approvals.api.ApprovalRequests;
import com.lms.approvals.api.ApprovalSubject;
import com.lms.approvals.api.ApprovalSubjectTypes;
import com.lms.approvals.api.ApprovalTicket;
import com.lms.delivery.command.domain.DeliveryProof;
import com.lms.delivery.command.domain.ProofStatus;
import com.lms.delivery.events.DeliveryProofReversed;
import com.lms.shared.audit.TransitionAudit;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reversing a confirmed proof of delivery -- a wrong consignee, a code read
 * out over the phone to the wrong person -- before billing has used it.
 *
 * <p>Only ever through an approval: {@code POD_REVERSAL} is mandatory in its
 * policy, so asking raises a request and approving it reverses the proof. A
 * proof billing has consumed is never reversed ({@code pod-already-billed}):
 * the carrier's bill for a trip that carried the consignment was approved for
 * payment, or the client's transport charge for it was invoiced. Undoing money
 * already settled is a credit note's job, not this.
 *
 * <p>Checked when asked and again when approved, because billing may have run
 * in between; a refusal at approval leaves the request open.
 */
@Service
public class ProofReversalService {

    /** The action a reversal is requested as. */
    public static final String REVERSE_ACTION = "REVERSE";
    static final String AUDIT_TYPE = "DELIVERY_PROOF";
    private static final int MAX_REASON = 500;

    private final DeliveryProofRepository proofs;
    private final ApprovalRequests approvals;
    private final JdbcClient jdbc;
    private final TransitionAudit audit;
    private final ApplicationEventPublisher events;

    public ProofReversalService(DeliveryProofRepository proofs, ApprovalRequests approvals, JdbcClient jdbc,
                                TransitionAudit audit, ApplicationEventPublisher events) {
        this.proofs = proofs;
        this.approvals = approvals;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    /**
     * Asks for the consignment's proof to be reversed.
     *
     * @return the approval request raised; null only if a tenant's policy
     *         could switch approval off, which a mandatory policy cannot
     */
    @Transactional
    @PreAuthorize("hasAuthority('POD_REVERSE')")
    public ApprovalTicket requestReversal(UUID consignmentId, String reason) {
        String why = reason == null ? "" : reason.strip();
        if (why.isEmpty() || why.length() > MAX_REASON) {
            throw new BusinessRuleViolationException("reversal-reason-required",
                    "A reversal says why, in 1 to " + MAX_REASON + " characters");
        }
        UUID orgUnitId = consignmentUnit(consignmentId);
        DeliveryProof proof = requireConfirmed(consignmentId);
        requireUnbilled(consignmentId);

        if (!approvals.isRequired(ApprovalSubjectTypes.POD_REVERSAL)) {
            reverse(proof, why, null);
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("proofId", proof.id().toString());
        payload.put("recipientName", proof.recipientName());
        payload.put("deliveredAt", proof.deliveredAt() == null ? null : proof.deliveredAt().toString());
        payload.put("goodsCondition", proof.goodsCondition() == null ? null : proof.goodsCondition().name());
        payload.put("reason", why);
        ApprovalTicket ticket = approvals.request(
                new ApprovalSubject(ApprovalSubjectTypes.POD_REVERSAL, consignmentId, orgUnitId),
                REVERSE_ACTION, payload, "Reverse proof of delivery: " + abbreviate(why));
        audit.record("DELIVERY_PROOF_REVERSAL_REQUESTED", AUDIT_TYPE, proof.id(), null,
                Json.object("consignmentId", consignmentId.toString(), "approvalId", ticket.id().toString(),
                        "reason", why));
        return ticket;
    }

    /**
     * Reverses the proof an approved request names, in the deciding
     * transaction. Unguarded: the approvals module checked the decider.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyApproved(UUID consignmentId, UUID proofId, String reason, UUID approvalId) {
        DeliveryProof proof = requireConfirmed(consignmentId);
        if (proofId != null && !proofId.equals(proof.id())) {
            throw new BusinessRuleViolationException("proof-not-recorded",
                    "The proof this request was raised for is no longer the consignment's proof");
        }
        requireUnbilled(consignmentId);
        reverse(proof, reason, approvalId);
    }

    private void reverse(DeliveryProof proof, String reason, UUID approvalId) {
        DeliveryProof saved = proofs.save(proof.reversed());
        audit.record("DELIVERY_PROOF_REVERSED", AUDIT_TYPE, saved.id(),
                Json.object("status", proof.status().name(), "recipient", proof.recipientName(),
                        "deliveredAt", proof.deliveredAt() == null ? null : proof.deliveredAt().toString()),
                Json.object("status", saved.status().name(), "consignmentId", saved.consignmentId().toString(),
                        "reason", reason, "approvalId", approvalId == null ? null : approvalId.toString()));
        events.publishEvent(new DeliveryProofReversed(saved.tenantId(), saved.consignmentId(), approvalId,
                Instant.now()));
    }

    private DeliveryProof requireConfirmed(UUID consignmentId) {
        DeliveryProof proof = proofs.forConsignment(TenantContext.requireTenantId(), consignmentId)
                .filter(p -> p.status() == ProofStatus.CONFIRMED)
                .orElse(null);
        if (proof == null) {
            throw new BusinessRuleViolationException("proof-not-recorded",
                    "This consignment has no confirmed proof of delivery to reverse");
        }
        return proof;
    }

    /**
     * The unit of the order the consignment was cut from, read under row-level
     * security: another tenant's consignment is absent. A consignment with no
     * order (a warehouse hand-off) has no unit.
     */
    private UUID consignmentUnit(UUID consignmentId) {
        record Unit(UUID id) {
        }
        return jdbc.sql("""
                        SELECT so.org_unit_id
                          FROM consignment c
                          LEFT JOIN sales_order so ON so.id = c.order_id
                         WHERE c.id = :id
                        """)
                .param("id", consignmentId)
                .query((rs, row) -> new Unit(rs.getObject("org_unit_id", UUID.class)))
                .optional()
                .orElseThrow(() -> new ResourceNotFoundException("Consignment", consignmentId))
                .id();
    }

    /**
     * Refuses once billing has consumed the proof: a carrier bill approved for
     * payment on a trip that carried the consignment, or an invoiced transport
     * charge to the consignment's customer for such a trip.
     */
    private void requireUnbilled(UUID consignmentId) {
        boolean billed = Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1
                              FROM load_consignment lc
                              JOIN trip t ON t.load_id = lc.load_id AND t.status <> 'CANCELLED'
                              JOIN consignment c ON c.id = lc.consignment_id
                              LEFT JOIN sales_order so ON so.id = c.order_id
                             WHERE lc.tenant_id = :tenantId AND lc.consignment_id = :consignmentId
                               AND (EXISTS (SELECT 1 FROM freight_bill fb
                                             WHERE fb.tenant_id = t.tenant_id AND fb.trip_id = t.id
                                               AND fb.status = 'APPROVED_FOR_PAYMENT')
                                    OR EXISTS (SELECT 1 FROM client_charge ch
                                                WHERE ch.tenant_id = t.tenant_id AND ch.trip_id = t.id
                                                  AND ch.service_type = 'TRANSPORT'
                                                  AND ch.client_partner_id = so.customer_partner_id
                                                  AND ch.invoice_id IS NOT NULL)))
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("consignmentId", consignmentId)
                .query(Boolean.class)
                .single());
        if (billed) {
            throw new BusinessRuleViolationException("pod-already-billed",
                    "Billing has already used this proof of delivery -- the carrier's bill was approved"
                            + " for payment or the client was invoiced -- so it can no longer be reversed");
        }
    }

    private static String abbreviate(String text) {
        return text.length() <= 200 ? text : text.substring(0, 199) + "…";
    }
}
