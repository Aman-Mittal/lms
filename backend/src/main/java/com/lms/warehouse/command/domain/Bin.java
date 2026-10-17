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

import java.time.Instant;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A storage location inside a warehouse, holding at most {@code capacityUnits}.
 *
 * <p>Capacity is a count of units, whatever the SKU. Volume and weight limits
 * are not modelled; a bin that fills by volume first is configured with the
 * unit count that fills it.
 */
@Table("warehouse_bin")
public record Bin(
        @Id UUID id,
        UUID tenantId,
        UUID warehouseId,
        String code,
        int capacityUnits,
        @Version Long version,
        Instant createdAt) {

    public static Bin create(UUID tenantId, UUID warehouseId, String code, int capacityUnits,
                             Instant at) {
        if (code == null || code.isBlank()) {
            throw new BusinessRuleViolationException("bin-code-required", "A bin needs a code");
        }
        if (capacityUnits <= 0) {
            throw new BusinessRuleViolationException("bin-capacity-invalid",
                    "Bin " + code + " needs a capacity of at least one unit");
        }
        return new Bin(UUID.randomUUID(), tenantId, warehouseId, code.strip(), capacityUnits,
                null, at);
    }
}
