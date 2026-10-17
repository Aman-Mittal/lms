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
package com.lms.milkrun.web;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import com.lms.milkrun.command.MilkRunRouteCommandService;
import com.lms.milkrun.query.MilkRunQueryService;
import com.lms.milkrun.query.MilkRunRouteStopView;
import com.lms.milkrun.query.MilkRunRouteView;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.query.Slice;
import com.lms.shared.web.IdResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Milk-run routes: the versioned plan each circuit is generated from. */
@RestController
@RequestMapping("/api/v1/milk-run-routes")
public class MilkRunRouteController {

    private final MilkRunRouteCommandService routes;
    private final MilkRunQueryService queries;

    public MilkRunRouteController(MilkRunRouteCommandService routes, MilkRunQueryService queries) {
        this.routes = routes;
        this.queries = queries;
    }

    @GetMapping
    public Slice<MilkRunRouteView> list(@RequestParam(required = false) String cursor,
                                        @RequestParam(required = false) Integer limit) {
        return queries.listRoutes(cursor, limit);
    }

    @GetMapping("/{id}")
    public MilkRunRouteView get(@PathVariable UUID id) {
        return queries.findRoute(id)
                .orElseThrow(() -> new ResourceNotFoundException("Milk-run route", id));
    }

    @GetMapping("/{id}/stops")
    public List<MilkRunRouteStopView> stops(@PathVariable UUID id) {
        return queries.routeStops(id);
    }

    @PostMapping
    public ResponseEntity<IdResponse> publish(@Valid @RequestBody PublishRouteRequest request) {
        UUID id = routes.publish(request.code(), request.name(), request.plantTerminalId(),
                request.effectiveFrom(), request.days(), request.departureTime(),
                request.timeZone(), request.runRate(), request.perStopFee(), request.currency(),
                request.stops().stream()
                        .map(stop -> new MilkRunRouteCommandService.StopPlan(stop.terminalId(),
                                stop.windowOpenMin(), stop.windowCloseMin(),
                                stop.plannedDwellMin()))
                        .toList());
        return ResponseEntity.created(URI.create("/api/v1/milk-run-routes/" + id))
                .body(new IdResponse(id));
    }

    public record PublishRouteRequest(@NotBlank String code,
                                      String name,
                                      @NotNull UUID plantTerminalId,
                                      @NotNull LocalDate effectiveFrom,
                                      @NotEmpty List<String> days,
                                      @NotNull LocalTime departureTime,
                                      String timeZone,
                                      @NotNull @PositiveOrZero BigDecimal runRate,
                                      @NotNull @PositiveOrZero BigDecimal perStopFee,
                                      String currency,
                                      @NotEmpty List<@Valid StopRequest> stops) {
    }

    public record StopRequest(@NotNull UUID terminalId,
                              @NotNull @PositiveOrZero Integer windowOpenMin,
                              @NotNull @PositiveOrZero Integer windowCloseMin,
                              @NotNull @PositiveOrZero Integer plannedDwellMin) {
    }
}
