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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.lms.masterdata.api.TerminalGeofencePort;
import com.lms.milkrun.command.domain.MilkRunRoute;
import com.lms.milkrun.command.domain.MilkRunRouteStop;
import com.lms.shared.audit.AuditTrail;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishing milk-run routes.
 *
 * <p>Guarded by {@code MILKRUN_PLAN}. A route carries the circuit's price, so
 * whoever records stops on the road ({@code MILKRUN_EXECUTE}) should not also
 * be able to change what the circuit pays.
 */
@Service
public class MilkRunRouteCommandService {

    /**
     * The longest circuit accepted.
     *
     * <p>Well beyond any real milk run -- they are short by nature, because a
     * vehicle has to fill up and get back to the plant inside a shift -- and
     * low enough that one route can never become an unbounded write.
     */
    static final int MAX_STOPS = 30;

    private final MilkRunRouteRepository routes;
    private final MilkRunRouteStopRepository stops;
    private final TerminalGeofencePort terminals;
    private final AuditTrail audit;

    public MilkRunRouteCommandService(MilkRunRouteRepository routes,
                                      MilkRunRouteStopRepository stops,
                                      TerminalGeofencePort terminals, AuditTrail audit) {
        this.routes = routes;
        this.stops = stops;
        this.terminals = terminals;
        this.audit = audit;
    }

    /** One supplier pickup as planned: where, the window, and how long to stay. */
    public record StopPlan(UUID terminalId, int windowOpenMin, int windowCloseMin,
                           int plannedDwellMin) {
    }

    /**
     * Publishes a version of a route, closing the incumbent the day before.
     *
     * <p>Both writes in one transaction, under the partial unique index that
     * permits one open version per code, so a crash between them cannot leave
     * two plans in force.
     *
     * <p>Runs already generated for days on or after {@code effectiveFrom}
     * keep the plan they were generated with. Regenerating them is deliberately
     * not automatic: a run may already be on the road.
     */
    @Transactional
    @PreAuthorize("hasAuthority('MILKRUN_PLAN')"
            + " and @abac.permits('MILKRUN_PLAN', 'TERMINAL', #plantTerminalId)")
    public UUID publish(String code, String name, UUID plantTerminalId, LocalDate effectiveFrom,
                        List<String> days, LocalTime departureTime, String timeZone,
                        BigDecimal runRate, BigDecimal perStopFee, String currency,
                        List<StopPlan> plannedStops) {
        UUID tenantId = TenantContext.requireTenantId();

        if (plannedStops == null || plannedStops.isEmpty()) {
            throw new BusinessRuleViolationException("milk-run-no-stops",
                    "A milk run needs at least one supplier to collect from");
        }
        if (plannedStops.size() > MAX_STOPS) {
            throw new BusinessRuleViolationException("milk-run-too-many-stops",
                    "A milk run may call at no more than " + MAX_STOPS + " suppliers");
        }

        // Row-level security does not cover a foreign key: the constraint is
        // checked by the system and would accept another tenant's terminal.
        // Asking master data, which reads under the caller's scope, does.
        requireTerminal(plantTerminalId, "plant");
        Set<UUID> seen = new HashSet<>();
        int previousOpen = -1;
        for (StopPlan stop : plannedStops) {
            requireTerminal(stop.terminalId(), "supplier");
            if (stop.terminalId().equals(plantTerminalId)) {
                throw new BusinessRuleViolationException("milk-run-plant-as-stop",
                        "The plant is where the circuit ends, not one of its pickups");
            }
            if (!seen.add(stop.terminalId())) {
                // A geofence crossing names a terminal, not a stop. Calling at
                // the same supplier twice would make "which visit was that"
                // unanswerable from a crossing.
                throw new BusinessRuleViolationException("milk-run-supplier-repeated",
                        "A circuit calls at each supplier once");
            }
            if (stop.windowOpenMin() < previousOpen) {
                throw new BusinessRuleViolationException("milk-run-windows-out-of-order",
                        "Pickup windows must open in circuit order");
            }
            previousOpen = stop.windowOpenMin();
        }

        MilkRunRoute candidate = MilkRunRoute.publish(UUID.randomUUID(), tenantId, code, name,
                plantTerminalId, effectiveFrom, days, departureTime, timeZone, runRate,
                perStopFee, currency);

        routes.findOpenVersion(tenantId, candidate.code()).ifPresent(incumbent -> {
            if (!effectiveFrom.isAfter(incumbent.effectiveFrom())) {
                throw new BusinessRuleViolationException("milk-run-route-backdated",
                        "Route " + incumbent.code() + " has been in force since "
                                + incumbent.effectiveFrom() + "; a successor cannot start on "
                                + effectiveFrom);
            }
            routes.save(incumbent.closeOn(effectiveFrom.minusDays(1)));
        });

        MilkRunRoute published = routes.save(candidate);
        int sequence = 1;
        for (StopPlan stop : plannedStops) {
            stops.save(MilkRunRouteStop.of(tenantId, published.id(), sequence++,
                    stop.terminalId(), stop.windowOpenMin(), stop.windowCloseMin(),
                    stop.plannedDwellMin()));
        }

        audit.record("MILK_RUN_ROUTE_PUBLISHED", "MILK_RUN_ROUTE", published.id(), null,
                Json.object("code", published.code(),
                        "effectiveFrom", effectiveFrom.toString(),
                        "days", published.daysOfWeek(),
                        "stops", String.valueOf(plannedStops.size())));
        return published.id();
    }

    private void requireTerminal(UUID terminalId, String role) {
        if (terminalId == null || terminals.centreOf(terminalId).isEmpty()) {
            throw new BusinessRuleViolationException("milk-run-unknown-terminal",
                    "The " + role + " terminal " + terminalId + " is not on this tenant's network");
        }
    }
}
