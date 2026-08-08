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
 */package com.lms.sourcing.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.query.Slice;
import com.lms.shared.web.IdResponse;
import com.lms.sourcing.command.AllocationService;
import com.lms.sourcing.query.AllocationView;
import com.lms.sourcing.query.OfferView;
import com.lms.sourcing.query.SourcingQueryService;
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
 * Offering a load to vendors (vision document 3.5).
 *
 * <p>Accept and reject both carry the vendor's identifier in the body even
 * though the caller is authenticated. The offer is held by a specific vendor at
 * a specific rank, and a signed-in dispatcher acting on a vendor's behalf --
 * which is how most rejections actually reach the system, by telephone -- is
 * not the vendor. The service checks the two agree.
 */
@RestController
@RequestMapping("/api/v1/allocations")
public class AllocationController {

    private final AllocationService allocations;
    private final SourcingQueryService sourcingQueries;

    public AllocationController(AllocationService allocations,
                                SourcingQueryService sourcingQueries) {
        this.allocations = allocations;
        this.sourcingQueries = sourcingQueries;
    }

    @GetMapping
    public Slice<AllocationView> list(@RequestParam(required = false) String status,
                                      @RequestParam(required = false) String cursor,
                                      @RequestParam(required = false) Integer limit) {
        return sourcingQueries.list(status, cursor, limit);
    }

    @GetMapping("/{id}")
    public AllocationView get(@PathVariable UUID id) {
        return sourcingQueries.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Allocation", id));
    }

    @GetMapping("/{id}/offers")
    public List<OfferView> offers(@PathVariable UUID id) {
        return sourcingQueries.findOffers(id);
    }

    @PostMapping
    public ResponseEntity<IdResponse> allocate(@Valid @RequestBody AllocateRequest request) {
        UUID id = allocations.allocate(request.loadId());
        return ResponseEntity.created(URI.create("/api/v1/allocations/" + id))
                .body(new IdResponse(id));
    }

    @PostMapping("/{id}/accept")
    public ResponseEntity<Void> accept(@PathVariable UUID id,
                                       @Valid @RequestBody VendorResponseRequest request) {
        allocations.accept(id, request.vendorPartnerId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<Void> reject(@PathVariable UUID id,
                                       @Valid @RequestBody VendorResponseRequest request) {
        allocations.reject(id, request.vendorPartnerId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable UUID id) {
        allocations.cancel(id);
        return ResponseEntity.noContent().build();
    }

    public record AllocateRequest(@NotNull UUID loadId) {
    }

    public record VendorResponseRequest(@NotNull UUID vendorPartnerId) {
    }
}
