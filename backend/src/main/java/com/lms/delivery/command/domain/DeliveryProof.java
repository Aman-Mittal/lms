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

/** One consignment's proof of delivery, from the code issued to the proof recorded. */
@Table("delivery_proof")
public record DeliveryProof(
        @Id UUID id,
        UUID tenantId,
        UUID consignmentId,
        ProofStatus status,
        String otpHash,
        Instant otpIssuedAt,
        Instant otpExpiresAt,
        int otpAttempts,
        String recipientName,
        String photoRef,
        Instant deliveredAt,
        BigDecimal lat,
        BigDecimal lon,
        @Version Long version,
        Instant createdAt,
        /** CLEAN until a proof says otherwise; see {@link GoodsCondition}. */
        GoodsCondition goodsCondition,
        /** What was short or damaged; null for a clean delivery. */
        String remark) {

    public static DeliveryProof issued(UUID tenantId, UUID consignmentId, String otpHash,
                                       Instant now, Instant expiresAt) {
        return new DeliveryProof(UUID.randomUUID(), tenantId, consignmentId, ProofStatus.OTP_ISSUED,
                otpHash, now, expiresAt, 0, null, null, null, null, null, null, now,
                GoodsCondition.CLEAN, null);
    }

    /**
     * A fresh code replaces the old one and starts the count of wrong attempts
     * again. A reversed proof is waiting for a code once more.
     */
    public DeliveryProof withNewCode(String otpHash, Instant now, Instant expiresAt) {
        ProofStatus next = status == ProofStatus.REVERSED ? ProofStatus.OTP_ISSUED : status;
        return new DeliveryProof(id, tenantId, consignmentId, next, otpHash, now, expiresAt, 0,
                recipientName, photoRef, deliveredAt, lat, lon, version, createdAt, goodsCondition, remark);
    }

    public DeliveryProof afterWrongAttempt() {
        return new DeliveryProof(id, tenantId, consignmentId, status, otpHash, otpIssuedAt,
                otpExpiresAt, otpAttempts + 1, recipientName, photoRef, deliveredAt, lat, lon,
                version, createdAt, goodsCondition, remark);
    }

    /**
     * The proof recorded.
     *
     * @param condition null is CLEAN
     * @param remark    what was wrong; required unless the goods were clean, and
     *                  dropped when they were
     */
    public DeliveryProof confirmed(String recipientName, String photoRef, Instant deliveredAt,
                                   BigDecimal lat, BigDecimal lon, GoodsCondition condition,
                                   String remark) {
        GoodsCondition received = condition == null ? GoodsCondition.CLEAN : condition;
        return new DeliveryProof(id, tenantId, consignmentId, ProofStatus.CONFIRMED, otpHash,
                otpIssuedAt, otpExpiresAt, otpAttempts, recipientName, photoRef, deliveredAt, lat,
                lon, version, createdAt, received, received == GoodsCondition.CLEAN ? null : remark);
    }

    /** The confirmed proof, reversed: kept on file, no longer a proof. */
    public DeliveryProof reversed() {
        if (status != ProofStatus.CONFIRMED) {
            throw new IllegalStateException("Only a confirmed proof can be reversed");
        }
        return new DeliveryProof(id, tenantId, consignmentId, ProofStatus.REVERSED, otpHash, otpIssuedAt,
                otpExpiresAt, otpAttempts, recipientName, photoRef, deliveredAt, lat, lon, version, createdAt,
                goodsCondition, remark);
    }

    /** Whether the goods were received with nothing short or damaged. */
    public boolean clean() {
        return goodsCondition == null || goodsCondition == GoodsCondition.CLEAN;
    }
}
