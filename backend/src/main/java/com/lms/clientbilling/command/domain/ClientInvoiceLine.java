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
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One charge, priced. The arithmetic a client disputes is in these lines, so
 * each records the contract version, the quantity, the unit and the rate it
 * was priced with, not only the amount.
 */
@Table("client_invoice_line")
public record ClientInvoiceLine(
        @Id UUID id,
        UUID tenantId,
        UUID invoiceId,
        int lineNo,
        UUID chargeId,
        UUID contractId,
        ServiceType serviceType,
        String narrative,
        BigDecimal quantity,
        String unit,
        BigDecimal rate,
        BigDecimal amount,
        @Version Long version,
        Instant createdAt) {

    public static ClientInvoiceLine of(UUID id, UUID tenantId, UUID invoiceId, int lineNo,
                                       UUID chargeId, UUID contractId, ChargePricing.Priced priced,
                                       ClientCharge charge) {
        return new ClientInvoiceLine(id, tenantId, invoiceId, lineNo, chargeId, contractId,
                charge.serviceType(), priced.narrative(), charge.quantity(), priced.unit(),
                priced.rate(), priced.amount(), null, Instant.now());
    }
}
