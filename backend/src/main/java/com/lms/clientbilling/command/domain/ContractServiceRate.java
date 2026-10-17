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
package com.lms.clientbilling.command.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * The price of one value-added service under one contract version.
 *
 * <p>Written only when the contract is published, and immutable thereafter
 * like the contract itself: a service rate is part of the version.
 */
@Table("client_contract_service_rate")
public record ContractServiceRate(
        @Id UUID id,
        UUID tenantId,
        UUID contractId,
        String serviceCode,
        String unit,
        BigDecimal rate,
        @Version Long version,
        Instant createdAt) {

    public static ContractServiceRate of(UUID id, UUID tenantId, UUID contractId,
                                         String serviceCode, String unit, BigDecimal rate) {
        if (serviceCode == null || serviceCode.isBlank()) {
            throw new IllegalArgumentException("A value-added service rate needs a service code");
        }
        if (unit == null || unit.isBlank()) {
            throw new IllegalArgumentException("A value-added service rate needs a unit");
        }
        if (rate == null || rate.signum() < 0) {
            throw new IllegalArgumentException("A value-added service rate cannot be negative");
        }
        return new ContractServiceRate(id, tenantId, contractId, normalise(serviceCode),
                unit.trim().toUpperCase(Locale.ROOT), rate, null, Instant.now());
    }

    /** Service codes compare case-insensitively; stored upper case. */
    public static String normalise(String serviceCode) {
        return serviceCode == null ? null : serviceCode.trim().toUpperCase(Locale.ROOT);
    }
}
