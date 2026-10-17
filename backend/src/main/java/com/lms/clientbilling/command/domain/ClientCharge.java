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
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Something done for a client, in quantities rather than money.
 *
 * <p>Priced only at the billing run, against the contract in force on the day
 * it was done. Capturing quantities rather than amounts is what lets a charge
 * arrive before anyone has decided which contract version applies -- and what
 * keeps a captured charge from silently carrying a superseded price.
 *
 * <p>{@code billingPeriod} is the month it will be invoiced in. That is the
 * month it occurred unless that month has already been billed, in which case
 * it is the next open month: an invoice that has been sent is never changed.
 */
@Table("client_charge")
public record ClientCharge(
        @Id UUID id,
        UUID tenantId,
        UUID clientPartnerId,
        ServiceType serviceType,
        String serviceCode,
        BigDecimal quantity,
        String unit,
        LocalDate occurredOn,
        LocalDate billingPeriod,
        String sourceKey,
        String reference,
        UUID tripId,
        BigDecimal costShare,
        UUID invoiceId,
        @Version Long version,
        Instant createdAt) {

    public static ClientCharge capture(UUID id, UUID tenantId, UUID clientPartnerId,
                                       ServiceType serviceType, String serviceCode,
                                       BigDecimal quantity, String unit, LocalDate occurredOn,
                                       LocalDate billingPeriod, String sourceKey,
                                       String reference, UUID tripId, BigDecimal costShare) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("A charge needs a positive quantity");
        }
        if (occurredOn == null) {
            throw new IllegalArgumentException("A charge must say when the work was done");
        }
        if (sourceKey == null || sourceKey.isBlank()) {
            throw new IllegalArgumentException(
                    "A charge needs a source key, or a retry would bill the same work twice");
        }
        if ((serviceType == ServiceType.VALUE_ADDED) != (serviceCode != null)) {
            throw new IllegalArgumentException(serviceType == ServiceType.VALUE_ADDED
                    ? "A value-added service charge needs a service code"
                    : "Only a value-added service charge carries a service code");
        }
        return new ClientCharge(id, tenantId, clientPartnerId, serviceType,
                ContractServiceRate.normalise(serviceCode), quantity, unit, occurredOn,
                billingPeriod, sourceKey.trim(), reference, tripId, costShare, null, null,
                Instant.now());
    }

    /** The month a day falls in, as its first day. */
    public static LocalDate periodOf(LocalDate day) {
        return day.withDayOfMonth(1);
    }
}
