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
import java.util.List;
import java.util.UUID;

import com.lms.identity.command.RoleAdminService;
import com.lms.identity.query.PermissionView;
import com.lms.identity.query.RoleQueryService;
import com.lms.identity.query.RoleView;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.web.IdResponse;
import com.lms.shared.web.IfMatch;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Roles and the permission vocabulary they are built from (issue 258). */
@RestController
@RequestMapping("/api/v1")
public class RoleController {

    private final RoleQueryService queries;
    private final RoleAdminService admin;

    public RoleController(RoleQueryService queries, RoleAdminService admin) {
        this.queries = queries;
        this.admin = admin;
    }

    @GetMapping("/roles")
    public List<RoleView> list() {
        return queries.list();
    }

    @GetMapping("/roles/{id}")
    public ResponseEntity<RoleView> get(@PathVariable UUID id) {
        RoleView view = queries.find(id).orElseThrow(() -> new ResourceNotFoundException("Role", id));
        return ResponseEntity.ok().eTag(IfMatch.etag(view.version())).body(view);
    }

    @GetMapping("/permissions")
    public List<PermissionView> permissions() {
        return queries.permissions();
    }

    @PostMapping("/roles")
    public ResponseEntity<IdResponse> create(@Valid @RequestBody CreateRoleRequest request) {
        UUID id = admin.create(request.code(), request.name(), request.permissions());
        return ResponseEntity.created(URI.create("/api/v1/roles/" + id)).body(new IdResponse(id));
    }

    /** Renames the role and replaces what it grants; a stale {@code If-Match} is a 409. */
    @PutMapping("/roles/{id}")
    public ResponseEntity<Void> update(@PathVariable UUID id,
                                       @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                       @Valid @RequestBody UpdateRoleRequest request) {
        admin.update(id, IfMatch.version(ifMatch, "the role's version"), request.name(), request.permissions());
        return ResponseEntity.noContent().build();
    }

    /** Deletes a role nobody holds. */
    @DeleteMapping("/roles/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        admin.delete(id);
        return ResponseEntity.noContent().build();
    }

    public record CreateRoleRequest(@NotBlank String code, @NotBlank String name, List<String> permissions) {
    }

    public record UpdateRoleRequest(@NotBlank String name, List<String> permissions) {
    }
}
