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
import com.lms.telematics.events.RouteDeviationDetected;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Raises a ticket when telematics sees a vehicle leave its corridor.
 *
 * <p>Shaped like {@code TripBillingListener}, for the same reasons: a plain
 * after-commit listener rather than {@code @ApplicationModuleListener}, so the
 * tenant scope is bound <em>before</em> the service opens its transaction; and
 * the scope is taken from the event, because on the outbox's republication
 * path after a restart nothing else has bound one.
 */
@Component
public class RouteDeviationListener {

    private static final Logger log = LoggerFactory.getLogger(RouteDeviationListener.class);

    private final ExceptionCommandService exceptions;

    public RouteDeviationListener(ExceptionCommandService exceptions) {
        this.exceptions = exceptions;
    }

    @TransactionalEventListener
    public void onRouteDeviation(RouteDeviationDetected event) {
        TenantContext.runWith(event.tenantId(), null, () ->
                exceptions.raiseForRouteDeviation(event.tripId(), event.distanceM(),
                                event.corridorM(), event.lat(), event.lon(), event.detectedAt())
                        .ifPresent(id -> log.debug("Route deviation on trip {} is ticket {}",
                                event.tripNo(), id)));
    }
}
