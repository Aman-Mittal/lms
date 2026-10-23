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

import com.lms.shared.tenant.TenantContext;
import com.lms.telematics.events.StoppageDetected;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Raises the stoppage ticket for a trip that has sat still outside a terminal.
 *
 * <p>Same shape as {@link RouteDeviationListener}: the scope is bound first and
 * the service opens its own transaction inside it. Safe to run twice, because
 * the service is idempotent on the stop.
 */
@Component
public class StoppageListener {

    private static final Logger log = LoggerFactory.getLogger(StoppageListener.class);

    private final ExceptionCommandService exceptions;

    public StoppageListener(ExceptionCommandService exceptions) {
        this.exceptions = exceptions;
    }

    @TransactionalEventListener
    public void onStoppage(StoppageDetected event) {
        TenantContext.runWith(event.tenantId(), null, () ->
                exceptions.raiseForStoppage(event.tripId(), event.stationarySince(),
                                event.stoppedMinutes(), event.thresholdMinutes(), event.lat(),
                                event.lon(), event.detectedAt())
                        .ifPresent(id -> log.debug("Stoppage on trip {} is ticket {}",
                                event.tripNo(), id)));
    }
}
