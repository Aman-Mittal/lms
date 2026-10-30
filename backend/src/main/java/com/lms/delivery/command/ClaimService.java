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
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;

import com.lms.delivery.command.domain.Claim;
import com.lms.delivery.command.domain.ClaimStatus;
import com.lms.delivery.command.domain.ClaimType;
import com.lms.delivery.command.domain.ProofStatus;
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
 * Raising a claim and deciding it.
 *
 * <p>Two permissions, on purpose. Whoever holds the delivery desk can raise a
 * claim; only someone with {@code CLAIM_DECIDE} moves it, because deciding is
 * granting money.
 */
@Service
public class ClaimService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999.99");
    private static final int MAX_TEXT = 1000;

    private final ClaimRepository claims;
    private final DeliveryProofRepository proofs;
    private final JdbcClient jdbc;
    private final TransitionAudit audit;

    public ClaimService(ClaimRepository claims, DeliveryProofRepository proofs, JdbcClient jdbc,
                        TransitionAudit audit) {
        this.claims = claims;
        this.proofs = proofs;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional
    @PreAuthorize("hasAuthority('CLAIM_RAISE')")
    public UUID raise(UUID consignmentId, ClaimType type, BigDecimal amount, String description) {
        UUID tenantId = TenantContext.requireTenantId();
        requireMoney(amount, "claim-amount-invalid", "The claimed amount");
        String text = description == null ? "" : description.strip();
        if (text.isEmpty() || text.length() > MAX_TEXT) {
            throw new BusinessRuleViolationException("claim-description-invalid",
                    "A description is 1 to " + MAX_TEXT + " characters");
        }
        boolean exists = jdbc.sql("SELECT 1 FROM consignment WHERE id = :id")
                .param("id", consignmentId).query(Integer.class).optional().isPresent();
        if (!exists) {
            throw new ResourceNotFoundException("Consignment", consignmentId);
        }
        if (type.needsProofOfDelivery()) {
            boolean proved = proofs.forConsignment(tenantId, consignmentId)
                    .filter(p -> p.status() == ProofStatus.CONFIRMED).isPresent();
            if (!proved) {
                throw new BusinessRuleViolationException("claim-needs-proof-of-delivery",
                        "A " + type + " claim needs a confirmed proof of delivery for the consignment");
            }
        }
        String claimNo = "CLM-" + LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + randomHex(3);
        Claim saved = claims.save(Claim.raise(tenantId, claimNo, consignmentId, type,
                amount, text));
        audit.record("CLAIM_RAISED", "CLAIM", saved.id(), null,
                Json.object("claimNo", claimNo, "consignmentId", consignmentId.toString(),
                        "type", type.name(), "amount", amount.toPlainString()));
        return saved.id();
    }

    /**
     * Moves a claim along its lifecycle. Approval states what is granted, which
     * is more than zero and no more than was claimed; rejection says why.
     */
    @Transactional
    @PreAuthorize("hasAuthority('CLAIM_DECIDE')")
    public void transition(UUID id, ClaimStatus target, BigDecimal approvedAmount, String resolutionNote) {
        Claim claim = claims.findById(id).orElseThrow(() -> new ResourceNotFoundException("Claim", id));
        if (!claim.status().canMoveTo(target)) {
            throw new BusinessRuleViolationException("claim-transition-invalid",
                    "A claim that is " + claim.status() + " cannot become " + target);
        }
        String note = resolutionNote == null || resolutionNote.isBlank() ? null : resolutionNote.strip();
        if (note != null && note.length() > MAX_TEXT) {
            throw new BusinessRuleViolationException("claim-note-invalid",
                    "A resolution note is at most " + MAX_TEXT + " characters");
        }
        if (target == ClaimStatus.APPROVED) {
            requireMoney(approvedAmount, "claim-approved-amount-invalid", "The approved amount");
            if (approvedAmount.compareTo(claim.amount()) > 0) {
                throw new BusinessRuleViolationException("claim-approved-amount-invalid",
                        "The approved amount " + approvedAmount.toPlainString()
                                + " is more than the " + claim.amount().toPlainString() + " claimed");
            }
        }
        if (target == ClaimStatus.REJECTED && note == null) {
            throw new BusinessRuleViolationException("claim-rejection-unexplained",
                    "A rejection needs a reason");
        }
        claims.save(claim.moved(target, approvedAmount, note));
        audit.record("CLAIM_" + target.name(), "CLAIM", id,
                Json.object("status", claim.status().name()),
                Json.object("status", target.name(),
                        "approvedAmount", target == ClaimStatus.APPROVED ? approvedAmount.toPlainString() : null));
    }

    private static void requireMoney(BigDecimal value, String code, String what) {
        if (value == null || value.signum() <= 0 || value.stripTrailingZeros().scale() > 2
                || value.compareTo(MAX_AMOUNT) > 0) {
            throw new BusinessRuleViolationException(code,
                    what + " must be more than zero, with at most two decimals");
        }
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().withUpperCase().formatHex(b);
    }
}
