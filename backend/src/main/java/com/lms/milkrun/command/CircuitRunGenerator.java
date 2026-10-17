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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.lms.milkrun.command.domain.CircuitRun;
import com.lms.milkrun.command.domain.CircuitRunStop;
import com.lms.milkrun.command.domain.MilkRunRoute;
import com.lms.milkrun.command.domain.MilkRunRouteStop;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plans circuit runs ahead of time from the routes' schedules.
 *
 * <p>Idempotent per route and day, and that is the property everything else
 * leans on. The generator runs at startup, on a fixed delay and on demand, so
 * the same day is offered to it many times; the unique key on
 * {@code (tenant, run_date, route_code)} and an {@code INSERT ... ON CONFLICT
 * DO NOTHING} make every offer after the first a no-op. A check-then-insert
 * would leave that to timing.
 *
 * <p>Keyed on the route <em>code</em>, not the version: publishing a new
 * version does not make a day that already has a run eligible for a second.
 *
 * <p>Unguarded and scope-agnostic on purpose. It works on whatever tenant is
 * bound, inside whatever transaction is open -- the scheduled job supplies
 * both through {@code TenantSweep}, and the guarded command service supplies
 * them for a request.
 */
@Component
public class CircuitRunGenerator {

    /** The furthest ahead anyone may ask for in one go. */
    static final int MAX_DAYS = 31;

    private final MilkRunRouteRepository routes;
    private final MilkRunRouteStopRepository routeStops;
    private final CircuitRunStopRepository runStops;
    private final JdbcClient jdbc;

    public CircuitRunGenerator(MilkRunRouteRepository routes,
                               MilkRunRouteStopRepository routeStops,
                               CircuitRunStopRepository runStops, JdbcClient jdbc) {
        this.routes = routes;
        this.routeStops = routeStops;
        this.runStops = runStops;
        this.jdbc = jdbc;
    }

    /**
     * Plans every scheduled circuit from {@code firstDay} for {@code days}
     * days.
     *
     * @return how many runs were newly planned; zero when they all existed
     */
    @Transactional
    public int generate(UUID tenantId, LocalDate firstDay, int days) {
        if (days < 1 || days > MAX_DAYS) {
            throw new IllegalArgumentException(
                    "Circuits can be planned between 1 and " + MAX_DAYS + " days ahead, not " + days);
        }
        LocalDate lastDay = firstDay.plusDays(days - 1L);

        Map<String, List<MilkRunRoute>> versionsByCode = new LinkedHashMap<>();
        for (MilkRunRoute version : routes.findCovering(tenantId, firstDay, lastDay)) {
            versionsByCode.computeIfAbsent(version.code(), code -> new ArrayList<>()).add(version);
        }

        Map<UUID, List<MilkRunRouteStop>> stopsByVersion = new LinkedHashMap<>();
        int planned = 0;
        for (LocalDate day = firstDay; !day.isAfter(lastDay); day = day.plusDays(1)) {
            for (List<MilkRunRoute> versions : versionsByCode.values()) {
                Optional<MilkRunRoute> inForce = inForceOn(versions, day);
                if (inForce.isEmpty() || !inForce.get().runsOn(day)) {
                    continue;
                }
                MilkRunRoute route = inForce.get();
                List<MilkRunRouteStop> stops = stopsByVersion.computeIfAbsent(route.id(),
                        id -> routeStops.findForRoute(tenantId, id));
                if (plan(tenantId, route, day, stops)) {
                    planned++;
                }
            }
        }
        return planned;
    }

    /** Versions arrive newest first, so the first that covers the day wins. */
    private static Optional<MilkRunRoute> inForceOn(List<MilkRunRoute> versions, LocalDate day) {
        return versions.stream().filter(version -> version.coversOn(day)).findFirst();
    }

    private boolean plan(UUID tenantId, MilkRunRoute route, LocalDate day,
                         List<MilkRunRouteStop> stops) {
        Instant departure = route.plannedDepartureOn(day);
        Instant now = Instant.now();

        Optional<UUID> inserted = jdbc.sql("""
                        INSERT INTO circuit_run (id, tenant_id, route_id, route_code, run_date,
                                                 planned_departure, plant_terminal_id, status,
                                                 run_rate, per_stop_fee, currency, version,
                                                 created_at, updated_at)
                        VALUES (:id, :tenantId, :routeId, :routeCode, :runDate, :departure,
                                :plantTerminalId, :status, :runRate, :perStopFee, :currency, 0,
                                :now, :now)
                        ON CONFLICT (tenant_id, run_date, route_code) DO NOTHING
                        RETURNING id
                        """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("routeId", route.id())
                .param("routeCode", route.code())
                .param("runDate", day)
                .param("departure", departure.atOffset(ZoneOffset.UTC))
                .param("plantTerminalId", route.plantTerminalId())
                .param("status", CircuitRun.Status.PLANNED.name())
                .param("runRate", route.runRate())
                .param("perStopFee", route.perStopFee())
                .param("currency", route.currency())
                .param("now", now.atOffset(ZoneOffset.UTC))
                .query(UUID.class)
                .optional();

        inserted.ifPresent(runId -> stops.forEach(stop ->
                runStops.save(CircuitRunStop.plan(tenantId, runId, stop, departure))));
        return inserted.isPresent();
    }
}
