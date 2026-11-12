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

package com.lms.finance.command;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.lms.execution.api.TripLookupPort;
import com.lms.finance.command.domain.CostSheet;
import com.lms.finance.command.domain.FreightBill;
import com.lms.finance.command.domain.FuelSurchargeIndex;
import com.lms.finance.command.domain.Tariff;
import com.lms.planning.api.LoadLifecyclePort;
import com.lms.shared.tenant.TenantContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The freight an advance is capped against.
 *
 * <p>The trip's own freight bill when it has one (what the platform says the
 * movement costs, manual charges included); before the trip is billed, the
 * platform's own price of the load on the vendor's rate card in force today,
 * with no detention (the dwell is not known yet). Neither is the vendor's
 * claim and neither comes from the request: a caller cannot raise the cap by
 * stating a bigger freight. With neither -- no bill and no rate card for the
 * lane -- there is nothing to measure an advance against and none is allowed.
 */
@Component
class AdvanceFreight {

    /** The freight and where it came from: {@code BILL} or {@code ESTIMATE}. */
    record Reference(BigDecimal amount, String source) {
    }

    private final FreightBillRepository bills;
    private final TariffRepository tariffs;
    private final TariffSlabRepository slabs;
    private final FuelSurchargeIndexRepository fuelIndex;
    private final LoadLifecyclePort loads;
    private final RatingEngine rating;

    AdvanceFreight(FreightBillRepository bills, TariffRepository tariffs, TariffSlabRepository slabs,
                   FuelSurchargeIndexRepository fuelIndex, LoadLifecyclePort loads, RatingEngine rating) {
        this.bills = bills;
        this.tariffs = tariffs;
        this.slabs = slabs;
        this.fuelIndex = fuelIndex;
        this.loads = loads;
        this.rating = rating;
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    Optional<Reference> of(TripLookupPort.TripSnapshot trip) {
        UUID tenantId = TenantContext.requireTenantId();
        Optional<FreightBill> bill = bills.findByTrip(tenantId, trip.tripId())
                .filter(b -> b.status() != FreightBill.BillStatus.CANCELLED
                        && b.status() != FreightBill.BillStatus.UNPRICED
                        && b.computedAmount() != null && b.computedAmount().signum() > 0);
        if (bill.isPresent()) {
            return Optional.of(new Reference(bill.get().computedAmount(), "BILL"));
        }
        if (trip.vendorPartnerId() == null || trip.loadId() == null) {
            return Optional.empty();
        }
        Optional<LoadLifecyclePort.LoadSummary> load = loads.summarise(trip.loadId());
        if (load.isEmpty()) {
            return Optional.empty();
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Optional<Tariff> card = tariffs.findInForceOn(tenantId, trip.vendorPartnerId(),
                load.get().originTerminalId(), load.get().destinationTerminalId(), load.get().vehicleType(), today);
        if (card.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal fuelPct = fuelIndex.findForMonth(tenantId, today.withDayOfMonth(1))
                .map(FuelSurchargeIndex::surchargePct).orElse(null);
        CostSheet sheet = rating.rate(card.get(), slabs.findForTariff(tenantId, card.get().id()),
                load.get().chargeableWeightKg(), Duration.ZERO, load.get().dropCount(), fuelPct);
        return sheet.total().signum() > 0 ? Optional.of(new Reference(sheet.total(), "ESTIMATE")) : Optional.empty();
    }
}
