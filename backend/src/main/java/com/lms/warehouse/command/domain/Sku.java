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
 * A stock-keeping unit: one thing the warehouse counts.
 *
 * <p>Per tenant, optionally owned by a business partner when the warehouse
 * stores a customer's goods.
 */
@Table("sku")
public record Sku(
        @Id UUID id,
        UUID tenantId,
        String code,
        String description,
        UUID ownerPartnerId,
        String uom,
        @Version Long version,
        Instant createdAt) {

    public static Sku create(UUID tenantId, String code, String description, UUID ownerPartnerId,
                             String uom, Instant at) {
        if (code == null || code.isBlank() || description == null || description.isBlank()) {
            throw new BusinessRuleViolationException("sku-incomplete",
                    "A SKU needs a code and a description");
        }
        String unit = uom == null || uom.isBlank() ? "EA" : uom.strip();
        return new Sku(UUID.randomUUID(), tenantId, code.strip(), description.strip(),
                ownerPartnerId, unit, null, at);
    }
}
