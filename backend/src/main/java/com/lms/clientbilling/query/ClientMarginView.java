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
 * What the 3PL made on one client in one billed month.
 *
 * @param receivable          the invoice total
 * @param transportReceivable the part of it for movements
 * @param carrierPayable      what the carriers' freight bills computed for the
 *                            same movements, apportioned by the client's share
 *                            of each load's chargeable weight
 * @param margin              receivable less carrier payable
 * @param unpricedMovements   movements whose carrier bill is missing or
 *                            unpriced, so whose cost is understated as zero
 */
public record ClientMarginView(
        UUID clientPartnerId,
        String clientCode,
        String clientName,
        UUID invoiceId,
        String invoiceNo,
        LocalDate periodStart,
        String currency,
        BigDecimal receivable,
        BigDecimal transportReceivable,
        BigDecimal warehouseReceivable,
        BigDecimal carrierPayable,
        BigDecimal margin,
        BigDecimal marginPct,
        int movements,
        int unpricedMovements) {
}
