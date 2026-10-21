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
package com.lms.multimodal.command;

import java.time.Instant;

import com.lms.shared.tenant.TenantSweep;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Notices legs whose predecessor never arrives.
 *
 * <p>The arrival-time detector in {@link ItineraryCommandService} reacts to an
 * arrival, so a lorry that breaks down on the way to the railhead -- and never
 * reports anything again -- would leave the rake booked behind it looking
 * healthy until somebody asked. This is the other half: a periodic look for
 * legs still waiting on a predecessor that is overdue and can no longer make
 * the connection.
 *
 * <p>In-process, because Render's free tier has no workers or cron, and shaped
 * the way every job here is:
 *
 * <ul>
 *   <li>It sweeps <em>per tenant</em> through {@link TenantSweep}. A scheduled
 *       thread has no tenant scope, and under row-level security an unscoped
 *       query sees no legs at all and would report success.
 *   <li>It runs shortly after startup as well as on a fixed delay. The instance
 *       sleeps after fifteen idle minutes; a miss that fell due while it slept
 *       is caught on waking. Marking is once per leg, so running it again
 *       costs an index range scan and nothing else.
 * </ul>
 *
 * <p>{@code lms.multimodal.connection-sweep.enabled} switches off the
 * <em>timer</em>, not the sweep: the acceptance suite does, because its
 * scenarios are timelines set in the past and a background pass would race
 * their steps. Read at runtime rather than as a condition, so a native image
 * built with it either way still honours the setting it starts with.
 */
@Component
public class MissedConnectionSweep {

    private final ItineraryCommandService itineraries;
    private final TenantSweep tenantSweep;
    private final boolean enabled;

    public MissedConnectionSweep(ItineraryCommandService itineraries, TenantSweep tenantSweep,
                                 @Value("${lms.multimodal.connection-sweep.enabled:true}")
                                 boolean enabled) {
        this.itineraries = itineraries;
        this.tenantSweep = tenantSweep;
        this.enabled = enabled;
    }

    /**
     * Five minutes: a connection is planned in hours, and on 0.1 of a CPU the
     * query is a range scan over a partial index of legs not yet started.
     */
    @Scheduled(initialDelay = 40_000, fixedDelay = 5 * 60 * 1000)
    public void onSchedule() {
        if (enabled) {
            sweep();
        }
    }

    /** One pass over every tenant. @return how many legs were marked */
    public int sweep() {
        return tenantSweep.forEachTenant("missed-connection", tenantId ->
                itineraries.sweepMissedConnections(tenantId, Instant.now()));
    }
}
