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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.lms.milkrun.command.CircuitRunCommandService;
import com.lms.milkrun.query.CircuitRunStopView;
import com.lms.milkrun.query.CircuitRunView;
import com.lms.milkrun.query.MilkRunQueryService;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.query.Slice;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Circuit runs: generating them, and recording what happened at each stop.
 *
 * <p>Each stop milestone is its own endpoint, as on the control tower desk:
 * the steps have different required fields and a single status field would
 * turn every rule into a conditional on the body. Telematics records the same
 * milestones from geofences without any of these being called.
 */
@RestController
@RequestMapping("/api/v1/milk-runs")
public class CircuitRunController {

    /** How far ahead on-demand generation plans when the caller does not say. */
    private static final int DEFAULT_DAYS = 7;

    private final CircuitRunCommandService circuits;
    private final MilkRunQueryService queries;

    public CircuitRunController(CircuitRunCommandService circuits, MilkRunQueryService queries) {
        this.circuits = circuits;
        this.queries = queries;
    }

    @GetMapping
    public Slice<CircuitRunView> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate fromDate,
            @RequestParam(required = false) String routeCode,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return queries.listRuns(fromDate, routeCode, status, cursor, limit);
    }

    @GetMapping("/{id}")
    public CircuitRunView get(@PathVariable UUID id) {
        return queries.findRun(id)
                .orElseThrow(() -> new ResourceNotFoundException("Circuit run", id));
    }

    @GetMapping("/{id}/stops")
    public List<CircuitRunStopView> stops(@PathVariable UUID id) {
        return queries.runStops(id);
    }

    @PostMapping("/generate")
    public GenerateResult generate(@RequestBody(required = false) GenerateRequest request) {
        LocalDate from = request == null || request.fromDate() == null
                ? LocalDate.now(ZoneOffset.UTC)
                : request.fromDate();
        int days = request == null || request.days() == null ? DEFAULT_DAYS : request.days();
        return new GenerateResult(circuits.generate(from, days));
    }

    @PostMapping("/{id}/trip")
    public ResponseEntity<Void> assignTrip(@PathVariable UUID id,
                                           @Valid @RequestBody AssignTripRequest request) {
        circuits.assignTrip(id, request.tripId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/stops/{sequence}/arrival")
    public ResponseEntity<Void> arrival(@PathVariable UUID id, @PathVariable int sequence,
                                        @RequestBody(required = false) MilestoneRequest request) {
        circuits.recordArrival(id, sequence, at(request));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/stops/{sequence}/departure")
    public ResponseEntity<Void> departure(@PathVariable UUID id, @PathVariable int sequence,
                                          @RequestBody(required = false) MilestoneRequest request) {
        circuits.recordDeparture(id, sequence, at(request));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/stops/{sequence}/pickup")
    public ResponseEntity<Void> pickup(@PathVariable UUID id, @PathVariable int sequence,
                                       @Valid @RequestBody PickupRequest request) {
        circuits.recordPickup(id, sequence, request.quantityKg());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/stops/{sequence}/skip")
    public ResponseEntity<Void> skip(@PathVariable UUID id, @PathVariable int sequence,
                                     @Valid @RequestBody SkipRequest request) {
        circuits.skip(id, sequence, request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/completion")
    public ResponseEntity<Void> complete(@PathVariable UUID id,
                                         @RequestBody(required = false) MilestoneRequest request) {
        circuits.complete(id, at(request));
        return ResponseEntity.noContent().build();
    }

    private static Instant at(MilestoneRequest request) {
        return request == null || request.at() == null ? Instant.now() : request.at();
    }

    /** @param days how many days to plan, starting at {@code fromDate}; 1 to 31 */
    public record GenerateRequest(LocalDate fromDate, Integer days) {
    }

    public record GenerateResult(int generated) {
    }

    public record AssignTripRequest(@NotNull UUID tripId) {
    }

    /** @param at when it happened; now if omitted */
    public record MilestoneRequest(Instant at) {
    }

    public record PickupRequest(@NotNull @PositiveOrZero BigDecimal quantityKg) {
    }

    public record SkipRequest(@NotBlank String reason) {
    }
}
