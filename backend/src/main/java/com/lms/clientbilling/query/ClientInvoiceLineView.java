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
package com.lms.clientbilling.query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One priced charge, with the contract version and the day that chose it.
 *
 * @param amount          the taxable amount
 * @param taxRate         percent, from the contract version
 * @param taxAmount       the amount at the rate, half-up to two decimals
 * @param lineTotal       amount plus tax
 * @param creditedAmount  taxable amount credited against this line so far
 */
public record ClientInvoiceLineView(
        int lineNo,
        String serviceType,
        String narrative,
        BigDecimal quantity,
        String unit,
        BigDecimal rate,
        BigDecimal amount,
        BigDecimal taxRate,
        BigDecimal taxAmount,
        BigDecimal lineTotal,
        BigDecimal creditedAmount,
        UUID chargeId,
        LocalDate occurredOn,
        String contractCode) {
}
