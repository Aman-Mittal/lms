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
 */package com.lms.planning.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.lms.planning.command.PlanningCommandService;
import com.lms.planning.query.ConsignmentView;
import com.lms.planning.query.LoadView;
import com.lms.planning.query.PlanningQueryService;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.query.Slice;
import com.lms.shared.web.IdResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consignment generation and load building (vision document 3.4).
 *
 * <p>Consignments are generated from an order rather than created directly, so
 * the endpoint hangs off the order that produced them and returns the set. The
 * grouping rule -- one consignment per consignee and destination -- belongs to
 * planning, and letting a client post consignments would move it to the client.
 */
@RestController
@RequestMapping("/api/v1")
public class PlanningController {

    private final PlanningCommandService planning;
    private final PlanningQueryService planningQueries;

    public PlanningController(PlanningCommandService planning,
                              PlanningQueryService planningQueries) {
        this.planning = planning;
        this.planningQueries = planningQueries;
    }

    // ---------------------------------------------------------- consignments

    @PostMapping("/orders/{id}/consignments")
    public ResponseEntity<List<IdResponse>> generate(@PathVariable UUID id) {
        List<IdResponse> generated = planning.generateConsignments(id).stream()
                .map(IdResponse::new)
                .toList();
        return ResponseEntity.created(URI.create("/api/v1/orders/" + id + "/consignments"))
                .body(generated);
    }

    @GetMapping("/consignments/{id}")
    public ConsignmentView getConsignment(@PathVariable UUID id) {
        return planningQueries.findConsignment(id)
                .orElseThrow(() -> new ResourceNotFoundException("Consignment", id));
    }

    /**
     * Tracking by reference, which is the one lookup an external caller makes.
     *
     * <p>A separate path rather than a query parameter on the collection: it is
     * a lookup of one thing by its public identifier, and modelling it as a
     * filtered list would hand back an envelope containing either nought or one
     * result for a question that has exactly one answer.
     */
    @GetMapping("/consignments/by-tracking-ref/{trackingRef}")
    public ConsignmentView trackByRef(@PathVariable String trackingRef) {
        return planningQueries.findByTrackingRef(trackingRef)
                .orElseThrow(() -> new ResourceNotFoundException("No consignment with tracking reference " + trackingRef));
    }

    @GetMapping("/consignments/unassigned")
    public Slice<ConsignmentView> unassigned(@RequestParam(required = false) String cursor,
                                             @RequestParam(required = false) Integer limit) {
        return planningQueries.listUnassigned(cursor, limit);
    }

    // ----------------------------------------------------------------- loads

    @GetMapping("/loads")
    public Slice<LoadView> listLoads(@RequestParam(required = false) String status,
                                     @RequestParam(required = false) String cursor,
                                     @RequestParam(required = false) Integer limit) {
        return planningQueries.listLoads(status, cursor, limit);
    }

    @GetMapping("/loads/{id}")
    public LoadView getLoad(@PathVariable UUID id) {
        return planningQueries.findLoad(id)
                .orElseThrow(() -> new ResourceNotFoundException("LoadUnit", id));
    }

    @GetMapping("/loads/{id}/consignments")
    public List<ConsignmentView> loadContents(@PathVariable UUID id) {
        return planningQueries.findConsignmentsOnLoad(id);
    }

    @PostMapping("/loads")
    public ResponseEntity<IdResponse> openLoad(@Valid @RequestBody OpenLoadRequest request) {
        UUID id = planning.openLoad(request.orgUnitId(), request.loadNo(),
                request.originTerminalId(), request.vehicleId());
        return ResponseEntity.created(URI.create("/api/v1/loads/" + id))
                .body(new IdResponse(id));
    }

    @PostMapping("/loads/{id}/consignments")
    public ResponseEntity<Void> assign(@PathVariable UUID id,
                                       @Valid @RequestBody AssignConsignmentRequest request) {
        planning.assignConsignment(id, request.consignmentId());
        return ResponseEntity.noContent().build();
    }

    /** Closes the load to further building, making it sourceable. */
    @PostMapping("/loads/{id}/plan")
    public ResponseEntity<Void> plan(@PathVariable UUID id) {
        planning.planLoad(id);
        return ResponseEntity.noContent().build();
    }

    public record OpenLoadRequest(
            @NotNull UUID orgUnitId,
            @NotBlank String loadNo,
            @NotNull UUID originTerminalId,
            @NotNull UUID vehicleId) {
    }

    public record AssignConsignmentRequest(@NotNull UUID consignmentId) {
    }
}
