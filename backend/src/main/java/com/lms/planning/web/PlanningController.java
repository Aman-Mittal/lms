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

import com.lms.order.api.FreightMode;
import com.lms.planning.command.PlanningCommandService;
import com.lms.planning.command.domain.LoadUnit;
import com.lms.planning.query.ConsignmentView;
import com.lms.planning.query.LoadView;
import com.lms.planning.query.PlanningQueryService;
import com.lms.shared.audit.AuditHistoryQueryService;
import com.lms.shared.audit.HistoryEntryView;
import com.lms.shared.audit.HistoryViewer;
import com.lms.identity.access.AccessSubject;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.query.Slice;
import com.lms.shared.web.IdResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
    private final AuditHistoryQueryService history;

    public PlanningController(PlanningCommandService planning,
                              PlanningQueryService planningQueries,
                              AuditHistoryQueryService history) {
        this.planning = planning;
        this.planningQueries = planningQueries;
        this.history = history;
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

    /** The consignment's audit trail, newest first; a 404 wherever reading the consignment is. */
    @GetMapping("/consignments/{id}/history")
    public Slice<HistoryEntryView> consignmentHistory(@PathVariable UUID id,
                                                      @RequestParam(required = false) String cursor,
                                                      @RequestParam(required = false) Integer limit) {
        getConsignment(id);
        return history.historyOf("Consignment", id, cursor, limit, viewer());
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
    public Slice<ConsignmentView> unassigned(@RequestParam(required = false) UUID orderId,
                                             @RequestParam(required = false) String cursor,
                                             @RequestParam(required = false) Integer limit,
                                             @RequestParam(required = false) String q) {
        return planningQueries.listUnassigned(orderId, cursor, limit, q);
    }

    /** Every consignment by tracking reference, in any state; see {@code listConsignments}. */
    @GetMapping("/consignments")
    public Slice<ConsignmentView> consignments(@RequestParam(required = false) String status,
                                               @RequestParam(required = false) String cursor,
                                               @RequestParam(required = false) Integer limit,
                                               @RequestParam(required = false) String q) {
        return planningQueries.listConsignments(status, cursor, limit, q);
    }

    /**
     * Dockets that have arrived at a hub and are waiting for a delivery load.
     *
     * <p>Hangs off the hub because it is that hub's yard being asked about,
     * and the delivery planner there works one hub at a time.
     */
    @GetMapping("/hubs/{terminalId}/dockets-awaiting-delivery")
    public Slice<ConsignmentView> awaitingDelivery(@PathVariable UUID terminalId,
                                                   @RequestParam(required = false) String cursor,
                                                   @RequestParam(required = false) Integer limit) {
        return planningQueries.listAwaitingDelivery(terminalId, cursor, limit);
    }

    // ----------------------------------------------------------------- loads

    @GetMapping("/loads")
    public Slice<LoadView> listLoads(@RequestParam(required = false) String status,
                                     @RequestParam(required = false) String cursor,
                                     @RequestParam(required = false) Integer limit,
                                     @RequestParam(required = false) String q) {
        return planningQueries.listLoads(status, cursor, limit, q);
    }

    @GetMapping("/loads/{id}")
    public LoadView getLoad(@PathVariable UUID id) {
        return planningQueries.findLoad(id)
                .orElseThrow(() -> new ResourceNotFoundException("LoadUnit", id));
    }

    /** The load's audit trail, newest first; a 404 wherever reading the load is. */
    @GetMapping("/loads/{id}/history")
    public Slice<HistoryEntryView> loadHistory(@PathVariable UUID id,
                                               @RequestParam(required = false) String cursor,
                                               @RequestParam(required = false) Integer limit) {
        getLoad(id);
        return history.historyOf("Load", id, cursor, limit, viewer());
    }

    /** A portal caller is not told which of the tenant's staff took a step. */
    private static HistoryViewer viewer() {
        AccessSubject caller = AccessSubject.current();
        return HistoryViewer.of(caller.userId(), caller.partnerScope() != null);
    }

    @GetMapping("/loads/{id}/consignments")
    public List<ConsignmentView> loadContents(@PathVariable UUID id) {
        return planningQueries.findConsignmentsOnLoad(id);
    }

    @PostMapping("/loads")
    public ResponseEntity<IdResponse> openLoad(@Valid @RequestBody OpenLoadRequest request) {
        UUID id = planning.openLoad(request.orgUnitId(), request.loadNo(),
                request.originTerminalId(), request.vehicleId(), request.freightMode(),
                request.ltlLeg());
        return ResponseEntity.created(URI.create("/api/v1/loads/" + id))
                .body(new IdResponse(id));
    }

    @PostMapping("/loads/{id}/consignments")
    public ResponseEntity<Void> assign(@PathVariable UUID id,
                                       @Valid @RequestBody AssignConsignmentRequest request) {
        planning.assignConsignment(id, request.consignmentId());
        return ResponseEntity.noContent().build();
    }

    /**
     * Puts many consignments on a load in one request, all or none.
     *
     * <p>A sub-resource of the load's contents rather than a list body on the
     * single-assignment path, so that the one-consignment request keeps its
     * shape for every client already sending it.
     */
    @PostMapping("/loads/{id}/consignments/bulk")
    public ResponseEntity<Void> assignBulk(@PathVariable UUID id,
                                           @Valid @RequestBody BulkAssignConsignmentsRequest request) {
        planning.assignConsignments(id, request.consignmentIds());
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
            @NotNull UUID vehicleId,
            /**
             * FTL when omitted. An LTL load's origin is the hub its dockets are
             * consolidated at.
             */
            FreightMode freightMode,
            /**
             * LINE_HAUL when omitted. A DELIVERY load is part-load only: it is
             * opened at a destination hub and takes the dockets that have
             * arrived there out to their consignees.
             */
            LoadUnit.LtlLeg ltlLeg) {
    }

    public record AssignConsignmentRequest(@NotNull UUID consignmentId) {
    }

    public record BulkAssignConsignmentsRequest(
            @NotEmpty @Size(max = PlanningCommandService.MAX_BULK_ASSIGNMENT)
            List<@NotNull UUID> consignmentIds) {

        /** A consignment named twice is a client defect, not a request to board it twice. */
        @AssertTrue(message = "must not name a consignment more than once")
        public boolean isEachConsignmentNamedOnce() {
            return consignmentIds == null
                    || consignmentIds.stream().distinct().count() == consignmentIds.size();
        }
    }
}
