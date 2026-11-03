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
package com.lms.controltower.command;

import java.util.UUID;
import java.util.function.Supplier;

import com.lms.shared.tenant.TenantContext;
import com.lms.telematics.events.TemperatureReported;
import com.lms.telematics.events.TripEtaProjected;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Feeds telematics' ETA projections and temperature readings to the detectors.
 *
 * <p>Same shape as {@link StoppageListener}: the tenant scope is taken from the
 * event and bound first, and the detector opens its own transaction inside it.
 * Two deliveries of one trip racing meet the watch row's version or the open
 * ticket index; the loser is dropped, which costs at most one reading, and the
 * next ping carries another.
 */
@Component
public class TripDetectorListener {

    private static final Logger log = LoggerFactory.getLogger(TripDetectorListener.class);

    private final TripDetectorService detectors;

    public TripDetectorListener(TripDetectorService detectors) {
        this.detectors = detectors;
    }

    @TransactionalEventListener
    public void onEtaProjected(TripEtaProjected event) {
        run(event.tenantId(), () -> detectors.onEtaProjected(event.tripId(),
                event.projectedArrivalAt(), event.lat(), event.lon(), event.basedOnFixAt()),
                "ETA of trip " + event.tripNo());
    }

    @TransactionalEventListener
    public void onTemperature(TemperatureReported event) {
        run(event.tenantId(), () -> detectors.onTemperature(event.tripId(), event.celsius(),
                event.recordedAt(), event.lat(), event.lon()),
                "Temperature of trip " + event.tripNo());
    }

    private void run(UUID tenantId, Supplier<java.util.Optional<UUID>> detection, String what) {
        try {
            TenantContext.callWith(tenantId, null, detection)
                    .ifPresent(id -> log.debug("{} is ticket {}", what, id));
        } catch (DuplicateKeyException | OptimisticLockingFailureException concurrentDelivery) {
            log.debug("{} was handled by a concurrent delivery", what);
        }
    }
}
