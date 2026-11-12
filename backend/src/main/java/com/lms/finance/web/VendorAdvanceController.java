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

package com.lms.finance.web;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;

import com.lms.finance.command.VendorAdvanceService;
import com.lms.finance.command.domain.VendorAdvance;
import com.lms.finance.query.VendorAdvanceQueryService;
import com.lms.finance.query.VendorAdvanceView;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.query.Slice;
import com.lms.shared.web.IdResponse;
import com.lms.shared.web.IfMatch;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
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
 * Advances to a vendor against a trip (issue #225): requested (201, or 202 with
 * the approval request), approved through the approvals module, paid with a
 * reference and cancelled before payment. State changes after the request take
 * {@code If-Match}.
 */
@RestController
@RequestMapping("/api/v1/vendor-advances")
public class VendorAdvanceController {

    private final VendorAdvanceService commands;
    private final VendorAdvanceQueryService queries;

    public VendorAdvanceController(VendorAdvanceService commands, VendorAdvanceQueryService queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @GetMapping
    public Slice<VendorAdvanceView> list(@RequestParam(required = false) String status,
                                         @RequestParam(required = false) UUID tripId,
                                         @RequestParam(required = false) UUID vendorId,
                                         @RequestParam(required = false) String cursor,
                                         @RequestParam(required = false) Integer limit) {
        return queries.list(status, tripId, vendorId, cursor, limit);
    }

    @PostMapping
    public ResponseEntity<?> request(@Valid @RequestBody AdvanceRequest request) {
        VendorAdvanceService.Outcome outcome = commands.request(new VendorAdvanceService.RequestCommand(
                request.tripId(), request.mode(), request.amount(), request.reason()));
        if (outcome.approval() != null) {
            return ResponseEntity.accepted().location(URI.create("/api/v1/approvals/" + outcome.approval().id()))
                    .body(new Pending(outcome.id(), outcome.approval()));
        }
        return ResponseEntity.created(URI.create("/api/v1/vendor-advances/" + outcome.id()))
                .body(new IdResponse(outcome.id()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<VendorAdvanceView> get(@PathVariable UUID id) {
        VendorAdvanceView view = queries.find(id).orElseThrow(() -> new ResourceNotFoundException("VendorAdvance", id));
        return ResponseEntity.ok().eTag(IfMatch.etag(view.version())).body(view);
    }

    /** Records that the approved advance was paid. */
    @PostMapping("/{id}/payment")
    public ResponseEntity<Void> pay(@PathVariable UUID id,
                                    @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                    @Valid @RequestBody PayRequest request) {
        commands.pay(id, IfMatch.version(ifMatch, "the advance's version"), request.reference(), request.paidOn());
        return ResponseEntity.noContent().build();
    }

    /** Cancels an advance not yet paid. */
    @PostMapping("/{id}/cancellation")
    public ResponseEntity<Void> cancel(@PathVariable UUID id,
                                       @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                       @Valid @RequestBody CancelRequest request) {
        commands.cancel(id, IfMatch.version(ifMatch, "the advance's version"), request.reason());
        return ResponseEntity.noContent().build();
    }

    /** The advance that is waiting, and the approval request that decides it. */
    public record Pending(UUID id, com.lms.approvals.api.ApprovalTicket approval) {
    }

    public record AdvanceRequest(
            @NotNull UUID tripId,
            @NotNull VendorAdvance.Mode mode,
            @NotNull @Positive BigDecimal amount,
            @NotBlank @Size(max = 500) String reason) {
    }

    public record PayRequest(@NotBlank @Size(max = 80) String reference, @NotNull LocalDate paidOn) {
    }

    public record CancelRequest(@NotBlank @Size(max = 500) String reason) {
    }
}
