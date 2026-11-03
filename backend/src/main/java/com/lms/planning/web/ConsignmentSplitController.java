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

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.lms.planning.command.ConsignmentSplitService;
import com.lms.planning.command.domain.Consignment;
import com.lms.planning.query.ConsignmentView;
import com.lms.planning.query.PlanningQueryService;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.query.Slice;
import jakarta.validation.Valid;
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
 * Splitting a consignment too big for one vehicle, and reading its parts.
 *
 * <p>The request carries only the parts' weights and volumes; the count and
 * the arithmetic are the consignment's rules, answered with their own codes
 * rather than as malformed input.
 */
@RestController
@RequestMapping("/api/v1/consignments/{id}")
public class ConsignmentSplitController {

    private final ConsignmentSplitService splits;
    private final PlanningQueryService planningQueries;

    public ConsignmentSplitController(ConsignmentSplitService splits,
                                      PlanningQueryService planningQueries) {
        this.splits = splits;
        this.planningQueries = planningQueries;
    }

    @PostMapping("/split")
    public ResponseEntity<List<ConsignmentSplitService.SplitPart>> split(
            @PathVariable UUID id, @Valid @RequestBody SplitConsignmentRequest request) {
        List<ConsignmentSplitService.SplitPart> parts = splits.split(id, request.parts().stream()
                .map(part -> new Consignment.PartSpec(part.deadWeightKg(), part.volumeM3()))
                .toList());
        return ResponseEntity.created(URI.create("/api/v1/consignments/" + id + "/parts"))
                .body(parts);
    }

    @GetMapping("/parts")
    public Slice<ConsignmentView> parts(@PathVariable UUID id,
                                        @RequestParam(required = false) String cursor,
                                        @RequestParam(required = false) Integer limit) {
        return planningQueries.listParts(id, cursor, limit)
                .orElseThrow(() -> new ResourceNotFoundException("Consignment", id));
    }

    public record SplitConsignmentRequest(@NotNull List<@NotNull @Valid PartRequest> parts) {
    }

    public record PartRequest(@NotNull BigDecimal deadWeightKg, @NotNull BigDecimal volumeM3) {
    }
}
