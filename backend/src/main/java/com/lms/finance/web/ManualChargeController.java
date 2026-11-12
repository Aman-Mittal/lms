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
import java.util.UUID;

import com.lms.finance.command.ManualChargeService;
import com.lms.finance.command.domain.ChargeDirection;
import com.lms.finance.command.domain.ManualCharge;
import com.lms.finance.query.ManualChargeQueryService;
import com.lms.finance.query.ManualChargeView;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.query.Slice;
import com.lms.shared.web.IdResponse;
import com.lms.shared.web.IfMatch;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
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
 * Charges raised by hand against a trip, consignment or order (issue #225).
 * Raising answers 201, or 202 with the approval request when the amount is
 * above the tenant's threshold; cancelling takes {@code If-Match}.
 */
@RestController
@RequestMapping("/api/v1/manual-charges")
public class ManualChargeController {

    private final ManualChargeService commands;
    private final ManualChargeQueryService queries;

    public ManualChargeController(ManualChargeService commands, ManualChargeQueryService queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @GetMapping
    public Slice<ManualChargeView> list(@RequestParam(required = false) String status,
                                        @RequestParam(required = false) String direction,
                                        @RequestParam(required = false) String subjectType,
                                        @RequestParam(required = false) UUID subjectId,
                                        @RequestParam(required = false) UUID tripId,
                                        @RequestParam(required = false) UUID vendorId,
                                        @RequestParam(required = false) UUID customerId,
                                        @RequestParam(required = false) String q,
                                        @RequestParam(required = false) String cursor,
                                        @RequestParam(required = false) Integer limit) {
        return queries.list(status, direction, subjectType, subjectId, tripId, vendorId, customerId, q, cursor,
                limit);
    }

    @PostMapping
    public ResponseEntity<?> record(@Valid @RequestBody RecordRequest request) {
        ManualChargeService.Outcome outcome = commands.record(new ManualChargeService.RecordCommand(
                request.catalogueId(), request.subjectType(), request.subjectId(), request.direction(),
                request.customerId(), request.quantity(), request.rate(), request.reason(), request.evidenceRef()));
        if (outcome.approval() != null) {
            return ResponseEntity.accepted().location(URI.create("/api/v1/approvals/" + outcome.approval().id()))
                    .body(new Pending(outcome.id(), outcome.approval()));
        }
        return ResponseEntity.created(URI.create("/api/v1/manual-charges/" + outcome.id()))
                .body(new IdResponse(outcome.id()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ManualChargeView> get(@PathVariable UUID id) {
        ManualChargeView view = queries.find(id).orElseThrow(() -> new ResourceNotFoundException("ManualCharge", id));
        return ResponseEntity.ok().eTag(IfMatch.etag(view.version())).body(view);
    }

    /** Cancels a charge not yet billed. */
    @PostMapping("/{id}/cancellation")
    public ResponseEntity<Void> cancel(@PathVariable UUID id,
                                       @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                       @Valid @RequestBody CancelRequest request) {
        commands.cancel(id, IfMatch.version(ifMatch, "the charge's version"), request.reason());
        return ResponseEntity.noContent().build();
    }

    /** The charge that is waiting, and the approval request that decides it. */
    public record Pending(UUID id, com.lms.approvals.api.ApprovalTicket approval) {
    }

    public record RecordRequest(
            @NotNull UUID catalogueId,
            @NotNull ManualCharge.SubjectType subjectType,
            @NotNull UUID subjectId,
            ChargeDirection.Side direction,
            UUID customerId,
            @NotNull @Positive BigDecimal quantity,
            @PositiveOrZero BigDecimal rate,
            @NotBlank @Size(max = 500) String reason,
            @Size(max = 200) String evidenceRef) {
    }

    public record CancelRequest(@NotBlank @Size(max = 500) String reason) {
    }
}
