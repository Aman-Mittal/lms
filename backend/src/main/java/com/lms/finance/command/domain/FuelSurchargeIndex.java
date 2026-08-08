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
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * The fuel surcharge percentage for one month.
 *
 * <p>A stored figure rather than a call to a price feed. An external
 * dependency on the pricing path would mean invoices that cannot be raised
 * because somebody else's service is down, and a percentage that silently
 * moved under a bill somebody had already agreed. The number is published
 * monthly and entered once; that is how it arrives in practice anyway.
 *
 * <p>Correcting a month is allowed and harmless: bills store the surcharge
 * <em>amount</em> they were priced with, so a later correction changes what
 * the next trip costs and nothing that has already been billed.
 */
@Table("fuel_surcharge_index")
public record FuelSurchargeIndex(
        @Id UUID id,
        UUID tenantId,
        /** The first of the month it applies to. */
        LocalDate effectiveMonth,
        BigDecimal surchargePct,
        @Version Long version,
        Instant createdAt) {

    public static FuelSurchargeIndex of(UUID id, UUID tenantId, LocalDate anyDayInMonth,
                                        BigDecimal surchargePct) {
        return new FuelSurchargeIndex(id, tenantId, anyDayInMonth.withDayOfMonth(1),
                surchargePct, null, Instant.now());
    }

    public FuelSurchargeIndex correctTo(BigDecimal newPct) {
        return new FuelSurchargeIndex(id, tenantId, effectiveMonth, newPct, version, createdAt);
    }
}
