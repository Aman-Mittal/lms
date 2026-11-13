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
package com.lms.delivery.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A consignment's proof of delivery. The code's hash is never part of it, and
 * the code was never stored.
 */
public record DeliveryProofView(
        UUID consignmentId,
        /** PENDING, OTP_ISSUED, CONFIRMED, SUBMITTED, NOT_RECEIVED or REVERSED. */
        String status,
        String recipientName,
        String photoRef,
        Instant deliveredAt,
        BigDecimal lat,
        BigDecimal lon,
        Instant otpExpiresAt,
        /** CLEAN, SHORTAGE or DAMAGE. */
        String goodsCondition,
        /** What was short or damaged; null for a clean delivery. */
        String remark,
        /** Keyed from the signed paper copy, with no code. */
        boolean paper,
        /** Who keyed it, for a paper proof submitted by a signed-in person. */
        UUID keyedBy,
        Instant reportedAt,
        Instant unloadingStartedAt,
        Instant unloadingEndedAt,
        /** Every order line of the consignment, with what was fed to it so far. */
        List<Line> lines,
        List<Document> documents) {

    /**
     * One order line of the consignment. The quantities fed are null until the
     * line has been fed.
     */
    public record Line(UUID orderLineId, int lineNo, String materialDescription, String uom,
                       BigDecimal grossQty, BigDecimal receivedQty, BigDecimal shortQty,
                       BigDecimal damagedQty, BigDecimal excessQty) {
    }

    /** A document that came back with the proof: its kind and a reference, never the file. */
    public record Document(String type, String reference) {
    }
}
