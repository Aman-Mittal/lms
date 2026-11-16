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
package com.lms.statutory.command;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.shared.audit.AuditTrail;
import com.lms.shared.config.Json;
import com.lms.shared.tenant.TenantSweep;
import com.lms.statutory.events.EwayBillExpired;
import com.lms.statutory.events.EwayBillExpiringSoon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the bills honest about time: a live bill past its validity becomes
 * EXPIRED, and one that comes within its unit's threshold of lapsing
 * ({@code statutory.ewayBill.expiringSoonHours}, default 8) is flagged once as
 * expiring soon, which the expiring list shows and an {@link EwayBillExpiringSoon}
 * event announces. An extension clears the flag.
 *
 * <p>In-process and per tenant through {@link TenantSweep}, shortly after
 * start-up as well as every few minutes, because the free instance sleeps.
 * Idempotent: an expired bill is no longer selected, a flagged one is not
 * flagged again. A batch is bounded; the rest wait for the next pass.
 */
@Component
public class EwayBillSweep {

    /** The largest threshold a unit may set, in hours; bills further out than this are never candidates. */
    private static final int WIDEST_THRESHOLD_HOURS = 168;
    private static final int BATCH = 200;
    private static final Logger log = LoggerFactory.getLogger(EwayBillSweep.class);

    private final TenantSweep tenantSweep;
    private final EwayBillStore store;
    private final EwayBillSettings settings;
    private final AuditTrail audit;
    private final ApplicationEventPublisher events;

    public EwayBillSweep(TenantSweep tenantSweep, EwayBillStore store, EwayBillSettings settings, AuditTrail audit,
                         ApplicationEventPublisher events) {
        this.tenantSweep = tenantSweep;
        this.store = store;
        this.settings = settings;
        this.audit = audit;
        this.events = events;
    }

    @Scheduled(initialDelay = 35_000, fixedDelay = 300_000)
    public void scheduled() {
        sweep(Instant.now());
    }

    /** @return how many bills were expired or flagged, across every tenant */
    public int sweep(Instant now) {
        return tenantSweep.forEachTenant("eway-bill-expiry", tenantId -> expire(tenantId, now) + flag(tenantId, now));
    }

    private int expire(UUID tenantId, Instant now) {
        List<EwayBillStore.Bill> lapsed = store.lapsed(now, BATCH);
        for (EwayBillStore.Bill bill : lapsed) {
            store.expire(bill, now);
            audit.recordAtomically("EWAY_BILL_EXPIRED", EwayBillService.BILL_TYPE, bill.id(),
                    Json.object("billNo", bill.billNo(), "status", bill.status().name(),
                            "validUntil", bill.validUntil().toString()),
                    Json.object("billNo", bill.billNo(), "status", EwayBillStatus.EXPIRED.name(),
                            "validUntil", bill.validUntil().toString()));
            events.publishEvent(new EwayBillExpired(tenantId, bill.id(), bill.consignmentId(), bill.billNo(),
                    bill.validUntil(), now));
        }
        if (!lapsed.isEmpty()) {
            log.info("E-way bill sweep expired {} bills", lapsed.size());
        }
        return lapsed.size();
    }

    private int flag(UUID tenantId, Instant now) {
        List<EwayBillStore.Bill> candidates = store.unflaggedBefore(now,
                now.plus(Duration.ofHours(WIDEST_THRESHOLD_HOURS)), BATCH);
        Map<UUID, Integer> thresholds = new HashMap<>();
        int flagged = 0;
        for (EwayBillStore.Bill bill : candidates) {
            int threshold = thresholds.computeIfAbsent(bill.orgUnitId(),
                    unit -> settings.forUnit(unit).expiringSoonHours());
            if (!EwayBillRules.expiringSoon(bill.validUntil(), now, threshold)) {
                continue;
            }
            store.flagExpiring(bill, now);
            audit.recordAtomically("EWAY_BILL_EXPIRING_SOON", EwayBillService.BILL_TYPE, bill.id(), null,
                    Json.object("billNo", bill.billNo(), "validUntil", bill.validUntil().toString(),
                            "thresholdHours", String.valueOf(threshold)));
            events.publishEvent(new EwayBillExpiringSoon(tenantId, bill.id(), bill.consignmentId(), bill.billNo(),
                    bill.validUntil(), now));
            flagged++;
        }
        if (flagged > 0) {
            log.info("E-way bill sweep flagged {} bills as expiring soon", flagged);
        }
        return flagged;
    }
}
