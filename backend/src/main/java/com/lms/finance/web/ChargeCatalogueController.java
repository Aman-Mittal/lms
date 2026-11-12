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

import com.lms.finance.command.ChargeCatalogueService;
import com.lms.finance.command.domain.ChargeBasis;
import com.lms.finance.command.domain.ChargeDirection;
import com.lms.finance.query.ChargeCatalogueQueryService;
import com.lms.finance.query.ChargeCatalogueView;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.query.Slice;
import com.lms.shared.web.IdResponse;
import com.lms.shared.web.IfMatch;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The tenant's catalogue of manual charges (issue #225). Edits carry {@code If-Match}. */
@RestController
@RequestMapping("/api/v1/charge-catalogue")
public class ChargeCatalogueController {

    private final ChargeCatalogueService commands;
    private final ChargeCatalogueQueryService queries;

    public ChargeCatalogueController(ChargeCatalogueService commands, ChargeCatalogueQueryService queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @GetMapping
    public Slice<ChargeCatalogueView> list(@RequestParam(required = false) Boolean active,
                                           @RequestParam(required = false) String direction,
                                           @RequestParam(required = false) String q,
                                           @RequestParam(required = false) String cursor,
                                           @RequestParam(required = false) Integer limit) {
        return queries.list(active, direction, q, cursor, limit);
    }

    @PostMapping
    public ResponseEntity<IdResponse> create(@Valid @RequestBody CreateRequest request) {
        UUID id = commands.create(request.code(), request.name(), request.basis(), request.defaultRate(),
                request.direction(), request.active());
        return ResponseEntity.created(URI.create("/api/v1/charge-catalogue/" + id)).body(new IdResponse(id));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ChargeCatalogueView> get(@PathVariable UUID id) {
        ChargeCatalogueView view = queries.find(id)
                .orElseThrow(() -> new ResourceNotFoundException("ChargeCatalogue", id));
        return ResponseEntity.ok().eTag(IfMatch.etag(view.version())).body(view);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Void> update(@PathVariable UUID id,
                                       @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                       @Valid @RequestBody UpdateRequest request) {
        commands.update(id, IfMatch.version(ifMatch, "the charge's version"), request.name(), request.basis(),
                request.defaultRate(), request.direction(), request.active());
        return ResponseEntity.noContent().build();
    }

    public record CreateRequest(
            @NotBlank @Size(max = 40) String code,
            @NotBlank @Size(max = 120) String name,
            @NotNull ChargeBasis basis,
            @NotNull @PositiveOrZero BigDecimal defaultRate,
            @NotNull ChargeDirection direction,
            Boolean active) {
    }

    public record UpdateRequest(
            @NotBlank @Size(max = 120) String name,
            @NotNull ChargeBasis basis,
            @NotNull @PositiveOrZero BigDecimal defaultRate,
            @NotNull ChargeDirection direction,
            Boolean active) {
    }
}
