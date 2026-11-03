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
package com.lms.controltower.web;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lms.controltower.command.DetectorSettingsService;
import com.lms.controltower.command.ExceptionCommandService;
import com.lms.controltower.command.TripWatchCommandService;
import com.lms.controltower.command.domain.ExceptionCategory;
import com.lms.controltower.query.ControlTowerQueryService;
import com.lms.controltower.query.ExceptionActivityView;
import com.lms.controltower.query.ExceptionSummaryView;
import com.lms.controltower.query.ExceptionView;
import com.lms.controltower.query.TripWatchView;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.query.Slice;
import com.lms.shared.web.IdResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The control tower desk (vision document 3.8).
 *
 * <p>Each lifecycle step is its own endpoint rather than a PATCH of the status.
 * The steps carry different permissions and different required fields -- a
 * resolution must say how, a reopen must say why -- and a single status field
 * would turn every one of those rules into a conditional on the request body.
 */
@RestController
@RequestMapping("/api/v1/exceptions")
public class ExceptionController {

    private final ExceptionCommandService exceptions;
    private final ControlTowerQueryService queries;
    private final TripWatchCommandService watches;
    private final DetectorSettingsService detectorSettings;

    public ExceptionController(ExceptionCommandService exceptions,
                               ControlTowerQueryService queries,
                               TripWatchCommandService watches,
                               DetectorSettingsService detectorSettings) {
        this.exceptions = exceptions;
        this.queries = queries;
        this.watches = watches;
        this.detectorSettings = detectorSettings;
    }

    @GetMapping
    public Slice<ExceptionView> list(@RequestParam(required = false) String status,
                                     @RequestParam(required = false) String severity,
                                     @RequestParam(required = false) String cursor,
                                     @RequestParam(required = false) Integer limit) {
        return queries.list(status, severity, cursor, limit);
    }

    @GetMapping("/summary")
    public ExceptionSummaryView summary() {
        return queries.summary();
    }

    /** What the ETA and temperature detectors tolerate, for this tenant. */
    @GetMapping("/detector-settings")
    public DetectorSettingsService.Settings detectorSettings() {
        return detectorSettings.view();
    }

    @PutMapping("/detector-settings")
    public ResponseEntity<Void> setDetectorSettings(
            @Valid @RequestBody DetectorSettingsRequest request) {
        detectorSettings.update(request.etaSlipMinutes(), request.temperatureExcursionMinutes());
        return ResponseEntity.noContent().build();
    }

    /** The terms a trip is watched against: its committed arrival and temperature band. */
    @GetMapping("/trip-watches/{tripId}")
    public TripWatchView tripWatch(@PathVariable UUID tripId) {
        return queries.watchOf(tripId)
                .orElseThrow(() -> new ResourceNotFoundException("Trip watch", tripId));
    }

    @PutMapping("/trip-watches/{tripId}")
    public ResponseEntity<Void> setTripWatch(@PathVariable UUID tripId,
                                             @Valid @RequestBody TripWatchRequest request) {
        watches.set(tripId, request.committedArrivalAt(), request.tempMinC(), request.tempMaxC());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/trip-watches/{tripId}")
    public ResponseEntity<Void> clearTripWatch(@PathVariable UUID tripId) {
        watches.clear(tripId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    public ExceptionView get(@PathVariable UUID id) {
        return queries.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Exception", id));
    }

    @GetMapping("/{id}/history")
    public List<ExceptionActivityView> history(@PathVariable UUID id) {
        return queries.historyOf(id);
    }

    @PostMapping
    public ResponseEntity<IdResponse> raise(@Valid @RequestBody RaiseRequest request) {
        UUID id = exceptions.raise(request.tripId(), request.category(), request.description(),
                request.lat(), request.lon());
        return ResponseEntity.created(URI.create("/api/v1/exceptions/" + id))
                .body(new IdResponse(id));
    }

    @PostMapping("/{id}/assign")
    public ResponseEntity<Void> assign(@PathVariable UUID id,
                                       @Valid @RequestBody AssignRequest request) {
        exceptions.assign(id, request.assigneeUserId(), request.note());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/investigate")
    public ResponseEntity<Void> investigate(@PathVariable UUID id,
                                            @RequestBody(required = false) NoteRequest request) {
        exceptions.startInvestigation(id, request == null ? null : request.note());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/resolve")
    public ResponseEntity<Void> resolve(@PathVariable UUID id,
                                        @Valid @RequestBody ResolveRequest request) {
        exceptions.resolve(id, request.resolution());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reopen")
    public ResponseEntity<Void> reopen(@PathVariable UUID id,
                                       @Valid @RequestBody ReopenRequest request) {
        exceptions.reopen(id, request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/close")
    public ResponseEntity<Void> close(@PathVariable UUID id,
                                      @RequestBody(required = false) NoteRequest request) {
        exceptions.close(id, request == null ? null : request.note());
        return ResponseEntity.noContent().build();
    }

    /**
     * @param lat optional; where the reporter says it happened. Used when the
     *            vehicle has no recent fix of its own.
     */
    public record RaiseRequest(@NotNull UUID tripId,
                               @NotNull ExceptionCategory category,
                               @NotBlank String description,
                               BigDecimal lat,
                               BigDecimal lon) {
    }

    public record AssignRequest(@NotNull UUID assigneeUserId, String note) {
    }

    public record NoteRequest(String note) {
    }

    public record ResolveRequest(@NotBlank String resolution) {
    }

    public record ReopenRequest(@NotBlank String reason) {
    }

    public record DetectorSettingsRequest(@Positive int etaSlipMinutes,
                                          @Positive int temperatureExcursionMinutes) {
    }

    /**
     * @param committedArrivalAt optional; when the trip promised to arrive
     * @param tempMinC           optional, with {@code tempMaxC}; the cargo's band
     */
    public record TripWatchRequest(Instant committedArrivalAt,
                                   BigDecimal tempMinC,
                                   BigDecimal tempMaxC) {
    }
}
