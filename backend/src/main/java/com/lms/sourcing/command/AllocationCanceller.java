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
package com.lms.sourcing.command;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.lms.shared.audit.TransitionAudit;
import com.lms.shared.config.Json;
import com.lms.sourcing.command.domain.Allocation;
import com.lms.sourcing.command.domain.AllocationOffer;
import org.springframework.stereotype.Component;

/**
 * Cancels an allocation: its outstanding offer, its place in a vendor's fair
 * share, and the allocation itself.
 *
 * <p>The one place this is done, because it has two callers that must agree:
 * a person abandoning an allocation ({@link AllocationService#cancel}) and a
 * cancelled load taking its offers down with it ({@link LoadCancellationAdapter}).
 * Does not touch the load; whether the load goes back to PLANNED or is itself
 * cancelled is the caller's to say.
 */
@Component
class AllocationCanceller {

    private final AllocationRepository allocations;
    private final AllocationOfferRepository offers;
    private final VendorAwardTally tally;

    AllocationCanceller(AllocationRepository allocations, AllocationOfferRepository offers,
                        VendorAwardTally tally) {
        this.allocations = allocations;
        this.offers = offers;
        this.tally = tally;
    }

    /**
     * Cancels an OFFERED or AWARDED allocation, and returns the audit entry
     * for it. The caller records entries in a batch.
     *
     * <p>A pending offer is cancelled, an accepted one stays as the history of
     * the acceptance, and an award is taken back off the vendor's monthly tally
     * so that a withdrawn award does not count against their fair share.
     */
    TransitionAudit.Entry cancel(UUID tenantId, Allocation allocation, String reason) {
        Optional<AllocationOffer> pending = offers.findPending(tenantId, allocation.id());
        UUID vendor = allocation.awardedVendorPartnerId() != null
                ? allocation.awardedVendorPartnerId()
                : pending.map(AllocationOffer::vendorPartnerId).orElse(null);
        Json before = AllocationService.snapshot(allocation, vendor, null);

        pending.ifPresent(offer -> offers.save(offer.cancel(Instant.now())));
        if (allocation.status() == Allocation.AllocationStatus.AWARDED
                && allocation.guideId() != null && allocation.awardedAt() != null) {
            tally.retractAward(tenantId, allocation.guideId(), allocation.awardedVendorPartnerId(),
                    allocation.awardedAt());
        }
        Allocation cancelled = allocations.save(allocation.cancel());
        return new TransitionAudit.Entry(cancelled.id(), before,
                AllocationService.cancelledSnapshot(cancelled, vendor, reason));
    }
}
