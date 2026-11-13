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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.lms.delivery.command.domain.DeliveryProof;
import com.lms.delivery.command.domain.ProofDocumentType;
import com.lms.shared.audit.TransitionAudit;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Starting a proof of delivery as a draft and feeding it over time: what was
 * received against each order line, the documents that came back and the
 * unloading times. Submitting it is {@link DeliveryService}'s, because that
 * is where a code is matched.
 *
 * <p>Every step is open to a draft only: once a proof proves the delivery it
 * is read-only, and changing it is a reversal.
 */
@Service
public class ProofFeedingService {

    static final String AUDIT_TYPE = "DELIVERY_PROOF";
    private static final int MAX_DOCUMENTS = 20;
    private static final int MAX_REFERENCE = 500;
    private static final int MAX_REMARK = 500;
    private static final BigDecimal MAX_QUANTITY = new BigDecimal("99999999999.999");

    /** What was received against one order line of the consignment. */
    public record LineInput(UUID orderLineId, BigDecimal receivedQty, BigDecimal shortQty,
                            BigDecimal damagedQty, BigDecimal excessQty) {
    }

    public record DocumentInput(ProofDocumentType type, String reference) {
    }

    private final DeliveryProofRepository proofs;
    private final JdbcClient jdbc;
    private final DeliveryService delivery;
    private final TransitionAudit audit;

    public ProofFeedingService(DeliveryProofRepository proofs, JdbcClient jdbc, DeliveryService delivery,
                               TransitionAudit audit) {
        this.proofs = proofs;
        this.jdbc = jdbc;
        this.delivery = delivery;
        this.audit = audit;
    }

    /**
     * Starts the consignment's draft, or finds the one already there; calling
     * it again changes nothing. A consignment must have left, as for a code.
     */
    @Transactional
    @PreAuthorize("hasAuthority('POD_RECORD')")
    public void ensure(UUID consignmentId) {
        UUID tenantId = TenantContext.requireTenantId();
        if (proofs.forConsignment(tenantId, consignmentId).isPresent()) {
            return;
        }
        delivery.requireProvable(consignmentId);
        UUID id = UUID.randomUUID();
        // One statement, so two callers racing to start the draft leave one row and no error.
        int inserted = jdbc.sql("""
                        INSERT INTO delivery_proof (id, tenant_id, consignment_id, status, version, created_at)
                        VALUES (:id, :tenantId, :consignmentId, 'PENDING', 0, now())
                        ON CONFLICT (tenant_id, consignment_id) DO NOTHING
                        """)
                .param("id", id).param("tenantId", tenantId).param("consignmentId", consignmentId).update();
        if (inserted > 0) {
            audit.record("DELIVERY_POD_STARTED", AUDIT_TYPE, id, null,
                    Json.object("consignmentId", consignmentId.toString(), "status", "PENDING"));
        }
    }

    /** Replaces the lines fed so far; an empty list clears them. */
    @Transactional
    @PreAuthorize("hasAuthority('POD_RECORD')")
    public void feedLines(UUID consignmentId, List<LineInput> lines) {
        DeliveryProof proof = requireOpen(consignmentId);
        List<LineInput> given = lines == null ? List.of() : lines;
        Set<UUID> seen = new HashSet<>();
        Set<UUID> ofConsignment = Set.copyOf(jdbc.sql("SELECT order_line_id FROM consignment_line WHERE consignment_id = :c")
                .param("c", consignmentId).query(UUID.class).list());
        for (LineInput line : given) {
            if (line.orderLineId() == null || !ofConsignment.contains(line.orderLineId())) {
                throw new BusinessRuleViolationException("pod-line-unknown",
                        "A line fed to the proof must be an order line of this consignment");
            }
            if (!seen.add(line.orderLineId())) {
                throw new BusinessRuleViolationException("pod-line-duplicate",
                        "An order line is fed once; give its received, short, damaged and excess quantities together");
            }
            quantity(line.receivedQty());
            quantity(line.shortQty());
            quantity(line.damagedQty());
            quantity(line.excessQty());
        }
        jdbc.sql("DELETE FROM delivery_proof_line WHERE proof_id = :p").param("p", proof.id()).update();
        for (LineInput line : given) {
            jdbc.sql("""
                            INSERT INTO delivery_proof_line
                                (tenant_id, proof_id, order_line_id, received_qty, short_qty, damaged_qty, excess_qty)
                            VALUES (:t, :p, :l, :received, :short, :damaged, :excess)
                            """)
                    .param("t", proof.tenantId()).param("p", proof.id()).param("l", line.orderLineId())
                    .param("received", zero(line.receivedQty())).param("short", zero(line.shortQty()))
                    .param("damaged", zero(line.damagedQty())).param("excess", zero(line.excessQty()))
                    .update();
        }
        audit.record("DELIVERY_POD_LINES_FED", AUDIT_TYPE, proof.id(), null,
                Json.object("consignmentId", consignmentId.toString(), "lines", String.valueOf(given.size())));
    }

    /** Replaces the documents listed; a type and a reference each, never the file. */
    @Transactional
    @PreAuthorize("hasAuthority('POD_RECORD')")
    public void setDocuments(UUID consignmentId, List<DocumentInput> documents) {
        DeliveryProof proof = requireOpen(consignmentId);
        List<DocumentInput> given = documents == null ? List.of() : documents;
        if (given.size() > MAX_DOCUMENTS) {
            throw new BusinessRuleViolationException("pod-documents-too-many",
                    "A proof lists at most " + MAX_DOCUMENTS + " documents");
        }
        for (DocumentInput doc : given) {
            String ref = doc.reference() == null ? "" : doc.reference().strip();
            if (doc.type() == null || ref.isEmpty() || ref.length() > MAX_REFERENCE) {
                throw new BusinessRuleViolationException("pod-document-invalid",
                        "A document has a type (FRONT, BACK or OTHER) and a reference of 1 to "
                                + MAX_REFERENCE + " characters");
            }
        }
        jdbc.sql("DELETE FROM delivery_proof_document WHERE proof_id = :p").param("p", proof.id()).update();
        int position = 0;
        for (DocumentInput doc : given) {
            jdbc.sql("""
                            INSERT INTO delivery_proof_document (id, tenant_id, proof_id, doc_type, reference, position)
                            VALUES (:id, :t, :p, :type, :ref, :pos)
                            """)
                    .param("id", UUID.randomUUID()).param("t", proof.tenantId()).param("p", proof.id())
                    .param("type", doc.type().name()).param("ref", doc.reference().strip())
                    .param("pos", position++).update();
        }
        audit.record("DELIVERY_POD_DOCUMENTS_SET", AUDIT_TYPE, proof.id(), null,
                Json.object("consignmentId", consignmentId.toString(), "documents", String.valueOf(given.size())));
    }

    /**
     * Sets when the vehicle reported and unloading started and ended. Whatever
     * is given must run in order -- reporting, unloading start, unloading end,
     * none after now -- and a time omitted is cleared.
     */
    @Transactional
    @PreAuthorize("hasAuthority('POD_RECORD')")
    public void setTimings(UUID consignmentId, Instant reportedAt, Instant unloadingStartedAt,
                           Instant unloadingEndedAt) {
        DeliveryProof proof = requireOpen(consignmentId);
        Instant now = Instant.now();
        Instant previous = null;
        for (Instant at : new Instant[] {reportedAt, unloadingStartedAt, unloadingEndedAt}) {
            if (at == null) {
                continue;
            }
            if (at.isAfter(now)) {
                throw new BusinessRuleViolationException("pod-timing-in-future",
                        "Reporting and unloading times cannot be in the future");
            }
            if (previous != null && at.isBefore(previous)) {
                throw new BusinessRuleViolationException("pod-timing-order-invalid",
                        "Reporting must not come after unloading started, nor unloading started after it ended");
            }
            previous = at;
        }
        proofs.save(proof.withTimings(reportedAt, unloadingStartedAt, unloadingEndedAt));
        audit.record("DELIVERY_POD_TIMINGS_SET", AUDIT_TYPE, proof.id(), null,
                Json.object("consignmentId", consignmentId.toString(),
                        "reportedAt", reportedAt == null ? null : reportedAt.toString(),
                        "unloadingStartedAt", unloadingStartedAt == null ? null : unloadingStartedAt.toString(),
                        "unloadingEndedAt", unloadingEndedAt == null ? null : unloadingEndedAt.toString()));
    }

    /** The consignee took nothing. The draft stays open to be fed and submitted if that turns out wrong. */
    @Transactional
    @PreAuthorize("hasAuthority('POD_RECORD')")
    public void markNotReceived(UUID consignmentId, String remark) {
        DeliveryProof proof = requireOpen(consignmentId);
        String note = remark == null ? "" : remark.strip();
        if (note.isEmpty() || note.length() > MAX_REMARK) {
            throw new BusinessRuleViolationException("proof-remark-required",
                    "Saying nothing was received needs a remark of 1 to " + MAX_REMARK + " characters");
        }
        proofs.save(proof.notReceived(note));
        audit.record("DELIVERY_POD_NOT_RECEIVED", AUDIT_TYPE, proof.id(), null,
                Json.object("consignmentId", consignmentId.toString(), "remark", note));
    }

    private DeliveryProof requireOpen(UUID consignmentId) {
        DeliveryProof proof = proofs.forConsignment(TenantContext.requireTenantId(), consignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Delivery proof", consignmentId));
        if (!proof.open()) {
            throw new BusinessRuleViolationException("proof-already-recorded",
                    "This consignment already has a proof of delivery; reverse it to change it");
        }
        return proof;
    }

    private static void quantity(BigDecimal value) {
        if (value == null) {
            return;
        }
        if (value.signum() < 0 || value.scale() > 3 && value.stripTrailingZeros().scale() > 3
                || value.compareTo(MAX_QUANTITY) > 0) {
            throw new BusinessRuleViolationException("pod-quantity-invalid",
                    "A quantity is zero or more, to three decimal places");
        }
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
