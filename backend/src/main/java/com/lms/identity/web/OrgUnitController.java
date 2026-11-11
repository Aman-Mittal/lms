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
package com.lms.identity.web;

import java.net.URI;
import java.util.UUID;

import com.lms.identity.command.OrgUnitService;
import com.lms.identity.command.domain.OrgUnit.UnitType;
import com.lms.identity.query.OrgUnitQueryService;
import com.lms.identity.query.OrgUnitView;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.query.Slice;
import com.lms.shared.web.IdResponse;
import com.lms.shared.web.IfMatch;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
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

/**
 * The organisational tree: read by any signed-in caller within their own
 * subtree, written with {@code ORG_UNIT_MANAGE}. Guards live on the service.
 */
@RestController
@RequestMapping("/api/v1/org-units")
public class OrgUnitController {

    private final OrgUnitService commands;
    private final OrgUnitQueryService queries;

    public OrgUnitController(OrgUnitService commands, OrgUnitQueryService queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @GetMapping
    public Slice<OrgUnitView> list(@RequestParam(required = false) String q,
                                   @RequestParam(required = false) String cursor,
                                   @RequestParam(required = false) Integer limit) {
        return queries.list(q, cursor, limit);
    }

    @PostMapping
    public ResponseEntity<IdResponse> create(@Valid @RequestBody CreateOrgUnitRequest request) {
        UUID id = commands.create(request.parentId(), request.slug(), new OrgUnitService.Draft(request.name(),
                request.unitType(), request.countryCode(), request.stateCode()));
        return ResponseEntity.created(URI.create("/api/v1/org-units/" + id)).body(new IdResponse(id));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrgUnitView> get(@PathVariable UUID id) {
        OrgUnitView view = queries.find(id).orElseThrow(() -> new ResourceNotFoundException("OrgUnit", id));
        return ResponseEntity.ok().header(HttpHeaders.ETAG, IfMatch.etag(view.version())).body(view);
    }

    /** Renames, retypes and recodes; the slug, the parent and so the path never change. */
    @PutMapping("/{id}")
    public ResponseEntity<OrgUnitView> update(@PathVariable UUID id,
                                              @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                              @Valid @RequestBody UpdateOrgUnitRequest request) {
        commands.update(id, IfMatch.version(ifMatch, "the unit's version"), new OrgUnitService.Draft(
                request.name(), request.unitType(), request.countryCode(), request.stateCode()));
        return get(id);
    }

    public record CreateOrgUnitRequest(@NotNull UUID parentId, @NotNull @Size(max = 63) String slug,
                                       @NotNull @Size(max = 100) String name, @NotNull UnitType unitType,
                                       @Size(max = 2) String countryCode, @Size(max = 3) String stateCode) {
    }

    public record UpdateOrgUnitRequest(@NotNull @Size(max = 100) String name, @NotNull UnitType unitType,
                                       @Size(max = 2) String countryCode, @Size(max = 3) String stateCode) {
    }
}
