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

package com.lms.finance.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * The customer-billing side of a manual receivable charge.
 *
 * <p>Both calls join the caller's transaction, so a charge approved or
 * cancelled and the client charge it made or removed commit together or not
 * at all.
 */
public interface ReceivableChargePort {

    /**
     * Puts the charge on the customer's next invoice, priced by its own
     * amount rather than by the contract's rates.
     *
     * <p>Idempotent on {@code sourceKey}: a second call with the same key
     * returns the charge the first made.
     *
     * @return the client charge, or empty when the customer holds no client
     *         contract to bill under (it is then not a client of this kind
     *         and the receivable is only recorded)
     */
    Optional<UUID> capture(Capture request);

    /**
     * Takes the client charge back while it is on no invoice.
     *
     * @return true when it was removed or was already gone; false when it is
     *         already invoiced and must stay
     */
    boolean withdraw(UUID clientChargeId);

    /** Whether the client charge is on an invoice already. */
    boolean isInvoiced(UUID clientChargeId);

    /**
     * @param sourceKey the caller's own key for the charge, unique per tenant
     * @param narrative the line as the invoice shows it
     * @param amount    quantity times rate, already rounded: the line's amount
     */
    record Capture(UUID customerPartnerId, String sourceKey, String narrative, BigDecimal quantity, String unit,
                   BigDecimal rate, BigDecimal amount, LocalDate occurredOn, String reference, UUID tripId) {
    }
}
