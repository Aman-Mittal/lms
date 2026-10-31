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
    private static final int MAX_NAME = 120;
    private static final int MAX_PHOTO_REF = 500;

    private final DeliveryProofRepository proofs;
    private final JdbcClient jdbc;
    private final TransitionAudit audit;
    private final ApplicationEventPublisher events;
    private final Duration otpTtl;
    private final int maxAttempts;
    private final boolean exposeOtp;

    public DeliveryService(DeliveryProofRepository proofs, JdbcClient jdbc, TransitionAudit audit,
                           ApplicationEventPublisher events,
                           @Value("${lms.delivery.otp-ttl-minutes:15}") long otpTtlMinutes,
                           @Value("${lms.delivery.otp-max-attempts:3}") int maxAttempts,
                           @Value("${lms.delivery.expose-otp:false}") boolean exposeOtp) {
        this.proofs = proofs;
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
        if (existing != null && existing.status() == ProofStatus.CONFIRMED) {
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
     * <p>{@code noRollbackFor}: a wrong code is refused with an exception, and
     * the count of wrong attempts must survive that. Rolled back with the
     * refusal, it would never reach its limit and the limit would protect
     * nothing.
     */
    @Transactional(noRollbackFor = BusinessRuleViolationException.class)
    @PreAuthorize("hasAuthority('POD_RECORD')")
    public void recordProof(UUID consignmentId, String otp, String recipientName, String photoRef,
                            OffsetDateTime deliveredAt, BigDecimal lat, BigDecimal lon) {
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
        if (proof.status() == ProofStatus.CONFIRMED) {
            throw new BusinessRuleViolationException("proof-already-recorded",
                    "This consignment already has a confirmed proof of delivery");
        }
        if (proof.otpAttempts() >= maxAttempts || now.isAfter(proof.otpExpiresAt())) {
            throw invalidCode();
        }
        if (!matches(consignmentId, otp, proof.otpHash())) {
            proofs.save(proof.afterWrongAttempt());
            throw invalidCode();
        }

        DeliveryProof saved = proofs.save(proof.confirmed(name, photoRef, at, lat, lon));
        audit.record("DELIVERY_PROOF_RECORDED", "DELIVERY_PROOF", saved.id(), null,
                Json.object("consignmentId", consignmentId.toString(), "recipient", name,
                        "deliveredAt", at.toString()));
        events.publishEvent(new DeliveryProofRecorded(tenantId, consignmentId, at, Instant.now()));
    }

    private static BusinessRuleViolationException invalidCode() {
        return new BusinessRuleViolationException("otp-invalid",
                "The code is not valid, has expired, or has been tried too often");
    }

    /** What a consignment's status says, and whether the trip carrying it has left. */
    private record Standing(String status, boolean departed) {

        /**
         * A full-truckload consignment never advances past ASSIGNED_TO_LOAD on
         * its own -- only LTL dockets do -- so its status alone cannot say the
         * goods have left. The trip on its load can.
         */
        boolean provable() {
            return PROVABLE.contains(status) || ("ASSIGNED_TO_LOAD".equals(status) && departed);
        }
    }

    /**
     * A consignment must exist for the caller's tenant (row-level security hides
     * another tenant's) and have left: nothing is delivered before it is on its way.
     */
    private void requireProvable(UUID consignmentId) {
        Standing standing = jdbc.sql("""
                        SELECT c.status,
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
                .query((rs, row) -> new Standing(rs.getString("status"), rs.getBoolean("departed")))
                .optional()
                .orElseThrow(() -> new ResourceNotFoundException("Consignment", consignmentId));
        if (!standing.provable()) {
            throw new BusinessRuleViolationException("consignment-not-in-transit",
                    "A delivery can be proved only for a consignment that has left, and this one is "
                            + standing.status() + " with no trip on its way");
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
