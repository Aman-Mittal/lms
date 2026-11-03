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
package com.lms.warehouse.command.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A pallet, carton or cage that leaves the warehouse as one piece.
 *
 * <p>Filled either by packing a picked order or, for a cross-dock, straight
 * from a receipt at the dock. Dispatch is the hand-off: the contents stop
 * being warehouse stock.
 */
@Table("handling_unit")
public record HandlingUnit(
        @Id UUID id,
        UUID tenantId,
        UUID warehouseId,
        String huNo,
        Source source,
        UUID outboundOrderId,
        UUID asnId,
        Status status,
        String dispatchRef,
        Instant dispatchedAt,
        /**
         * Where transport is to take it. Set at dispatch, together with the
         * consignee and the weighed gross, when the unit is handed to the
         * platform's own planning; all three absent when it leaves some other
         * way -- collected by the customer, or on somebody else's lorry.
         */
        UUID destinationTerminalId,
        UUID consigneePartnerId,
        /** Weighed at the dock on the way out. */
        BigDecimal grossWeightKg,
        BigDecimal volumeM3,
        @Version Long version,
        Instant createdAt) {

    public enum Source {
        PACK,
        CROSS_DOCK
    }

    public enum Status {
        PACKED,
        DISPATCHED
    }

    public static HandlingUnit packedFor(UUID tenantId, UUID warehouseId, UUID outboundOrderId,
                                         Instant at) {
        return new HandlingUnit(UUID.randomUUID(), tenantId, warehouseId, newHuNo(), Source.PACK,
                outboundOrderId, null, Status.PACKED, null, null, null, null, null, null, null, at);
    }

    public static HandlingUnit crossDockedFrom(UUID tenantId, UUID warehouseId, UUID asnId,
                                               Instant at) {
        return new HandlingUnit(UUID.randomUUID(), tenantId, warehouseId, newHuNo(),
                Source.CROSS_DOCK, null, asnId, Status.PACKED, null, null, null, null, null, null, null,
                at);
    }

    public HandlingUnit dispatch(String reference, Instant at) {
        return dispatch(reference, null, at);
    }

    /**
     * Hands the unit over, and -- when {@code handOff} is given -- says where
     * transport is to take it, so that planning can turn it into a
     * consignment.
     */
    public HandlingUnit dispatch(String reference, HandOff handOff, Instant at) {
        if (status != Status.PACKED) {
            throw new BusinessRuleViolationException("handling-unit-already-dispatched",
                    "Handling unit " + huNo + " was already dispatched at " + dispatchedAt);
        }
        return new HandlingUnit(id, tenantId, warehouseId, huNo, source, outboundOrderId, asnId,
                Status.DISPATCHED, reference, at,
                handOff == null ? null : handOff.destinationTerminalId(),
                handOff == null ? null : handOff.consigneePartnerId(),
                handOff == null ? null : handOff.grossWeightKg(),
                handOff == null ? null : handOff.volumeM3(),
                version, createdAt);
    }

    /**
     * What transport needs to move a unit, and the warehouse is the only
     * party that knows: where it goes, to whom, and what it weighed on the
     * scale at the dock door.
     *
     * <p>All of destination, consignee and weight, or none -- a half-addressed
     * unit cannot be planned and is a data-entry mistake, not a choice.
     *
     * @param volumeM3 optional; absent means not measured, and the unit is
     *                 then charged on its weight alone
     */
    public record HandOff(UUID destinationTerminalId, UUID consigneePartnerId,
                          BigDecimal grossWeightKg, BigDecimal volumeM3) {

        public HandOff {
            if (destinationTerminalId == null || consigneePartnerId == null
                    || grossWeightKg == null) {
                throw new BusinessRuleViolationException("hand-off-incomplete",
                        "A hand-off to transport needs a destination, a consignee and a "
                                + "gross weight together");
            }
            if (grossWeightKg.signum() <= 0) {
                throw new BusinessRuleViolationException("hand-off-weight-not-positive",
                        "A handling unit handed to transport must weigh something");
            }
            if (volumeM3 != null && volumeM3.signum() < 0) {
                throw new BusinessRuleViolationException("hand-off-volume-negative",
                        "A handling unit cannot have a negative volume");
            }
        }

        /** Null when nothing at all was given, which is a dispatch without a hand-off. */
        public static HandOff ofNullable(UUID destinationTerminalId, UUID consigneePartnerId,
                                         BigDecimal grossWeightKg, BigDecimal volumeM3) {
            if (destinationTerminalId == null && consigneePartnerId == null
                    && grossWeightKg == null && volumeM3 == null) {
                return null;
            }
            return new HandOff(destinationTerminalId, consigneePartnerId, grossWeightKg,
                    volumeM3);
        }
    }

    /**
     * Random rather than sequential, as with exception tickets: a per-tenant
     * counter would need a lock on every pack, and a clash is caught by the
     * unique constraint regardless.
     */
    private static String newHuNo() {
        return "HU-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }
}
