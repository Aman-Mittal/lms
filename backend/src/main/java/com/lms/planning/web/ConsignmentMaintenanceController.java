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
package com.lms.planning.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.planning.command.ConsignmentAmendmentService;
import com.lms.planning.command.ConsignmentBulkService;
import com.lms.planning.command.ConsignmentCancellationService;
import com.lms.shared.web.IfMatch;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Amending a consignment's particulars and cancelling a consignment, one at a
 * time or many at once.
 *
 * <p>A separate adapter from {@link PlanningController}, which generates and
 * reads: these change a consignment that already exists, and their bulk forms
 * answer per item rather than all or none.
 */
@RestController
@RequestMapping("/api/v1/consignments")
public class ConsignmentMaintenanceController {

    private final ConsignmentAmendmentService amendments;
    private final ConsignmentCancellationService cancellations;
    private final ConsignmentBulkService bulk;

    public ConsignmentMaintenanceController(ConsignmentAmendmentService amendments,
                                            ConsignmentCancellationService cancellations,
                                            ConsignmentBulkService bulk) {
        this.amendments = amendments;
        this.cancellations = cancellations;
        this.bulk = bulk;
    }

    /**
     * Amends the named particulars. The body names only what changes; empty
     * text clears a field. The new version comes back as the {@code ETag}.
     */
    @PatchMapping(value = "/{id}", consumes = {"application/json", "application/merge-patch+json"})
    public ResponseEntity<ConsignmentAmendmentService.Amended> amend(
            @PathVariable UUID id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody Map<String, Object> changes) {
        ConsignmentAmendmentService.Amended amended = amendments.amend(id, versionOf(ifMatch), changes);
        return ResponseEntity.ok().eTag(IfMatch.etag(amended.version())).body(amended);
    }

    @PostMapping("/amendments")
    public BulkResult amendAll(@Valid @RequestBody BulkAmendRequest request) {
        return new BulkResult(bulk.amendAll(request.items().stream()
                .map(item -> new ConsignmentBulkService.AmendItem(item.consignmentId(), item.version(),
                        item.changes()))
                .toList()));
    }

    @PostMapping("/{id}/cancellation")
    public ConsignmentCancellationService.Cancellation cancel(
            @PathVariable UUID id, @RequestBody(required = false) @Valid CancelRequest request) {
        return cancellations.cancel(id, request == null ? null : request.reason());
    }

    @PostMapping("/cancellations")
    public BulkResult cancelAll(@Valid @RequestBody BulkCancelRequest request) {
        return new BulkResult(bulk.cancelAll(request.consignmentIds(), request.reason()));
    }

    /**
     * The version in an {@code If-Match} header, quoted or not. Mandatory, and
     * never {@code *}: an unconditional edit is the lost update it prevents.
     */
    static long versionOf(String ifMatch) {
        return IfMatch.version(ifMatch, "the consignment's version");
    }

    /** Why; optional, and so is the whole body. */
    public record CancelRequest(@Size(max = 500) String reason) {
    }

    public record BulkCancelRequest(
            @NotEmpty @Size(max = ConsignmentBulkService.MAX_ITEMS) List<@NotNull UUID> consignmentIds,
            @Size(max = 500) String reason) {

        @AssertTrue(message = "must not name a consignment more than once")
        public boolean isEachConsignmentNamedOnce() {
            return consignmentIds == null
                    || consignmentIds.stream().distinct().count() == consignmentIds.size();
        }
    }

    public record AmendItemRequest(@NotNull UUID consignmentId, @NotNull Long version,
                                   @NotNull Map<String, Object> changes) {
    }

    public record BulkAmendRequest(
            @NotEmpty @Size(max = ConsignmentBulkService.MAX_ITEMS) List<@NotNull @Valid AmendItemRequest> items) {

        @AssertTrue(message = "must not name a consignment more than once")
        public boolean isEachConsignmentNamedOnce() {
            return items == null || items.stream().filter(java.util.Objects::nonNull)
                    .map(AmendItemRequest::consignmentId).distinct().count() == items.size();
        }
    }

    /** Every item's outcome, in the order asked. */
    public record BulkResult(List<ConsignmentBulkService.ItemResult> items) {
    }
}
