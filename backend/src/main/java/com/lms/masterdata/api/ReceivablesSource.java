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
package com.lms.masterdata.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What a client owes and how its transport is priced.
 *
 * <p>Implemented by client billing, which holds contracts, charges, invoices,
 * credit notes and payments.
 */
public interface ReceivablesSource {

    /** Uninvoiced charges and unpaid invoices of one client. */
    Receivables receivablesOf(UUID clientPartnerId);

    /**
     * How much of each order's chargeable weight is already on a posted
     * transport charge: the weight of the order's consignments carried on
     * trips this client has been charged for. Orders with nothing charged are
     * absent.
     */
    Map<UUID, BigDecimal> chargedWeightByOrder(UUID clientPartnerId, Collection<UUID> orderIds);

    /** The transport terms of the client contract in force on {@code day}, if any. */
    Optional<TransportTariff> transportTariff(UUID clientPartnerId, LocalDate day);

    /**
     * @param currency            of the contract in force today, or the latest
     *                            invoice; null when neither exists
     * @param unbilledCharges     charges not yet on an invoice, priced and
     *                            taxed as the billing run would price them
     * @param unpricedChargeCount charges no contract could price, counted at zero
     * @param outstandingInvoices issued totals less credit notes and payments,
     *                            never below zero
     */
    record Receivables(String currency, BigDecimal unbilledCharges, int unbilledChargeCount,
                       int unpricedChargeCount, BigDecimal outstandingInvoices) {
    }

    /** Rate per chargeable kg, minimum per movement, and tax percent on transport. */
    record TransportTariff(String currency, BigDecimal ratePerKg, BigDecimal minimumCharge,
                           BigDecimal taxRatePct) {
    }
}
