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
 */package com.lms.execution.web;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lms.execution.command.TripCommandService;
import com.lms.execution.command.domain.WeighbridgeReading;
import com.lms.execution.query.GateEventView;
import com.lms.execution.query.TripQueryService;
import com.lms.execution.query.TripView;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.query.Slice;
import com.lms.shared.web.IdResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The trip state machine over HTTP (vision document 3.6).
 *
 * <p>One endpoint per milestone rather than a status field. The machine has
 * nine states and an explicit transition table, and every move has its own
 * preconditions -- a gate-in records a terminal and a time, a dispatch runs
 * four compliance checks. Exposing a settable status would flatten all of that
 * into "the client asked for DISPATCHED", which is precisely the request the
 * platform exists to be able to refuse.
 *
 * <p>Times are optional on every milestone and default to now. A gate clerk
 * recording an event as it happens should not have to send a clock reading;
 * a back-office correction entered an hour later must be able to.
 */
@RestController
@RequestMapping("/api/v1/trips")
public class TripController {

    private final TripCommandService trips;
    private final TripQueryService tripQueries;

    public TripController(TripCommandService trips, TripQueryService tripQueries) {
        this.trips = trips;
        this.tripQueries = tripQueries;
    }

    @GetMapping
    public Slice<TripView> list(@RequestParam(required = false) String status,
                                @RequestParam(required = false) String cursor,
                                @RequestParam(required = false) Integer limit) {
        return tripQueries.list(status, cursor, limit);
    }

    @GetMapping("/{id}")
    public TripView get(@PathVariable UUID id) {
        return tripQueries.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Trip", id));
    }

    @GetMapping("/{id}/gate-log")
    public List<GateEventView> gateLog(@PathVariable UUID id) {
        return tripQueries.findGateLog(id);
    }

    @PostMapping
    public ResponseEntity<IdResponse> raise(@Valid @RequestBody RaiseTripRequest request) {
        UUID id = trips.raiseTrip(request.tripNo(), request.loadId(), request.plannedStartAt());
        return ResponseEntity.created(URI.create("/api/v1/trips/" + id))
                .body(new IdResponse(id));
    }

    @PostMapping("/{id}/assignment")
    public ResponseEntity<Void> assign(@PathVariable UUID id,
                                       @Valid @RequestBody AssignRequest request) {
        trips.assign(id, request.vehicleId(), request.driverId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/documents")
    public ResponseEntity<IdResponse> attachDocument(
            @PathVariable UUID id, @Valid @RequestBody AttachTripDocumentRequest request) {
        UUID documentId = trips.attachDocument(id, request.documentType(), request.documentRef());
        return ResponseEntity.created(URI.create("/api/v1/trips/" + id + "/documents"))
                .body(new IdResponse(documentId));
    }

    @PostMapping("/{id}/gate-in")
    public ResponseEntity<Void> gateIn(@PathVariable UUID id,
                                       @Valid @RequestBody GateInRequest request) {
        trips.gateIn(id, request.terminalId(), request.at(), request.remarks());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/weighings")
    public ResponseEntity<Void> weigh(@PathVariable UUID id,
                                      @Valid @RequestBody WeighingRequest request) {
        trips.recordWeighing(id, request.terminalId(), request.type(),
                request.weightKg(), request.at());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/loaded")
    public ResponseEntity<Void> markLoaded(@PathVariable UUID id,
                                           @RequestBody(required = false) AtRequest request) {
        trips.markLoaded(id, at(request));
        return ResponseEntity.noContent().build();
    }

    /** The compliance gate. Refuses with 409 and every blocker at once. */
    @PostMapping("/{id}/dispatch")
    public ResponseEntity<Void> dispatch(@PathVariable UUID id,
                                         @RequestBody(required = false) AtRequest request) {
        trips.dispatch(id, at(request));
        return ResponseEntity.noContent().build();
    }

    /**
     * Moving.
     *
     * <p>Normally driven by a geofence exit rather than by this endpoint, which
     * exists for the case telematics cannot cover: a vehicle with no working
     * tracker still has to be dispatchable.
     */
    @PostMapping("/{id}/in-transit")
    public ResponseEntity<Void> beginTransit(@PathVariable UUID id) {
        trips.beginTransit(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/arrival")
    public ResponseEntity<Void> arrive(@PathVariable UUID id,
                                       @RequestBody(required = false) AtRequest request) {
        trips.arrive(id, at(request));
        return ResponseEntity.noContent().build();
    }

    /** Proof of delivery accepted. Raises the freight bill. */
    @PostMapping("/{id}/completion")
    public ResponseEntity<Void> complete(@PathVariable UUID id,
                                         @RequestBody(required = false) AtRequest request) {
        trips.complete(id, at(request));
        return ResponseEntity.noContent().build();
    }

    private static Instant at(AtRequest request) {
        return request == null ? null : request.at();
    }

    /** A milestone whose only detail is when it happened, and even that is optional. */
    public record AtRequest(Instant at) {
    }

    public record RaiseTripRequest(
            @NotBlank String tripNo,
            @NotNull UUID loadId,
            Instant plannedStartAt) {
    }

    public record AssignRequest(@NotNull UUID vehicleId, UUID driverId) {
    }

    public record AttachTripDocumentRequest(
            @NotBlank String documentType,
            @NotBlank String documentRef) {
    }

    public record GateInRequest(@NotNull UUID terminalId, Instant at, String remarks) {
    }

    public record WeighingRequest(
            @NotNull UUID terminalId,
            @NotNull WeighbridgeReading.ReadingType type,
            @NotNull @Positive BigDecimal weightKg,
            Instant at) {
    }
}
