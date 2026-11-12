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

import com.lms.shared.error.BusinessRuleViolationException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/** One payment made to a vendor against an invoice: an amount, the day, how, and the bank's or cheque's reference. */
@Table("vendor_payment")
public record VendorPayment(
        @Id UUID id,
        UUID tenantId,
        UUID invoiceId,
        UUID vendorPartnerId,
        BigDecimal amount,
        LocalDate paidOn,
        Mode mode,
        String reference,
        UUID recordedBy,
        @Version Long version,
        Instant createdAt) {

    public enum Mode {
        BANK_TRANSFER,
        CHEQUE,
        CASH,
        OTHER
    }

    public static VendorPayment record(UUID id, UUID tenantId, UUID invoiceId, UUID vendorPartnerId,
                                       BigDecimal amount, LocalDate paidOn, Mode mode, String reference,
                                       UUID recordedBy) {
        if (reference == null || reference.isBlank() || reference.strip().length() > 80) {
            throw new BusinessRuleViolationException("vendor-payment-reference-required",
                    "A payment carries the bank, cheque or voucher reference, 1 to 80 characters");
        }
        if (paidOn == null) {
            throw new BusinessRuleViolationException("vendor-payment-date-required", "A payment says the day it was made");
        }
        return new VendorPayment(id, tenantId, invoiceId, vendorPartnerId, amount, paidOn,
                mode == null ? Mode.BANK_TRANSFER : mode, reference.strip(), recordedBy, null, Instant.now());
    }
}
