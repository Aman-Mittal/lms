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
package com.lms.execution.web;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lms.execution.command.GatePassCheckService;
import com.lms.execution.command.GatePassService;
import com.lms.execution.command.GateRegisterService;
import com.lms.execution.command.domain.GateVisit;
import com.lms.execution.query.GatePassCheckView;
import com.lms.execution.query.GatePassView;
import com.lms.execution.query.GateQueryService;
import com.lms.execution.query.GateVisitView;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.query.Slice;
import com.lms.shared.web.IdResponse;
import com.lms.shared.web.IfMatch;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The yard gate (issue #223): the register of every vehicle that comes in and
 * goes out, and the passes that let it.
 *
 * <p>An entry names its site (an organisational unit, or a terminal and so its
 * unit) and a registered vehicle or a visitor's registration number; its exit
 * closes it. A second entry while the vehicle is inside is
 * {@code gate-entry-already-open}. A pass is checked at entry (see
 * {@code yard.gate.passRequired}) and the desk can ask what the gate would
 * decide with {@code GET /gate/passes/check}.
 */
@RestController
@RequestMapping("/api/v1/gate")
public class GateController {

    private final GateRegisterService register;
    private final GatePassService passes;
    private final GatePassCheckService checks;
    private final GateQueryService queries;

    public GateController(GateRegisterService register, GatePassService passes, GatePassCheckService checks,
                          GateQueryService queries) {
        this.register = register;
        this.passes = passes;
        this.checks = checks;
        this.queries = queries;
    }

    // ------------------------------------------------------------- register

    @PostMapping("/entries")
    public ResponseEntity<IdResponse> enter(@Valid @RequestBody EntryRequest request) {
        UUID id = register.enter(new GateRegisterService.EnterCommand(request.orgUnitId(), request.terminalId(),
                request.vehicleId(), request.registrationNo(), request.driverId(), request.driverName(),
                request.purpose(), request.tripId(), request.gatePassNo(), request.at(), request.sealNos(),
                request.weightRef(), request.remarks()));
        return ResponseEntity.created(URI.create("/api/v1/gate/visits/" + id)).body(new IdResponse(id));
    }

    @PostMapping("/visits/{id}/exit")
    public ResponseEntity<Void> exit(@PathVariable UUID id, @RequestBody(required = false) @Valid ExitRequest request) {
        ExitRequest body = request == null ? new ExitRequest(null, null, null, null) : request;
        register.exit(id, body.at(), body.sealNos(), body.weightRef(), body.remarks());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/visits")
    public Slice<GateVisitView> visits(@RequestParam(required = false) String status,
                                       @RequestParam(required = false) UUID orgUnitId,
                                       @RequestParam(required = false) UUID terminalId,
                                       @RequestParam(required = false) String purpose,
                                       @RequestParam(required = false) UUID tripId,
                                       @RequestParam(required = false) String q,
                                       @RequestParam(required = false) String cursor,
                                       @RequestParam(required = false) Integer limit) {
        return queries.visits(status, orgUnitId, terminalId, purpose, tripId, q, cursor, limit);
    }

    @GetMapping("/visits/{id}")
    public GateVisitView visit(@PathVariable UUID id) {
        return queries.visit(id).orElseThrow(() -> new ResourceNotFoundException("GateVisit", id));
    }

    /** Who is inside now, longest first (at most 500). */
    @GetMapping("/open")
    public List<GateVisitView> open(@RequestParam(required = false) UUID orgUnitId,
                                    @RequestParam(required = false) UUID terminalId) {
        return queries.openVisits(orgUnitId, terminalId);
    }

    // --------------------------------------------------------------- passes

    @PostMapping("/passes")
    public ResponseEntity<IdResponse> issue(@Valid @RequestBody IssuePassRequest request) {
        UUID id = passes.issue(new GatePassService.IssueCommand(request.orgUnitId(), request.terminalId(),
                request.vehicleId(), request.registrationNo(), request.tripId(), request.purpose(),
                request.validFrom(), request.validUntil(), request.passNo()));
        return ResponseEntity.created(URI.create("/api/v1/gate/passes/" + id)).body(new IdResponse(id));
    }

    @GetMapping("/passes")
    public Slice<GatePassView> passes(@RequestParam(required = false) String status,
                                      @RequestParam(required = false) UUID orgUnitId,
                                      @RequestParam(required = false) String q,
                                      @RequestParam(required = false) String cursor,
                                      @RequestParam(required = false) Integer limit) {
        return queries.passes(status, orgUnitId, q, cursor, limit);
    }

    /** What the gate would decide for a vehicle now; consumes nothing. */
    @GetMapping("/passes/check")
    public GatePassCheckView check(@RequestParam(required = false) UUID orgUnitId,
                                   @RequestParam(required = false) UUID terminalId,
                                   @RequestParam(required = false) UUID vehicleId,
                                   @RequestParam(required = false) String registrationNo,
                                   @RequestParam(required = false) UUID tripId,
                                   @RequestParam(required = false) String passNo,
                                   @RequestParam(required = false) Instant at) {
        GatePassCheckService.Admission admission = checks.check(orgUnitId, terminalId, vehicleId, registrationNo,
                tripId, passNo, at);
        return new GatePassCheckView(admission.admits(), admission.passRequired(), admission.passId(),
                admission.passNo(), admission.code(), admission.message());
    }

    @GetMapping("/passes/{id}")
    public ResponseEntity<GatePassView> pass(@PathVariable UUID id) {
        GatePassView view = queries.pass(id).orElseThrow(() -> new ResourceNotFoundException("GatePass", id));
        return ResponseEntity.ok().eTag(IfMatch.etag(view.version())).body(view);
    }

    /** Withdraws an unused pass; {@code If-Match} carries the version read. */
    @PostMapping("/passes/{id}/cancellation")
    public ResponseEntity<Void> cancel(@PathVariable UUID id,
                                       @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                       @Valid @RequestBody CancelPassRequest request) {
        passes.cancel(id, IfMatch.version(ifMatch, "the pass's version"), request.reason());
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------- requests

    public record EntryRequest(
            UUID orgUnitId,
            UUID terminalId,
            UUID vehicleId,
            @Size(max = 30) String registrationNo,
            UUID driverId,
            @Size(max = 120) String driverName,
            @NotNull GateVisit.Purpose purpose,
            UUID tripId,
            @Size(max = 60) String gatePassNo,
            Instant at,
            @Size(max = 20) List<@Size(max = 60) String> sealNos,
            @Size(max = 120) String weightRef,
            @Size(max = 500) String remarks) {
    }

    public record ExitRequest(
            Instant at,
            @Size(max = 20) List<@Size(max = 60) String> sealNos,
            @Size(max = 120) String weightRef,
            @Size(max = 500) String remarks) {
    }

    public record IssuePassRequest(
            UUID orgUnitId,
            UUID terminalId,
            UUID vehicleId,
            @Size(max = 30) String registrationNo,
            UUID tripId,
            @NotNull GateVisit.Purpose purpose,
            @NotNull Instant validFrom,
            @NotNull Instant validUntil,
            @Size(max = 60) String passNo) {
    }

    public record CancelPassRequest(@NotBlank @Size(max = 500) String reason) {
    }
}
