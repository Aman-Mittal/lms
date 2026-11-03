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
package com.lms.sourcing.web;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.query.Slice;
import com.lms.shared.web.IdResponse;
import com.lms.sourcing.command.AllocationEvaluationService;
import com.lms.sourcing.command.AllocationRuleService;
import com.lms.sourcing.command.AllocationRuleService.RuleDraft;
import com.lms.sourcing.command.domain.AllocationRule.RuleAction;
import com.lms.sourcing.query.AllocationEvaluationView;
import com.lms.sourcing.query.AllocationRuleQueryService;
import com.lms.sourcing.query.AllocationRuleView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant-written preferences for who a load is offered to.
 *
 * <p>Revising is a PUT carrying the whole rule, as custom field definitions
 * are: a rule is small, and a partial update would leave "which conditions does
 * it have now" depending on what was sent last time.
 */
@RestController
@RequestMapping("/api/v1/allocation-rules")
public class AllocationRuleController {

    private final AllocationRuleService service;
    private final AllocationRuleQueryService queries;
    private final AllocationEvaluationService evaluations;

    public AllocationRuleController(AllocationRuleService service, AllocationRuleQueryService queries,
                                    AllocationEvaluationService evaluations) {
        this.service = service;
        this.queries = queries;
        this.evaluations = evaluations;
    }

    @GetMapping
    public Slice<AllocationRuleView> list(@RequestParam(required = false) Boolean active,
                                          @RequestParam(required = false) String cursor,
                                          @RequestParam(required = false) Integer limit) {
        return queries.list(active, cursor, limit);
    }

    @GetMapping("/{id}")
    public AllocationRuleView get(@PathVariable UUID id) {
        return queries.find(id).orElseThrow(() -> new ResourceNotFoundException("Allocation rule", id));
    }

    @PostMapping
    public ResponseEntity<IdResponse> create(@Valid @RequestBody RuleRequest request) {
        UUID id = service.create(request.toDraft());
        return ResponseEntity.created(URI.create("/api/v1/allocation-rules/" + id))
                .body(new IdResponse(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Void> revise(@PathVariable UUID id, @Valid @RequestBody RuleRequest request) {
        service.revise(id, request.toDraft());
        return ResponseEntity.noContent().build();
    }

    /** A dry run: a POST because it takes a body, though it changes nothing. */
    @PostMapping("/evaluations")
    public AllocationEvaluationView evaluate(@Valid @RequestBody EvaluationRequest request) {
        return evaluations.evaluate(request.loadId());
    }

    public record RuleRequest(
            @NotBlank String name,
            Integer priority,
            Boolean active,
            UUID originTerminalId,
            UUID destinationTerminalId,
            BigDecimal minWeightKg,
            BigDecimal maxWeightKg,
            String materialClass,
            @NotNull RuleAction action,
            @NotEmpty List<UUID> vendorPartnerIds) {

        RuleDraft toDraft() {
            return new RuleDraft(name, priority, active, originTerminalId, destinationTerminalId,
                    minWeightKg, maxWeightKg, materialClass, action, vendorPartnerIds);
        }
    }

    public record EvaluationRequest(@NotNull UUID loadId) {
    }
}
