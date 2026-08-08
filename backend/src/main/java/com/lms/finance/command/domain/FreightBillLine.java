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
package com.lms.finance.command.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One line of the arithmetic behind a freight bill.
 *
 * <p>A bill carrying only a total is unarguable. A vendor disputing 4 200 has
 * no way to see that 3 000 of it is linehaul and 900 is detention they did not
 * think they had incurred -- and the conversation that follows is about
 * suspicion rather than about numbers. These lines are the entire content of
 * that conversation.
 */
@Table("freight_bill_line")
public record FreightBillLine(
        @Id UUID id,
        UUID tenantId,
        UUID billId,
        int lineNo,
        ChargeType chargeType,
        String narrative,
        BigDecimal quantity,
        String unit,
        BigDecimal rate,
        BigDecimal amount,
        @Version Long version,
        Instant createdAt) {

    public enum ChargeType {
        BASE_FREIGHT,
        /** The gap between the slab rate and the lane's floor, shown rather than hidden. */
        MINIMUM_CHARGE_UPLIFT,
        DETENTION,
        ADDITIONAL_DROP,
        FUEL_SURCHARGE
    }

    public static FreightBillLine of(UUID id, UUID tenantId, UUID billId, int lineNo,
                                     ChargeType chargeType, String narrative,
                                     BigDecimal quantity, String unit, BigDecimal rate,
                                     BigDecimal amount) {
        return new FreightBillLine(id, tenantId, billId, lineNo, chargeType, narrative,
                quantity, unit, rate, amount, null, Instant.now());
    }
}
