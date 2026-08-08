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
 */package com.lms.telematics.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lms.shared.error.ResourceNotFoundException;
import com.lms.telematics.api.PingIngestPort;
import com.lms.telematics.query.DeviationView;
import com.lms.telematics.query.GeofenceCrossingView;
import com.lms.telematics.query.TelematicsQueryService;
import com.lms.telematics.query.TripProgressView;
import com.lms.telematics.query.VehiclePositionView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Position ingest and live visibility (vision document 3.7).
 *
 * <p>Ingest answers 202 with a report of what happened, never 4xx for a bad
 * point. A device that reports one implausible coordinate must not lose the
 * twenty-nine good ones in the same flush, and it could not fix the bad one if
 * it were told -- so rejections are counted and described in the body rather
 * than raised as a failure of the request.
 *
 * <p>The batch is capped in the request rather than only in the service. A
 * 0.1-CPU instance evaluating geofences synchronously has a real ceiling, and
 * refusing an oversized batch at the edge is cheaper than accepting it and
 * timing out halfway through.
 */
@RestController
@RequestMapping("/api/v1/telematics")
public class TelematicsController {

    /**
     * Matches {@code lms.telematics.max-batch-size}. Duplicated as a constant
     * because a validation annotation cannot read configuration -- and a
     * mismatch fails loudly at the edge rather than quietly halfway through
     * a batch.
     */
    private static final int MAX_BATCH = 500;

    private final PingIngestPort ingest;
    private final TelematicsQueryService telematics;

    public TelematicsController(PingIngestPort ingest, TelematicsQueryService telematics) {
        this.ingest = ingest;
        this.telematics = telematics;
    }

    @PostMapping("/pings")
    public ResponseEntity<PingIngestPort.IngestResult> ingest(
            @Valid @RequestBody PingBatchRequest request) {
        PingIngestPort.IngestResult result = ingest.ingest(request.pings().stream()
                .map(p -> new PingIngestPort.PingReport(p.vehicleId(), p.recordedAt(),
                        p.lat(), p.lon(), p.speedKph(), p.headingDeg(), p.accuracyM(),
                        p.ignitionOn(), p.source()))
                .toList());

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    }

    @GetMapping("/positions")
    public List<VehiclePositionView> positions() {
        return telematics.currentPositions();
    }

    @GetMapping("/positions/{vehicleId}")
    public VehiclePositionView position(@PathVariable UUID vehicleId) {
        return telematics.positionOf(vehicleId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No position has been reported for vehicle " + vehicleId));
    }

    @GetMapping("/trips/{tripId}/crossings")
    public List<GeofenceCrossingView> crossings(@PathVariable UUID tripId) {
        return telematics.crossingsFor(tripId);
    }

    /**
     * The trip's track, as a GeoJSON-ready array of coordinate pairs.
     *
     * <p>Raw points while the trip is running, the simplified polyline once
     * retention has collapsed it. The caller cannot tell which, and should not
     * need to: it is the shape of the journey either way.
     */
    @GetMapping("/trips/{tripId}/track")
    public List<double[]> track(@PathVariable UUID tripId) {
        return telematics.trackFor(tripId);
    }

    @GetMapping("/trips/{tripId}/progress")
    public TripProgressView progress(@PathVariable UUID tripId) {
        return telematics.progressOf(tripId)
                .orElseThrow(() -> new ResourceNotFoundException("Trip", tripId));
    }

    @GetMapping("/deviations")
    public List<DeviationView> openDeviations() {
        return telematics.openDeviations();
    }

    public record PingBatchRequest(
            @NotEmpty @Size(max = MAX_BATCH) @Valid List<Ping> pings) {
    }

    /**
     * One position report.
     *
     * @param recordedAt when the <em>device</em> recorded it, not when it
     *                   arrived. A buffered flush sends what it has, and
     *                   treating arrival order as position order produces a
     *                   track that jumps backwards through time.
     */
    public record Ping(
            @NotNull UUID vehicleId,
            @NotNull Instant recordedAt,
            @NotNull BigDecimal lat,
            @NotNull BigDecimal lon,
            BigDecimal speedKph,
            BigDecimal headingDeg,
            BigDecimal accuracyM,
            Boolean ignitionOn,
            String source) {
    }
}
