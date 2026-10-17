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
import java.util.UUID;

import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.web.IdResponse;
import com.lms.sourcing.command.RoutingGuideCommandService;
import com.lms.sourcing.command.domain.RoutingGuide;
import com.lms.sourcing.query.RoutingGuideView;
import com.lms.sourcing.query.SourcingQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The contractual routing guide (vision document 3.5).
 *
 * <p>Which vendors serve a lane, and in what order. Without this a tenant could
 * plan loads but never source one, because allocation walks a guide nobody
 * had a way to write.
 */
@RestController
@RequestMapping("/api/v1/routing-guides")
public class RoutingGuideController {

    private final RoutingGuideCommandService guides;
    private final SourcingQueryService queries;

    public RoutingGuideController(RoutingGuideCommandService guides, SourcingQueryService queries) {
        this.guides = guides;
        this.queries = queries;
    }

    @PostMapping
    public ResponseEntity<IdResponse> define(@Valid @RequestBody DefineGuideRequest request) {
        UUID id = guides.define(request.orgUnitId(), request.originTerminalId(),
                request.destinationTerminalId(), request.vehicleType(), request.strategy());
        return ResponseEntity.created(URI.create("/api/v1/routing-guides/" + id))
                .body(new IdResponse(id));
    }

    @GetMapping("/{id}")
    public RoutingGuideView get(@PathVariable UUID id) {
        return queries.findGuide(id)
                .orElseThrow(() -> new ResourceNotFoundException("Routing guide", id));
    }

    @PostMapping("/{id}/vendors")
    public ResponseEntity<IdResponse> addVendor(@PathVariable UUID id,
                                                @Valid @RequestBody AddVendorRequest request) {
        UUID entryId = guides.addVendor(id, request.vendorPartnerId(), request.rankNo(),
                request.responseSlaMinutes(), request.agreedRate());
        return ResponseEntity.created(URI.create("/api/v1/routing-guides/" + id))
                .body(new IdResponse(entryId));
    }

    @PostMapping("/{id}/retire")
    public ResponseEntity<Void> retire(@PathVariable UUID id) {
        guides.retire(id);
        return ResponseEntity.noContent().build();
    }

    public record DefineGuideRequest(@NotNull UUID orgUnitId,
                                     @NotNull UUID originTerminalId,
                                     @NotNull UUID destinationTerminalId,
                                     @NotBlank String vehicleType,
                                     RoutingGuide.Strategy strategy) {
    }

    public record AddVendorRequest(@NotNull UUID vendorPartnerId,
                                   @Min(1) int rankNo,
                                   @Min(1) int responseSlaMinutes,
                                   @PositiveOrZero BigDecimal agreedRate) {
    }
}
