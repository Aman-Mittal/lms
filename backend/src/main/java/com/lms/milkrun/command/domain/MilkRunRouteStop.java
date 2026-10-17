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
package com.lms.milkrun.command.domain;

import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One supplier pickup in a route version, in circuit order.
 *
 * <p>The window is in minutes after the circuit's departure rather than in
 * clock time, so one route serves every day it runs and the windows move with
 * the departure if the departure is ever changed.
 */
@Table("milk_run_route_stop")
public record MilkRunRouteStop(
        @Id UUID id,
        UUID tenantId,
        UUID routeId,
        int sequence,
        UUID terminalId,
        int windowOpenMin,
        int windowCloseMin,
        int plannedDwellMin,
        @Version Long version) {

    public static MilkRunRouteStop of(UUID tenantId, UUID routeId, int sequence, UUID terminalId,
                                      int windowOpenMin, int windowCloseMin, int plannedDwellMin) {
        if (windowOpenMin < 0 || windowCloseMin < windowOpenMin) {
            throw new BusinessRuleViolationException("milk-run-window-invalid",
                    "Stop " + sequence + " has a window from " + windowOpenMin + " to "
                            + windowCloseMin + " minutes after departure; it must open no "
                            + "earlier than the departure and close no earlier than it opens");
        }
        if (plannedDwellMin < 0) {
            throw new BusinessRuleViolationException("milk-run-window-invalid",
                    "Stop " + sequence + " cannot plan a negative dwell");
        }
        return new MilkRunRouteStop(UUID.randomUUID(), tenantId, routeId, sequence, terminalId,
                windowOpenMin, windowCloseMin, plannedDwellMin, null);
    }
}
