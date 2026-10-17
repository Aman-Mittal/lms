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
package com.lms.milkrun.command;

import java.time.LocalDate;
import java.time.ZoneOffset;

import com.lms.shared.tenant.TenantSweep;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps every tenant's circuits planned a few days ahead.
 *
 * <p>In-process, because Render's free tier has no workers or cron, and that
 * shapes it in the two ways every job here is shaped:
 *
 * <ul>
 *   <li>It sweeps <em>per tenant</em> through {@link TenantSweep}. A scheduled
 *       thread has no tenant scope, and under row-level security a query with
 *       no scope sees no routes at all -- it would plan nothing and report
 *       success.
 *   <li>It runs shortly after startup as well as on a fixed delay. The
 *       instance sleeps after fifteen idle minutes; a purely wall-clock
 *       schedule would let a night's sleep become a morning with no circuits
 *       planned. Because generation is idempotent per route and day, running
 *       it on every wake costs a few no-op inserts and nothing else.
 * </ul>
 *
 * <p>The window starts yesterday in UTC, so a plant whose local date is behind
 * UTC -- anywhere west of Greenwich -- still has its own today planned when
 * the instance first wakes after UTC midnight.
 */
@Component
public class CircuitScheduleJob {

    private final CircuitRunGenerator generator;
    private final TenantSweep tenantSweep;
    private final int horizonDays;

    public CircuitScheduleJob(CircuitRunGenerator generator, TenantSweep tenantSweep,
                              @Value("${lms.milkrun.horizon-days:7}") int horizonDays) {
        this.generator = generator;
        this.tenantSweep = tenantSweep;
        this.horizonDays = Math.clamp(horizonDays, 1, CircuitRunGenerator.MAX_DAYS - 1);
    }

    @Scheduled(initialDelay = 30_000, fixedDelay = 60 * 60 * 1000)
    public void planAhead() {
        LocalDate firstDay = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        tenantSweep.forEachTenant("milk-run-generation", tenantId ->
                generator.generate(tenantId, firstDay, horizonDays + 1));
    }
}
