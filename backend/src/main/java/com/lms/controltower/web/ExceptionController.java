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
package com.lms.controltower.web;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.lms.controltower.command.ExceptionCommandService;
import com.lms.controltower.command.domain.ExceptionCategory;
import com.lms.controltower.query.ControlTowerQueryService;
import com.lms.controltower.query.ExceptionActivityView;
import com.lms.controltower.query.ExceptionSummaryView;
import com.lms.controltower.query.ExceptionView;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.query.Slice;
import com.lms.shared.web.IdResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
 * The control tower desk (vision document 3.8).
 *
 * <p>Each lifecycle step is its own endpoint rather than a PATCH of the status.
 * The steps carry different permissions and different required fields -- a
 * resolution must say how, a reopen must say why -- and a single status field
 * would turn every one of those rules into a conditional on the request body.
 */
@RestController
@RequestMapping("/api/v1/exceptions")
public class ExceptionController {

    private final ExceptionCommandService exceptions;
    private final ControlTowerQueryService queries;

    public ExceptionController(ExceptionCommandService exceptions,
                               ControlTowerQueryService queries) {
        this.exceptions = exceptions;
        this.queries = queries;
    }

    @GetMapping
    public Slice<ExceptionView> list(@RequestParam(required = false) String status,
                                     @RequestParam(required = false) String severity,
                                     @RequestParam(required = false) String cursor,
                                     @RequestParam(required = false) Integer limit) {
        return queries.list(status, severity, cursor, limit);
    }

    @GetMapping("/summary")
    public ExceptionSummaryView summary() {
        return queries.summary();
    }

    @GetMapping("/{id}")
    public ExceptionView get(@PathVariable UUID id) {
        return queries.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Exception", id));
    }

    @GetMapping("/{id}/history")
    public List<ExceptionActivityView> history(@PathVariable UUID id) {
        return queries.historyOf(id);
    }

    @PostMapping
    public ResponseEntity<IdResponse> raise(@Valid @RequestBody RaiseRequest request) {
        UUID id = exceptions.raise(request.tripId(), request.category(), request.description(),
                request.lat(), request.lon());
        return ResponseEntity.created(URI.create("/api/v1/exceptions/" + id))
                .body(new IdResponse(id));
    }

    @PostMapping("/{id}/assign")
    public ResponseEntity<Void> assign(@PathVariable UUID id,
                                       @Valid @RequestBody AssignRequest request) {
        exceptions.assign(id, request.assigneeUserId(), request.note());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/investigate")
    public ResponseEntity<Void> investigate(@PathVariable UUID id,
                                            @RequestBody(required = false) NoteRequest request) {
        exceptions.startInvestigation(id, request == null ? null : request.note());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/resolve")
    public ResponseEntity<Void> resolve(@PathVariable UUID id,
                                        @Valid @RequestBody ResolveRequest request) {
        exceptions.resolve(id, request.resolution());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reopen")
    public ResponseEntity<Void> reopen(@PathVariable UUID id,
                                       @Valid @RequestBody ReopenRequest request) {
        exceptions.reopen(id, request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/close")
    public ResponseEntity<Void> close(@PathVariable UUID id,
                                      @RequestBody(required = false) NoteRequest request) {
        exceptions.close(id, request == null ? null : request.note());
        return ResponseEntity.noContent().build();
    }

    /**
     * @param lat optional; where the reporter says it happened. Used when the
     *            vehicle has no recent fix of its own.
     */
    public record RaiseRequest(@NotNull UUID tripId,
                               @NotNull ExceptionCategory category,
                               @NotBlank String description,
                               BigDecimal lat,
                               BigDecimal lon) {
    }

    public record AssignRequest(@NotNull UUID assigneeUserId, String note) {
    }

    public record NoteRequest(String note) {
    }

    public record ResolveRequest(@NotBlank String resolution) {
    }

    public record ReopenRequest(@NotBlank String reason) {
    }
}
