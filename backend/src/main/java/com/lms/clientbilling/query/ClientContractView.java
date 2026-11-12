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
import java.util.List;
import java.util.UUID;

/** One version of a client's contract, with its value-added service rates. */
public record ClientContractView(
        UUID id,
        UUID orgUnitId,
        String code,
        UUID clientPartnerId,
        String currency,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        /** The last day the contract applies, or null for no end date. */
        LocalDate validTill,
        /** Stored: DRAFT, PENDING_VERIFICATION or ACTIVE. */
        String status,
        /** What to show: the status, or EXPIRED for a version in force past its end date. */
        String state,
        /** Why verification was last refused, while the contract is back in DRAFT. */
        String rejectionReason,
        /** The version an edit sends back in {@code If-Match}. */
        long version,
        BigDecimal transportRatePerKg,
        BigDecimal transportMinimumCharge,
        BigDecimal storageRatePerPalletDay,
        BigDecimal handlingInRatePerPallet,
        BigDecimal handlingOutRatePerPallet,
        BigDecimal transportTaxRatePct,
        BigDecimal warehouseTaxRatePct,
        int storageUnitsPerPallet,
        List<ServiceRate> valueAddedServices,
        /** True when trip transport is invoiceable on dispatch rather than on proof. */
        boolean billOnDispatch) {

    public ClientContractView {
        valueAddedServices = valueAddedServices == null ? List.of() : List.copyOf(valueAddedServices);
    }

    public record ServiceRate(String serviceCode, String unit, BigDecimal rate) {
    }
}
