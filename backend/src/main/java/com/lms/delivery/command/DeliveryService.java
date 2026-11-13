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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

import com.lms.delivery.command.domain.DeliveryProof;
import com.lms.delivery.command.domain.GoodsCondition;
import com.lms.delivery.command.domain.ProofStatus;
import com.lms.delivery.events.DeliveryProofRecorded;
import com.lms.shared.audit.TransitionAudit;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proof of delivery: a one-time code issued for a consignment, then the proof
 * recorded when the code is matched.
 *
 * <p>The code is the consignee's word that the goods arrived, so it is treated
 * like a credential: kept only as a hash, short-lived, limited in tries, and
 * refused with one message whatever was wrong with it -- a caller must not be
 * able to tell "wrong" from "expired" from "never issued".
 */
@Service
public class DeliveryService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> PROVABLE = Set.of("IN_TRANSIT", "DELIVERED");
    /** A docket reaches its consignee only on its last leg, from the destination hub. */
    private static final Set<String> DOCKET_PROVABLE = Set.of("OUT_FOR_DELIVERY", "DELIVERED");
    private static final int MAX_NAME = 120;
    private static final int MAX_PHOTO_REF = 500;
    private static final int MAX_REMARK = 500;

    private final DeliveryProofRepository proofs;
    private final ProofChecks checks;
    private final JdbcClient jdbc;
    private final TransitionAudit audit;
    private final ApplicationEventPublisher events;
    private final Duration otpTtl;
    private final int maxAttempts;
    private final boolean exposeOtp;

    public DeliveryService(DeliveryProofRepository proofs, ProofChecks checks, JdbcClient jdbc, TransitionAudit audit,
                           ApplicationEventPublisher events,
                           @Value("${lms.delivery.otp-ttl-minutes:15}") long otpTtlMinutes,
                           @Value("${lms.delivery.otp-max-attempts:3}") int maxAttempts,
                           @Value("${lms.delivery.expose-otp:false}") boolean exposeOtp) {
        this.proofs = proofs;
        this.checks = checks;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.otpTtl = Duration.ofMinutes(otpTtlMinutes);
        this.maxAttempts = maxAttempts;
        this.exposeOtp = exposeOtp;
    }

    /**
     * @param expiresAt when the code stops working
     * @param otp       the code itself, only when this deployment exposes it (a
     *                  demo); null otherwise, because otherwise it goes to the
     *                  consignee and nowhere else
     */
    public record Issued(Instant expiresAt, String otp) {
    }

    @Transactional
    @PreAuthorize("hasAuthority('POD_RECORD')")
    public Issued issueOtp(UUID consignmentId) {
        UUID tenantId = TenantContext.requireTenantId();
        requireProvable(consignmentId);

        DeliveryProof existing = proofs.forConsignment(tenantId, consignmentId).orElse(null);
        if (existing != null && existing.status().proves()) {
            throw new BusinessRuleViolationException("proof-already-recorded",
                    "This consignment already has a confirmed proof of delivery");
        }
        String otp = "%06d".formatted(RANDOM.nextInt(1_000_000));
        Instant now = Instant.now();
        Instant expiresAt = now.plus(otpTtl);
        String hash = hash(consignmentId, otp);

        DeliveryProof saved = proofs.save(existing == null
                ? DeliveryProof.issued(tenantId, consignmentId, hash, now, expiresAt)
                : existing.withNewCode(hash, now, expiresAt));
        audit.record("DELIVERY_OTP_ISSUED", "DELIVERY_PROOF", saved.id(), null,
                Json.object("consignmentId", consignmentId.toString(),
                        "expiresAt", expiresAt.toString()));
        return new Issued(expiresAt, exposeOtp ? otp : null);
    }

    /**
     * Records the proof when the code matches.
     *
     * <p>A proof says what state the goods arrived in. Anything but CLEAN needs
     * a remark, because whoever releases the carrier's money later has to know
     * what was short or broken.
     *
     * <p>{@code noRollbackFor}: a wrong code is refused with an exception, and
     * the count of wrong attempts must survive that. Rolled back with the
     * refusal, it would never reach its limit and the limit would protect
     * nothing.
     */
    @Transactional(noRollbackFor = BusinessRuleViolationException.class)
    @PreAuthorize("hasAuthority('POD_RECORD')")
    public void recordProof(UUID consignmentId, String otp, String recipientName, String photoRef,
                            OffsetDateTime deliveredAt, BigDecimal lat, BigDecimal lon,
                            GoodsCondition condition, String remark) {
        UUID tenantId = TenantContext.requireTenantId();
        requireProvable(consignmentId);

        String name = recipientName == null ? "" : recipientName.strip();
        if (name.isEmpty() || name.length() > MAX_NAME) {
            throw new BusinessRuleViolationException("recipient-invalid",
                    "The recipient's name is 1 to " + MAX_NAME + " characters");
        }
        if (photoRef != null && photoRef.length() > MAX_PHOTO_REF) {
            throw new BusinessRuleViolationException("photo-ref-invalid",
                    "A photo reference is at most " + MAX_PHOTO_REF + " characters");
        }
        GoodsCondition received = condition == null ? GoodsCondition.CLEAN : condition;
        String note = remark == null ? null : remark.strip();
        if (received != GoodsCondition.CLEAN && (note == null || note.isEmpty())) {
            throw new BusinessRuleViolationException("proof-remark-required",
                    "A delivery recorded with a " + received.name().toLowerCase(java.util.Locale.ROOT)
                            + " needs a remark saying what was wrong");
        }
        if (note != null && note.length() > MAX_REMARK) {
            throw new BusinessRuleViolationException("proof-remark-invalid",
                    "A remark is at most " + MAX_REMARK + " characters");
        }
        if ((lat == null) != (lon == null)) {
            throw new BusinessRuleViolationException("position-incomplete",
                    "Give both a latitude and a longitude, or neither");
        }
        Instant now = Instant.now();
        Instant at = deliveredAt == null ? now : deliveredAt.toInstant();
        if (at.isAfter(now.plus(Duration.ofMinutes(5)))) {
            throw new BusinessRuleViolationException("delivered-in-future",
                    "A delivery cannot be recorded as happening in the future");
        }

        DeliveryProof proof = proofs.forConsignment(tenantId, consignmentId)
                .orElseThrow(DeliveryService::invalidCode);
        if (proof.status().proves()) {
            throw new BusinessRuleViolationException("proof-already-recorded",
                    "This consignment already has a confirmed proof of delivery");
        }
        requireCodeMatches(proof, consignmentId, otp, now);

        GoodsCondition shown = checks.verify(proof, received, photoRef != null && !photoRef.isBlank());
        requireRemarkFor(shown, note);
        DeliveryProof saved = proofs.save(proof.confirmed(name, photoRef, at, lat, lon, shown,
                note == null || note.isEmpty() ? null : note));
        audit.record("DELIVERY_PROOF_RECORDED", "DELIVERY_PROOF", saved.id(), null,
                Json.object("consignmentId", consignmentId.toString(), "recipient", name,
                        "deliveredAt", at.toString(), "condition", saved.goodsCondition().name()));
        events.publishEvent(new DeliveryProofRecorded(tenantId, consignmentId, at, Instant.now(),
                saved.goodsCondition().name()));
    }

    /**
     * Submits the fed draft with the consignee's code: the same checks as the
     * one-step route plus those the policy sets on lines, quantities and
     * documents, and the same effects -- DELIVERED, the proof hold lifted when
     * every proof is clean, the client billable.
     *
     * @param deliveredAt null is now
     */
    @Transactional(noRollbackFor = BusinessRuleViolationException.class)
    @PreAuthorize("hasAuthority('POD_RECORD')")
    public void submit(UUID consignmentId, String otp, String recipientName, OffsetDateTime deliveredAt,
                       BigDecimal lat, BigDecimal lon, GoodsCondition condition, String remark) {
        finish(consignmentId, otp, false, recipientName, deliveredAt, lat, lon, condition, remark);
    }

    /**
     * Keys the proof from the signed paper copy, with no code, under a right of
     * its own. Who keyed it is kept on the proof and in the audit trail, which
     * calls it what it is -- a proof nobody at the consignee confirmed.
     */
    @Transactional
    @PreAuthorize("hasAuthority('POD_PAPER_KEY')")
    public void keyFromPaper(UUID consignmentId, String recipientName, OffsetDateTime deliveredAt,
                             BigDecimal lat, BigDecimal lon, GoodsCondition condition, String remark) {
        finish(consignmentId, null, true, recipientName, deliveredAt, lat, lon, condition, remark);
    }

    private void finish(UUID consignmentId, String otp, boolean paper, String recipientName,
                        OffsetDateTime deliveredAt, BigDecimal lat, BigDecimal lon, GoodsCondition condition,
                        String remark) {
        UUID tenantId = TenantContext.requireTenantId();
        requireProvable(consignmentId);
        String name = recipientName == null ? "" : recipientName.strip();
        if (name.isEmpty() || name.length() > MAX_NAME) {
            throw new BusinessRuleViolationException("recipient-invalid",
                    "The recipient's name is 1 to " + MAX_NAME + " characters");
        }
        String note = remark == null || remark.isBlank() ? null : remark.strip();
        if (note != null && note.length() > MAX_REMARK) {
            throw new BusinessRuleViolationException("proof-remark-invalid",
                    "A remark is at most " + MAX_REMARK + " characters");
        }
        if ((lat == null) != (lon == null)) {
            throw new BusinessRuleViolationException("position-incomplete",
                    "Give both a latitude and a longitude, or neither");
        }
        Instant now = Instant.now();
        Instant at = deliveredAt == null ? now : deliveredAt.toInstant();
        if (at.isAfter(now.plus(Duration.ofMinutes(5)))) {
            throw new BusinessRuleViolationException("delivered-in-future",
                    "A delivery cannot be recorded as happening in the future");
        }
        DeliveryProof existing = proofs.forConsignment(tenantId, consignmentId).orElse(null);
        if (existing == null && !paper) {
            throw invalidCode();
        }
        // Paper can be keyed with no draft started: the paper is the whole of the proof.
        DeliveryProof proof = existing != null ? existing
                : proofs.save(DeliveryProof.draft(tenantId, consignmentId, now));
        if (proof.status().proves()) {
            throw new BusinessRuleViolationException("proof-already-recorded",
                    "This consignment already has a confirmed proof of delivery");
        }
        if (!paper) {
            requireCodeMatches(proof, consignmentId, otp, now);
        }
        GoodsCondition shown = checks.verify(proof, condition, false);
        requireRemarkFor(shown, note);
        UUID keyer = actor();
        DeliveryProof saved = proofs.save(proof.submitted(name, at, lat, lon, shown, note, paper,
                paper ? keyer : null));
        audit.record(paper ? "DELIVERY_POD_PAPER_KEYED" : "DELIVERY_POD_SUBMITTED", "DELIVERY_PROOF", saved.id(),
                Json.object("status", proof.status().name()),
                Json.object("consignmentId", consignmentId.toString(), "recipient", name,
                        "deliveredAt", at.toString(), "condition", saved.goodsCondition().name(),
                        "paper", String.valueOf(paper), "keyedBy", paper && keyer != null ? keyer.toString() : null));
        events.publishEvent(new DeliveryProofRecorded(tenantId, consignmentId, at, Instant.now(),
                saved.goodsCondition().name()));
    }

    private void requireCodeMatches(DeliveryProof proof, UUID consignmentId, String otp, Instant now) {
        if (proof.otpHash() == null || proof.otpAttempts() >= maxAttempts || now.isAfter(proof.otpExpiresAt())) {
            throw invalidCode();
        }
        if (!matches(consignmentId, otp, proof.otpHash())) {
            proofs.save(proof.afterWrongAttempt());
            throw invalidCode();
        }
    }

    private static void requireRemarkFor(GoodsCondition condition, String note) {
        if (condition != GoodsCondition.CLEAN && (note == null || note.isEmpty())) {
            throw new BusinessRuleViolationException("proof-remark-required",
                    "A delivery recorded with a " + condition.name().toLowerCase(java.util.Locale.ROOT)
                            + " needs a remark saying what was wrong");
        }
    }

    /** The signed-in user, or null for a service account or a test principal. */
    private static UUID actor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static BusinessRuleViolationException invalidCode() {
        return new BusinessRuleViolationException("otp-invalid",
                "The code is not valid, has expired, or has been tried too often");
    }

    /** What a consignment's status says, and whether the trip carrying it has left. */
    record Standing(String status, boolean docket, boolean departed) {

        /**
         * A full truckload is IN_TRANSIT once its trip is dispatched. One
         * dispatched before that rule still reads ASSIGNED_TO_LOAD, so for it
         * the trip on its load says whether the goods have left.
         *
         * <p>A part-load docket is proved only on its last leg: on the line
         * haul or at a hub it has not reached its consignee.
         */
        boolean provable() {
            if (docket) {
                return DOCKET_PROVABLE.contains(status);
            }
            return PROVABLE.contains(status) || ("ASSIGNED_TO_LOAD".equals(status) && departed);
        }
    }

    /**
     * A consignment must exist for the caller's tenant (row-level security hides
     * another tenant's) and have left: nothing is delivered before it is on its way.
     */
    void requireProvable(UUID consignmentId) {
        Standing standing = jdbc.sql("""
                        SELECT c.status, c.freight_mode = 'LTL' AS docket,
                               EXISTS (SELECT 1
                                         FROM load_consignment lc
                                         JOIN trip t ON t.load_id = lc.load_id
                                        WHERE lc.consignment_id = c.id
                                          AND t.status IN ('DISPATCHED', 'IN_TRANSIT',
                                                           'AT_DESTINATION', 'COMPLETED')) AS departed
                          FROM consignment c
                         WHERE c.id = :id
                        """)
                .param("id", consignmentId)
                .query((rs, row) -> new Standing(rs.getString("status"), rs.getBoolean("docket"),
                        rs.getBoolean("departed")))
                .optional()
                .orElseThrow(() -> new ResourceNotFoundException("Consignment", consignmentId));
        if (!standing.provable()) {
            throw new BusinessRuleViolationException("consignment-not-in-transit",
                    standing.docket()
                            ? "A docket's delivery can be proved only once it is out for delivery "
                                    + "from its destination hub, and this one is " + standing.status()
                            : "A delivery can be proved only for a consignment that has left, and "
                                    + "this one is " + standing.status() + " with no trip on its way");
        }
    }

    private static boolean matches(UUID consignmentId, String otp, String storedHash) {
        byte[] given = hash(consignmentId, otp == null ? "" : otp.strip()).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(given, storedHash.getBytes(StandardCharsets.UTF_8));
    }

    /** SHA-256 over the consignment and the code, so equal codes on two consignments differ. */
    private static String hash(UUID consignmentId, String otp) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest((consignmentId + ":" + otp).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
