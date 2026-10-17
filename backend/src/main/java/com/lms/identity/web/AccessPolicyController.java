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
import java.util.Map;
import java.util.UUID;

import com.lms.identity.command.AccessPolicyCommandService;
import com.lms.identity.command.domain.AccessPolicy;
import com.lms.identity.query.AccessPolicyView;
import com.lms.identity.query.AccessQueryService;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.query.Slice;
import com.lms.shared.web.IdResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Access policies as data: a tenant's attribute-based rules, administered
 * over the API rather than shipped in a release.
 */
@RestController
@RequestMapping("/api/v1/access-policies")
public class AccessPolicyController {

    private final AccessPolicyCommandService commands;
    private final AccessQueryService queries;

    public AccessPolicyController(AccessPolicyCommandService commands, AccessQueryService queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @GetMapping
    public Slice<AccessPolicyView> list(@RequestParam(required = false) String action,
                                        @RequestParam(required = false) String cursor,
                                        @RequestParam(required = false) Integer limit) {
        return queries.list(action, cursor, limit);
    }

    @GetMapping("/{id}")
    public AccessPolicyView get(@PathVariable UUID id) {
        return queries.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AccessPolicy", id));
    }

    @PostMapping
    public ResponseEntity<IdResponse> create(@Valid @RequestBody CreatePolicyRequest request) {
        UUID id = commands.create(request.code(), request.action(), request.effect(),
                request.description(), request.condition(),
                request.enabled() == null || request.enabled());
        return ResponseEntity.created(URI.create("/api/v1/access-policies/" + id))
                .body(new IdResponse(id));
    }

    /**
     * Replaces what a policy says. The code and the action stay: a policy
     * that governed a different action would be a different policy.
     */
    @PutMapping("/{id}")
    public ResponseEntity<Void> revise(@PathVariable UUID id,
                                       @Valid @RequestBody RevisePolicyRequest request) {
        commands.revise(id, request.effect(), request.description(), request.condition(),
                request.enabled());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        commands.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** @param enabled optional; a new policy is enabled unless this says otherwise */
    public record CreatePolicyRequest(@NotBlank String code,
                                      @NotBlank String action,
                                      @NotNull AccessPolicy.Effect effect,
                                      @NotBlank String description,
                                      @NotEmpty Map<String, Object> condition,
                                      Boolean enabled) {
    }

    public record RevisePolicyRequest(@NotNull AccessPolicy.Effect effect,
                                      @NotBlank String description,
                                      @NotEmpty Map<String, Object> condition,
                                      boolean enabled) {
    }
}
