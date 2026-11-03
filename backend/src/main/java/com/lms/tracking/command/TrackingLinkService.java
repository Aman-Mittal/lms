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
package com.lms.tracking.command;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import com.lms.shared.audit.TransitionAudit;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import com.lms.tracking.command.domain.TrackingLink;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issuing and revoking tracking links.
 *
 * <p>The token is the credential, so it is treated like one: 256 random bits,
 * handed back once, stored only as a hash.
 */
@Service
public class TrackingLinkService {

    public static final int DEFAULT_DAYS = 30;
    public static final int MAX_DAYS = 180;
    public static final int MAX_ACTIVE_PER_CONSIGNMENT = 10;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final TrackingLinkRepository links;
    private final JdbcClient jdbc;
    private final TransitionAudit audit;

    public TrackingLinkService(TrackingLinkRepository links, JdbcClient jdbc, TransitionAudit audit) {
        this.links = links;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /**
     * @param token the one and only time it is available
     */
    public record Issued(UUID id, String token, Instant expiresAt) {
    }

    @Transactional
    @PreAuthorize("hasAuthority('TRACKING_LINK_MANAGE')")
    public Issued issue(UUID consignmentId, Integer expiresInDays) {
        UUID tenantId = TenantContext.requireTenantId();
        int days = expiresInDays == null ? DEFAULT_DAYS : expiresInDays;
        if (days < 1 || days > MAX_DAYS) {
            throw new BusinessRuleViolationException("tracking-expiry-invalid",
                    "A link lasts between 1 and " + MAX_DAYS + " days");
        }
        requireConsignment(consignmentId);
        int active = jdbc.sql("""
                        SELECT count(*) FROM tracking_link
                         WHERE consignment_id = :c AND revoked_at IS NULL AND expires_at > now()
                        """).param("c", consignmentId).query(Integer.class).single();
        if (active >= MAX_ACTIVE_PER_CONSIGNMENT) {
            throw new BusinessRuleViolationException("tracking-link-limit",
                    "A consignment can have at most " + MAX_ACTIVE_PER_CONSIGNMENT
                            + " active links; revoke one first");
        }

        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(Duration.ofDays(days));

        TrackingLink saved = links.save(
                TrackingLink.issue(tenantId, consignmentId, hash(token), now, expiresAt));
        audit.record("TRACKING_LINK_ISSUED", "TRACKING_LINK", saved.id(), null,
                Json.object("consignmentId", consignmentId.toString(),
                        "expiresAt", expiresAt.toString()));
        return new Issued(saved.id(), token, expiresAt);
    }

    /** Revoking twice is not an error: the link is gone either way. */
    @Transactional
    @PreAuthorize("hasAuthority('TRACKING_LINK_MANAGE')")
    public void revoke(UUID id) {
        TrackingLink link = links.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tracking link", id));
        if (link.isRevoked()) {
            return;
        }
        links.save(link.revoked(Instant.now()));
        audit.record("TRACKING_LINK_REVOKED", "TRACKING_LINK", id, null,
                Json.object("consignmentId", link.consignmentId().toString()));
    }

    private void requireConsignment(UUID consignmentId) {
        if (jdbc.sql("SELECT 1 FROM consignment WHERE id = :id")
                .param("id", consignmentId).query(Integer.class).optional().isEmpty()) {
            throw new ResourceNotFoundException("Consignment", consignmentId);
        }
    }

    /** The stored form of a token. Shared with the public lookup, which must hash the same way. */
    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
