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
package com.lms.masterdata.web;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;

import com.lms.masterdata.command.CreditLimitChangeService;
import com.lms.masterdata.command.CreditOverrideService;
import com.lms.masterdata.command.domain.CreditOverride;
import com.lms.masterdata.query.CreditLimitChangeView;
import com.lms.masterdata.query.CreditPositionView;
import com.lms.masterdata.query.CreditQueryService;
import com.lms.shared.query.Slice;
import com.lms.shared.web.IdResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer credit: the position, maker-checker limit changes and overrides.
 * Guards live on the services, as everywhere in the web layer.
 */
@RestController
public class CreditController {

    private final CreditQueryService queries;
    private final CreditLimitChangeService changes;
    private final CreditOverrideService overrides;

    public CreditController(CreditQueryService queries, CreditLimitChangeService changes,
                            CreditOverrideService overrides) {
        this.queries = queries;
        this.changes = changes;
        this.overrides = overrides;
    }

    @GetMapping("/api/v1/partners/{id}/credit-position")
    public CreditPositionView position(@PathVariable UUID id) {
        return queries.position(id);
    }

    @GetMapping("/api/v1/partners/{id}/credit-limit-changes")
    public Slice<CreditLimitChangeView> changesOf(@PathVariable UUID id,
                                                  @RequestParam(required = false) String cursor,
                                                  @RequestParam(required = false) Integer limit) {
        return queries.changesOf(id, cursor, limit);
    }

    @PostMapping("/api/v1/partners/{id}/credit-limit-changes")
    public ResponseEntity<IdResponse> request(@PathVariable UUID id,
                                              @Valid @RequestBody CreditLimitChangeRequest request) {
        UUID changeId = changes.request(id, request.creditLimit(), request.softLimit(),
                request.reason());
        return ResponseEntity.created(URI.create("/api/v1/partners/" + id + "/credit-limit-changes"))
                .body(new IdResponse(changeId));
    }

    @GetMapping("/api/v1/credit-limit-changes")
    public Slice<CreditLimitChangeView> changes(
            @RequestParam(required = false) CreditStatus status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return queries.changes(status == null ? null : status.name(), cursor, limit);
    }

    @PostMapping("/api/v1/credit-limit-changes/{id}/approve")
    public ResponseEntity<Void> approve(@PathVariable UUID id,
                                        @Valid @RequestBody CreditLimitDecisionRequest request) {
        changes.approve(id, request.note());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/credit-limit-changes/{id}/reject")
    public ResponseEntity<Void> reject(@PathVariable UUID id,
                                       @Valid @RequestBody CreditLimitDecisionRequest request) {
        changes.reject(id, request.note());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/partners/{id}/credit-overrides")
    public ResponseEntity<IdResponse> override(@PathVariable UUID id,
                                               @Valid @RequestBody CreditOverrideRequest request) {
        UUID overrideId = overrides.grant(id, request.subjectType(), request.orderId(),
                request.period(), request.upTo(), request.reason());
        return ResponseEntity.created(URI.create("/api/v1/partners/" + id + "/credit-position"))
                .body(new IdResponse(overrideId));
    }

    /** The outcomes a change request can be listed by. */
    public enum CreditStatus {
        PENDING, APPROVED, REJECTED
    }

    public record CreditLimitChangeRequest(
            @NotNull @DecimalMin("0") BigDecimal creditLimit,
            @DecimalMin("0") BigDecimal softLimit,
            @NotBlank @Size(max = 500) String reason) {
    }

    public record CreditLimitDecisionRequest(@Size(max = 500) String note) {
    }

    public record CreditOverrideRequest(
            @NotNull CreditOverride.SubjectType subjectType,
            UUID orderId,
            LocalDate period,
            @NotNull @DecimalMin("0") BigDecimal upTo,
            @NotBlank @Size(max = 500) String reason) {
    }
}
