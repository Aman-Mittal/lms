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
package com.lms.statutory.web;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lms.identity.access.AccessSubject;
import com.lms.shared.audit.AuditHistoryQueryService;
import com.lms.shared.audit.HistoryEntryView;
import com.lms.shared.audit.HistoryViewer;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.query.Slice;
import com.lms.shared.web.IdResponse;
import com.lms.statutory.command.EwayBillService;
import com.lms.statutory.query.EwayBillQueryService;
import com.lms.statutory.query.EwayBillView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Statutory e-way bills (issue #231). Reading needs a token (a customer or
 * vendor portal caller sees nothing: these are the tenant's own compliance
 * records); every change needs {@code EWAY_BILL_MANAGE}. Every refusal is a
 * problem code: an unknown action is {@code eway-action-unknown}.
 */
@RestController
@RequestMapping("/api/v1")
public class EwayBillController {

    private final EwayBillService bills;
    private final EwayBillQueryService queries;
    private final AuditHistoryQueryService history;

    public EwayBillController(EwayBillService bills, EwayBillQueryService queries,
                              AuditHistoryQueryService history) {
        this.bills = bills;
        this.queries = queries;
        this.history = history;
    }

    @GetMapping("/eway-bills")
    public Slice<EwayBillView> list(@RequestParam(required = false) String status,
                                    @RequestParam(required = false) String q,
                                    @RequestParam(required = false) String cursor,
                                    @RequestParam(required = false) Integer limit) {
        requireStaff();
        return queries.list(status, q, cursor, limit);
    }

    @GetMapping("/eway-bills/expiring")
    public Slice<EwayBillView> expiring(@RequestParam(required = false) String cursor,
                                        @RequestParam(required = false) Integer limit) {
        requireStaff();
        return queries.expiring(cursor, limit);
    }

    @GetMapping("/eway-bills/by-number/{billNo}")
    public EwayBillView byNumber(@PathVariable String billNo) {
        requireStaff();
        return queries.findByNumber(billNo)
                .orElseThrow(() -> new ResourceNotFoundException("No e-way bill has that number"));
    }

    @GetMapping("/eway-bills/{id}")
    public EwayBillView get(@PathVariable UUID id) {
        requireStaff();
        return queries.find(id).orElseThrow(() -> new ResourceNotFoundException("E-way bill", id));
    }

    @GetMapping("/eway-bills/{id}/history")
    public Slice<HistoryEntryView> history(@PathVariable UUID id, @RequestParam(required = false) String cursor,
                                           @RequestParam(required = false) Integer limit) {
        get(id);
        AccessSubject caller = AccessSubject.current();
        return history.historyOf("EWAY_BILL", id, cursor, limit, HistoryViewer.of(caller.userId(), false));
    }

    @PostMapping("/eway-bills/{id}/actions")
    public EwayBillView act(@PathVariable UUID id, @Valid @RequestBody ActionRequest request) {
        bills.act(id, request.action(), request.reason(), request.vehicleNo());
        return get(id);
    }

    @GetMapping("/consignments/{consignmentId}/eway-bills")
    public List<EwayBillView> ofConsignment(@PathVariable UUID consignmentId) {
        requireStaff();
        return queries.forConsignment(consignmentId);
    }

    @PostMapping("/consignments/{consignmentId}/eway-bills")
    public ResponseEntity<IdResponse> generate(@PathVariable UUID consignmentId,
                                               @Valid @RequestBody GenerateRequest request) {
        UUID id = bills.generate(consignmentId, request.documentRef(), request.distanceKm(), request.vehicleNo(),
                Boolean.TRUE.equals(request.fetchOnly()));
        return ResponseEntity.created(URI.create("/api/v1/eway-bills/" + id)).body(new IdResponse(id));
    }

    @PostMapping("/consignments/{consignmentId}/eway-bills/record")
    public ResponseEntity<IdResponse> record(@PathVariable UUID consignmentId,
                                             @Valid @RequestBody RecordRequest request) {
        UUID id = bills.record(consignmentId, request.billNo(), request.generatedAt(), request.validUntil(),
                request.vehicleNo(), request.distanceKm());
        return ResponseEntity.created(URI.create("/api/v1/eway-bills/" + id)).body(new IdResponse(id));
    }

    private static void requireStaff() {
        if (AccessSubject.current().partnerScope() != null) {
            throw new AccessDeniedException("E-way bills are not visible to portal callers");
        }
    }

    /** The action is a name rather than an enumeration so that an unknown one is a problem code, not a parse error. */
    public record ActionRequest(@NotBlank @Size(max = 40) String action, @Size(max = 500) String reason,
                                @Size(max = 20) String vehicleNo) {
    }

    /** {@code fetchOnly} records the bill the statutory system already holds for the document. */
    public record GenerateRequest(@Size(max = 120) String documentRef, @Positive Integer distanceKm,
                                  @Size(max = 20) String vehicleNo, Boolean fetchOnly) {
    }

    public record RecordRequest(@NotBlank @Size(max = 40) String billNo, Instant generatedAt,
                                @NotNull Instant validUntil, @Size(max = 20) String vehicleNo,
                                @Positive Integer distanceKm) {
    }
}
