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

/**
 * One consignment's proof of delivery, from the draft started or the code
 * issued to the proof recorded. The lines and documents fed to it live in
 * their own tables, read and written beside this row.
 */
@Table("delivery_proof")
public record DeliveryProof(
        @Id UUID id,
        UUID tenantId,
        UUID consignmentId,
        ProofStatus status,
        /** Null for a draft no code has been issued for. */
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
        String remark,
        /** Keyed by staff from the signed paper copy, with no code. */
        boolean paper,
        /** Who keyed the proof; null unless it was submitted by a signed-in person. */
        UUID keyedBy,
        Instant reportedAt,
        Instant unloadingStartedAt,
        Instant unloadingEndedAt) {

    public static DeliveryProof issued(UUID tenantId, UUID consignmentId, String otpHash,
                                       Instant now, Instant expiresAt) {
        return new DeliveryProof(UUID.randomUUID(), tenantId, consignmentId, ProofStatus.OTP_ISSUED,
                otpHash, now, expiresAt, 0, null, null, null, null, null, null, now,
                GoodsCondition.CLEAN, null, false, null, null, null, null);
    }

    /** A draft to be fed over time; no code, no recipient. */
    public static DeliveryProof draft(UUID tenantId, UUID consignmentId, Instant now) {
        return new DeliveryProof(UUID.randomUUID(), tenantId, consignmentId, ProofStatus.PENDING,
                null, null, null, 0, null, null, null, null, null, null, now,
                GoodsCondition.CLEAN, null, false, null, null, null, null);
    }

    private DeliveryProof with(ProofStatus status, String otpHash, Instant otpIssuedAt, Instant otpExpiresAt,
                               int otpAttempts, String recipientName, String photoRef, Instant deliveredAt,
                               BigDecimal lat, BigDecimal lon, GoodsCondition condition, String remark,
                               boolean paper, UUID keyedBy) {
        return new DeliveryProof(id, tenantId, consignmentId, status, otpHash, otpIssuedAt, otpExpiresAt,
                otpAttempts, recipientName, photoRef, deliveredAt, lat, lon, version, createdAt, condition,
                remark, paper, keyedBy, reportedAt, unloadingStartedAt, unloadingEndedAt);
    }

    /**
     * A fresh code replaces the old one and starts the count of wrong attempts
     * again. A reversed proof, a draft or a refused one is waiting for a code.
     */
    public DeliveryProof withNewCode(String otpHash, Instant now, Instant expiresAt) {
        ProofStatus next = status.proves() || status == ProofStatus.OTP_ISSUED ? status : ProofStatus.OTP_ISSUED;
        return with(next, otpHash, now, expiresAt, 0, recipientName, photoRef, deliveredAt, lat, lon,
                goodsCondition, remark, paper, keyedBy);
    }

    public DeliveryProof afterWrongAttempt() {
        return with(status, otpHash, otpIssuedAt, otpExpiresAt, otpAttempts + 1, recipientName, photoRef,
                deliveredAt, lat, lon, goodsCondition, remark, paper, keyedBy);
    }

    /**
     * The proof recorded in one step, by code.
     *
     * @param condition null is CLEAN
     * @param remark    what was wrong; required unless the goods were clean, and
     *                  dropped when they were
     */
    public DeliveryProof confirmed(String recipientName, String photoRef, Instant deliveredAt,
                                   BigDecimal lat, BigDecimal lon, GoodsCondition condition,
                                   String remark) {
        GoodsCondition received = condition == null ? GoodsCondition.CLEAN : condition;
        return with(ProofStatus.CONFIRMED, otpHash, otpIssuedAt, otpExpiresAt, otpAttempts, recipientName,
                photoRef, deliveredAt, lat, lon, received, received == GoodsCondition.CLEAN ? null : remark,
                false, null);
    }

    /**
     * The fed draft submitted, with a code ({@code paper} false) or keyed from
     * paper by {@code keyedBy}.
     */
    public DeliveryProof submitted(String recipientName, Instant deliveredAt, BigDecimal lat, BigDecimal lon,
                                   GoodsCondition condition, String remark, boolean paper, UUID keyedBy) {
        GoodsCondition received = condition == null ? GoodsCondition.CLEAN : condition;
        return with(ProofStatus.SUBMITTED, otpHash, otpIssuedAt, otpExpiresAt, otpAttempts, recipientName,
                photoRef, deliveredAt, lat, lon, received, received == GoodsCondition.CLEAN ? null : remark,
                paper, keyedBy);
    }

    /** The consignee took nothing. */
    public DeliveryProof notReceived(String remark) {
        return with(ProofStatus.NOT_RECEIVED, otpHash, otpIssuedAt, otpExpiresAt, otpAttempts, recipientName,
                photoRef, deliveredAt, lat, lon, GoodsCondition.SHORTAGE, remark, false, null);
    }

    /** The unloading times, replaced as given. */
    public DeliveryProof withTimings(Instant reportedAt, Instant unloadingStartedAt, Instant unloadingEndedAt) {
        return new DeliveryProof(id, tenantId, consignmentId, status, otpHash, otpIssuedAt, otpExpiresAt,
                otpAttempts, recipientName, photoRef, deliveredAt, lat, lon, version, createdAt, goodsCondition,
                remark, paper, keyedBy, reportedAt, unloadingStartedAt, unloadingEndedAt);
    }

    /** The proof, reversed: kept on file, no longer a proof. */
    public DeliveryProof reversed() {
        if (!status.proves()) {
            throw new IllegalStateException("Only a confirmed or submitted proof can be reversed");
        }
        return with(ProofStatus.REVERSED, otpHash, otpIssuedAt, otpExpiresAt, otpAttempts, recipientName,
                photoRef, deliveredAt, lat, lon, goodsCondition, remark, paper, keyedBy);
    }

    /** Whether a draft may still be fed and submitted: it is not (yet) a proof. */
    public boolean open() {
        return !status.proves();
    }

    /** Whether the goods were received with nothing short or damaged. */
    public boolean clean() {
        return goodsCondition == null || goodsCondition == GoodsCondition.CLEAN;
    }
}
