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
package com.lms.delivery.command.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/** A claim against a consignment. */
@Table("claim")
public record Claim(
        @Id UUID id,
        UUID tenantId,
        String claimNo,
        UUID consignmentId,
        ClaimType claimType,
        BigDecimal amount,
        String currency,
        String description,
        ClaimStatus status,
        BigDecimal approvedAmount,
        String resolutionNote,
        Instant decidedAt,
        @Version Long version,
        Instant createdAt) {

    public static Claim raise(UUID tenantId, String claimNo, UUID consignmentId, ClaimType type,
                              BigDecimal amount, String description) {
        return new Claim(UUID.randomUUID(), tenantId, claimNo, consignmentId, type, amount, "INR",
                description, ClaimStatus.OPEN, null, null, null, null, Instant.now());
    }

    public Claim moved(ClaimStatus target, BigDecimal approvedAmount, String resolutionNote) {
        boolean decision = target == ClaimStatus.APPROVED || target == ClaimStatus.REJECTED;
        return new Claim(id, tenantId, claimNo, consignmentId, claimType, amount, currency,
                description, target,
                target == ClaimStatus.APPROVED ? approvedAmount : this.approvedAmount,
                resolutionNote != null ? resolutionNote : this.resolutionNote,
                decision ? Instant.now() : decidedAt, version, createdAt);
    }
}
