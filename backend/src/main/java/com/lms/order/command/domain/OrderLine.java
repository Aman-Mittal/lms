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
package com.lms.order.command.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

import com.lms.order.api.MaterialClass;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A single material requirement within an order (vision document 3.3).
 *
 * <p>Carries the dimensional data that decides what the customer is actually
 * billed for.
 */
@Table("order_line")
public record OrderLine(
        @Id UUID id,
        UUID tenantId,
        UUID orderId,
        int lineNo,
        String materialCode,
        String materialDescription,
        MaterialClass materialClass,
        String hazmatUnCode,
        BigDecimal quantity,
        String uom,
        BigDecimal deadWeightKg,
        BigDecimal lengthM,
        BigDecimal widthM,
        BigDecimal heightM,
        BigDecimal volumetricDivisor,
        UUID consigneePartnerId,
        UUID destinationTerminalId,
        boolean planned,
        /**
         * Cancelled on its own after validation: no longer demand, never
         * planned again, kept as the record of what was asked for.
         */
        boolean cancelled,
        @Version Long version,
        Instant createdAt,
        /** How the line was closed; null while it is open. */
        CloseKind closeKind,
        String closeReason,
        Instant closedAt) {

    /**
     * Divisor applied when a line does not carry its own.
     *
     * <p>5000 is the common road-freight convention for centimetre dimensions.
     * The divisor is stored per line rather than read from configuration at
     * calculation time, because it is a commercial term: changing a default
     * must never retrospectively alter what an existing order was billed.
     */
    public static final BigDecimal DEFAULT_VOLUMETRIC_DIVISOR = new BigDecimal("5000");

    private static final int WEIGHT_SCALE = 3;

    /** Cubic metres occupied, or zero when the line carries no dimensions. */
    public BigDecimal volumeM3() {
        if (lengthM == null || widthM == null || heightM == null) {
            return BigDecimal.ZERO;
        }
        return lengthM.multiply(widthM).multiply(heightM)
                .multiply(quantity)
                .setScale(WEIGHT_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * The billable weight of the space this line occupies.
     *
     * <p>Volume in cubic metres is converted to cubic centimetres before
     * dividing, because the divisor convention is expressed in centimetre
     * dimensions. Getting that conversion wrong is a factor of a million, which
     * is not a subtle error but is an easy one.
     */
    public BigDecimal volumetricWeightKg() {
        BigDecimal volume = volumeM3();
        if (volume.signum() == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal divisor = volumetricDivisor == null ? DEFAULT_VOLUMETRIC_DIVISOR : volumetricDivisor;
        BigDecimal volumeCm3 = volume.multiply(new BigDecimal("1000000"));
        return volumeCm3.divide(divisor, WEIGHT_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * What the customer is billed on: the greater of actual weight and
     * volumetric weight (vision document 3.3).
     *
     * <p>The rule exists because a lorry runs out of space long before it runs
     * out of payload for light bulky freight. Billing such a load on dead
     * weight alone would give away the vehicle.
     */
    public BigDecimal chargeableWeightKg() {
        return deadWeightKg.max(volumetricWeightKg()).setScale(WEIGHT_SCALE, RoundingMode.HALF_UP);
    }

    /** True when the line carries dangerous goods and needs certified capacity. */
    public boolean isHazmat() {
        return hazmatUnCode != null && !hazmatUnCode.isBlank();
    }

    /** Whether this line may share a transport unit with another. */
    public boolean isCompatibleWith(OrderLine other) {
        return materialClass.isCompatibleWith(other.materialClass());
    }

    /**
     * The grouping key for consignment generation (vision document 3.4.1):
     * same consignee, same destination.
     */
    public GroupingKey groupingKey() {
        return new GroupingKey(consigneePartnerId, destinationTerminalId);
    }

    /** Identifies "the same consignee at the same location". */
    public record GroupingKey(UUID consigneePartnerId, UUID destinationTerminalId) {
    }

    /**
     * Marks the line as taken onto a consignment.
     *
     * <p>Idempotent: re-marking an already-planned line returns the same
     * instance rather than issuing a pointless update, so a retried
     * consignment generation costs nothing.
     */
    public OrderLine markPlanned() {
        if (planned) {
            return this;
        }
        return new OrderLine(id, tenantId, orderId, lineNo, materialCode, materialDescription,
                materialClass, hazmatUnCode, quantity, uom, deadWeightKg, lengthM, widthM, heightM,
                volumetricDivisor, consigneePartnerId, destinationTerminalId, true, cancelled,
                version, createdAt, closeKind, closeReason, closedAt);
    }

    /** Whether the line has been closed, on its own or with its order. */
    public boolean isClosed() {
        return closedAt != null;
    }

    /**
     * Whether the line is still demand: neither closed nor cancelled. Planning,
     * coverage and credit exposure only count open lines.
     */
    public boolean isOpen() {
        return !isClosed() && !cancelled;
    }

    /**
     * The line, closed. A line that was never planned never will be: planning
     * is offered only open lines.
     */
    public OrderLine closed(CloseKind kind, String reason, Instant at) {
        if (isClosed()) {
            return this;
        }
        return new OrderLine(id, tenantId, orderId, lineNo, materialCode, materialDescription,
                materialClass, hazmatUnCode, quantity, uom, deadWeightKg, lengthM, widthM, heightM,
                volumetricDivisor, consigneePartnerId, destinationTerminalId, planned, cancelled,
                version, createdAt, kind, reason, at);
    }

    /**
     * The line released from the consignment it was on, because that
     * consignment was cancelled: it is demand waiting for a consignment again.
     * Idempotent, like {@link #markPlanned}.
     */
    public OrderLine released() {
        if (!planned) {
            return this;
        }
        return new OrderLine(id, tenantId, orderId, lineNo, materialCode, materialDescription,
                materialClass, hazmatUnCode, quantity, uom, deadWeightKg, lengthM, widthM, heightM,
                volumetricDivisor, consigneePartnerId, destinationTerminalId, false, cancelled,
                version, createdAt, closeKind, closeReason, closedAt);
    }

    /**
     * The line cancelled. Its consignment, if it had one, must already have
     * been cancelled and the line released from it: a cancelled line is never
     * on a consignment, which the database also insists on.
     */
    public OrderLine cancel() {
        if (planned) {
            throw new IllegalStateException("Line " + lineNo + " is still on a consignment");
        }
        return new OrderLine(id, tenantId, orderId, lineNo, materialCode, materialDescription,
                materialClass, hazmatUnCode, quantity, uom, deadWeightKg, lengthM, widthM, heightM,
                volumetricDivisor, consigneePartnerId, destinationTerminalId, false, true,
                version, createdAt, closeKind, closeReason, closedAt);
    }
}
