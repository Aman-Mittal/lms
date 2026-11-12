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

package com.lms.finance.query;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The balance settlement of one freight bill: what it was approved at (freight
 * and the manual charges on it), the advances paid against its trip, and what
 * is therefore still payable. Never a stored figure: every amount here is
 * derived from the records it names.
 *
 * @param approvedAmount  the vendor's claim the bill was approved against, else
 *                        the computed amount; the computed amount while the
 *                        bill is not yet approved
 * @param freightAmount   the approved amount less the manual charges on it
 * @param chargesAmount   the manual charges that are lines of the bill
 * @param advancesPaid    advances paid on the bill's trip, deducted or not
 * @param advancesDeducted the part of them already taken off a vendor invoice
 * @param netPayable      the approved amount less the advances paid
 */
public record BillSettlementView(
        UUID billId,
        String billNo,
        String status,
        String currency,
        UUID tripId,
        String tripNo,
        BigDecimal freightAmount,
        BigDecimal chargesAmount,
        BigDecimal approvedAmount,
        BigDecimal advancesPaid,
        BigDecimal advancesDeducted,
        BigDecimal netPayable,
        UUID invoiceId,
        String invoiceNo,
        String invoiceStatus,
        BigDecimal invoiceOutstanding,
        List<VendorAdvanceView> advances) {
}
