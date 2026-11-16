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

package com.lms.order.command.intake;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lms.order.api.MaterialClass;

/**
 * One order of a bulk batch, with the key that makes sending it twice safe.
 * It is raised, given its lines and validated by the same commands an order
 * typed in goes through.
 */
public record BulkItem(String idempotencyKey, UUID customerPartnerId, String orderNo, UUID originTerminalId,
                       Instant requestedPickupAt, Instant requestedDeliveryAt, String loadType, List<Line> lines) {

    public BulkItem {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** One line of the order; the class is GENERAL when omitted. */
    public record Line(String materialCode, String materialDescription, MaterialClass materialClass,
                       String hazmatUnCode, BigDecimal quantity, String uom, BigDecimal deadWeightKg,
                       UUID consigneePartnerId, UUID destinationTerminalId) {
    }

    /** A digest of the item as sent, so that a key reused for other content is told apart from a retry. */
    String digest() {
        StringBuilder canonical = new StringBuilder();
        canonical.append(customerPartnerId).append('|').append(orderNo).append('|').append(originTerminalId)
                .append('|').append(requestedPickupAt).append('|').append(requestedDeliveryAt).append('|')
                .append(loadType);
        for (Line line : lines) {
            canonical.append('|').append(line.materialCode()).append(',').append(line.materialDescription())
                    .append(',').append(line.materialClass()).append(',').append(line.hazmatUnCode()).append(',')
                    .append(line.quantity() == null ? null : line.quantity().stripTrailingZeros().toPlainString())
                    .append(',').append(line.uom()).append(',')
                    .append(line.deadWeightKg() == null ? null : line.deadWeightKg().stripTrailingZeros().toPlainString())
                    .append(',').append(line.consigneePartnerId()).append(',').append(line.destinationTerminalId());
        }
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
    }
}
