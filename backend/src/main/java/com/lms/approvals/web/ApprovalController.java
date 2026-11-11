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
package com.lms.approvals.web;

import java.util.List;
import java.util.UUID;

import com.lms.approvals.api.ApprovalPolicy;
import com.lms.approvals.command.ApprovalCommandService;
import com.lms.approvals.command.ApprovalPolicyService;
import com.lms.approvals.query.ApprovalDetailView;
import com.lms.approvals.query.ApprovalPolicyView;
import com.lms.approvals.query.ApprovalQueryService;
import com.lms.approvals.query.ApprovalView;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.query.Slice;
import com.lms.shared.web.IfMatch;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The approvals inbox, decisions, and a tenant's approval policies.
 * Authorisation is in the services, as everywhere else.
 */
@RestController
@RequestMapping("/api/v1/approvals")
public class ApprovalController {

    private static final String VERSION = "the approval request's version";

    private final ApprovalCommandService commands;
    private final ApprovalQueryService queries;
    private final ApprovalPolicyService policies;

    public ApprovalController(ApprovalCommandService commands, ApprovalQueryService queries,
                              ApprovalPolicyService policies) {
        this.commands = commands;
        this.queries = queries;
        this.policies = policies;
    }

    @GetMapping
    public Slice<ApprovalView> list(@RequestParam(required = false) String status,
                                    @RequestParam(required = false) String subjectType,
                                    @RequestParam(required = false) UUID subjectId,
                                    @RequestParam(required = false) ApprovalQueryService.Scope scope,
                                    @RequestParam(required = false) String cursor,
                                    @RequestParam(required = false) Integer limit) {
        return queries.list(status, subjectType, subjectId, scope, cursor, limit);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApprovalDetailView> get(@PathVariable UUID id) {
        ApprovalDetailView detail = queries.find(id)
                .orElseThrow(() -> new ResourceNotFoundException("Approval request", id));
        return ResponseEntity.ok().header(HttpHeaders.ETAG, IfMatch.etag(detail.approval().version()))
                .body(detail);
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<Void> approve(@PathVariable UUID id,
                                        @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                        @RequestBody(required = false) @Valid ApproveRequest request) {
        commands.approve(id, IfMatch.version(ifMatch, VERSION), request == null ? null : request.note());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<Void> reject(@PathVariable UUID id,
                                       @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                       @RequestBody @Valid RejectRequest request) {
        commands.reject(id, IfMatch.version(ifMatch, VERSION), request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable UUID id,
                                       @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                       @RequestBody(required = false) @Valid CancelApprovalRequest request) {
        commands.cancel(id, IfMatch.version(ifMatch, VERSION), request == null ? null : request.reason());
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------- policies

    @GetMapping("/policies")
    public List<ApprovalPolicyView> policies() {
        return policies.effective().stream().map(ApprovalPolicyView::of).toList();
    }

    @PutMapping("/policies/{subjectType}")
    public ApprovalPolicyView setPolicy(@PathVariable String subjectType,
                                        @RequestBody @Valid PolicyRequest request) {
        return ApprovalPolicyView.of(policies.set(subjectType, new ApprovalPolicyService.Change(
                request.required(), request.decidePermissions(), request.ordered(), request.allowSelfApproval(),
                request.dueMinutes(), request.onExpiry())));
    }

    @DeleteMapping("/policies/{subjectType}")
    public ApprovalPolicyView resetPolicy(@PathVariable String subjectType) {
        return ApprovalPolicyView.of(policies.reset(subjectType));
    }

    // ---------------------------------------------------------------- bodies

    public record ApproveRequest(@Size(max = 500) String note) {
    }

    public record RejectRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record CancelApprovalRequest(@Size(max = 500) String reason) {
    }

    /** Every field optional: an omitted one keeps the code default. */
    public record PolicyRequest(Boolean required, @Size(min = 1, max = 5) List<String> decidePermissions,
                                Boolean ordered, Boolean allowSelfApproval, Integer dueMinutes,
                                ApprovalPolicy.Expiry onExpiry) {
    }
}
