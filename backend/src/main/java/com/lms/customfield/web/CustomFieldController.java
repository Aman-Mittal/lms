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

package com.lms.customfield.web;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.customfield.command.CustomFieldDefinitionService;
import com.lms.customfield.command.CustomFieldValueService;
import com.lms.customfield.command.domain.EntityType;
import com.lms.customfield.command.domain.FieldType;
import com.lms.shared.query.Slice;
import com.lms.shared.web.IdResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
 * Custom fields: what a tenant tracks beyond the standard attributes, and the
 * values it records. Authorisation is in the services, as everywhere else.
 */
@RestController
@RequestMapping("/api/v1/custom-fields")
public class CustomFieldController {

    private final CustomFieldDefinitionService definitions;
    private final CustomFieldValueService values;

    public CustomFieldController(CustomFieldDefinitionService definitions,
                                 CustomFieldValueService values) {
        this.definitions = definitions;
        this.values = values;
    }

    /** A tenant has a handful of definitions, so this is one slice with no cursor. */
    @GetMapping("/definitions")
    public Slice<CustomFieldDefinitionService.View> list(
            @RequestParam(required = false) EntityType entityType) {
        return new Slice<>(definitions.list(entityType), null, false);
    }

    @PostMapping("/definitions")
    public ResponseEntity<IdResponse> define(@Valid @RequestBody DefineRequest request) {
        UUID id = definitions.define(request.entityType(), request.key(), request.label(),
                request.fieldType(), Boolean.TRUE.equals(request.required()), request.options(),
                request.displayOrder());
        return ResponseEntity.created(URI.create("/api/v1/custom-fields/definitions/" + id))
                .body(new IdResponse(id));
    }

    @PutMapping("/definitions/{id}")
    public ResponseEntity<Void> revise(@PathVariable UUID id,
                                       @Valid @RequestBody ReviseRequest request) {
        definitions.revise(id, request.label(), Boolean.TRUE.equals(request.required()),
                request.options(), request.displayOrder(), !Boolean.FALSE.equals(request.active()));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/values/{entityType}/{entityId}")
    public CustomValues get(@PathVariable EntityType entityType, @PathVariable UUID entityId) {
        return new CustomValues(values.get(entityType, entityId));
    }

    @PutMapping("/values/{entityType}/{entityId}")
    public CustomValues replace(@PathVariable EntityType entityType, @PathVariable UUID entityId,
                                @Valid @RequestBody CustomValues request) {
        return new CustomValues(values.replace(entityType, entityId, request.values()));
    }

    public record DefineRequest(
            @NotNull EntityType entityType,
            @NotBlank String key,
            @NotBlank String label,
            @NotNull FieldType fieldType,
            Boolean required,
            List<String> options,
            Integer displayOrder) {
    }

    public record ReviseRequest(
            @NotBlank String label,
            Boolean required,
            List<String> options,
            Integer displayOrder,
            Boolean active) {
    }

    /** The values of one record, keyed by field key. */
    public record CustomValues(@NotNull Map<String, Object> values) {
    }
}
