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
import java.util.List;
import java.util.UUID;

import com.lms.shared.tenant.TenantSweep;
import com.lms.sourcing.command.domain.AllocationOffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Moves the cascade on when a vendor simply does not answer (vision document
 * 3.5.1).
 *
 * <p>A response SLA that nothing enforces is a suggestion. Without this, an
 * unanswered offer holds a load indefinitely and the freight sits while the
 * allocation looks healthy.
 *
 * <p>Runs in-process because Render's free tier has no workers or cron. Two
 * consequences follow and both are deliberate:
 *
 * <ul>
 *   <li>It sweeps <em>per tenant</em> via {@link TenantSweep}. A scheduled
 *       thread carries no tenant scope, and under row-level security a query
 *       with no scope returns nothing at all -- so the naive version of this
 *       class would have found no lapsed offers, ever, and reported success.
 *   <li>It runs shortly after startup as well as on a fixed delay. The instance
 *       sleeps after fifteen idle minutes, so a purely wall-clock schedule
 *       would miss every deadline that fell while it was asleep.
 * </ul>
 */
@Component
public class AllocationTimeoutSweeper {

    private static final Logger log = LoggerFactory.getLogger(AllocationTimeoutSweeper.class);

    private final AllocationOfferRepository offers;
    private final AllocationService allocations;
    private final TenantSweep tenantSweep;

    public AllocationTimeoutSweeper(AllocationOfferRepository offers,
                                    AllocationService allocations,
                                    TenantSweep tenantSweep) {
        this.offers = offers;
        this.allocations = allocations;
        this.tenantSweep = tenantSweep;
    }

    /**
     * A minute is fine granularity for an SLA measured in tens of minutes, and
     * on 0.1 of a CPU the query is a partial-index range scan over a table that
     * is almost entirely settled offers.
     */
    @Scheduled(initialDelay = 20_000, fixedDelay = 60_000)
    public void sweepLapsedOffers() {
        tenantSweep.forEachTenant("allocation-timeout", tenantId -> {
            Instant now = Instant.now();
            List<AllocationOffer> lapsed = offers.findLapsed(tenantId, now);

            for (AllocationOffer offer : lapsed) {
                UUID offerId = offer.id();
                try {
                    // Each timeout is settled through the service, in its own
                    // transaction, so one allocation whose cascade fails -- a
                    // vendor deleted, a guide pulled -- does not abandon the
                    // rest of this tenant's lapsed offers.
                    allocations.timeOut(offerId);
                } catch (RuntimeException e) {
                    log.error("Could not time out allocation offer {}", offerId, e);
                }
            }
            return lapsed.size();
        });
    }
}
