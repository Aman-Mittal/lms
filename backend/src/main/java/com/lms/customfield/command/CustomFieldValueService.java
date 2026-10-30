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

package com.lms.customfield.command;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import com.lms.customfield.command.domain.CustomFieldDefinition;
import com.lms.customfield.command.domain.CustomFieldValue;
import com.lms.customfield.command.domain.EntityType;
import com.lms.shared.audit.TransitionAudit;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reading and writing the custom values of one record.
 *
 * <p>A write <em>replaces</em> the record's values rather than merging into
 * them. A merge cannot express "this field is now empty" without a second
 * convention, and a replace is what a form that shows every field sends. The
 * one exception is a retired field: its stored value survives every write, so
 * switching a field off never destroys what was recorded under it.
 */
@Service
public class CustomFieldValueService {

    private static final int MAX_TEXT = 500;
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {
    };

    private final CustomFieldDefinitionRepository definitions;
    private final CustomFieldValueRepository values;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final TransitionAudit audit;

    public CustomFieldValueService(CustomFieldDefinitionRepository definitions,
                                   CustomFieldValueRepository values, JdbcClient jdbc,
                                   JsonMapper json, TransitionAudit audit) {
        this.definitions = definitions;
        this.values = values;
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('CUSTOM_FIELD_READ')")
    public Map<String, Object> get(EntityType entityType, UUID entityId) {
        requireRecord(entityType, entityId);
        return values.forRecord(TenantContext.requireTenantId(), entityType.name(), entityId)
                .map(v -> parse(v.fieldValues()))
                .orElseGet(TreeMap::new);
    }

    @Transactional
    @PreAuthorize("hasAuthority('CUSTOM_FIELD_WRITE')")
    public Map<String, Object> replace(EntityType entityType, UUID entityId,
                                       Map<String, Object> submitted) {
        UUID tenantId = TenantContext.requireTenantId();
        requireRecord(entityType, entityId);

        Map<String, CustomFieldDefinition> active = new HashMap<>();
        for (CustomFieldDefinition d : definitions.ofType(tenantId, entityType.name())) {
            if (d.active()) {
                active.put(d.fieldKey(), d);
            }
        }

        List<String> problems = new ArrayList<>();
        Map<String, Object> clean = new TreeMap<>();
        for (Map.Entry<String, Object> entry : (submitted == null
                ? Map.<String, Object>of() : submitted).entrySet()) {
            CustomFieldDefinition definition = active.get(entry.getKey());
            if (definition == null) {
                problems.add("'" + entry.getKey() + "' is not a field defined for " + entityType);
            } else if (entry.getValue() != null) {
                coerce(definition, entry.getValue(), problems).ifPresent(
                        v -> clean.put(definition.fieldKey(), v));
            }
        }
        for (CustomFieldDefinition definition : active.values()) {
            if (definition.required() && !clean.containsKey(definition.fieldKey())
                    && !(submitted != null && submitted.get(definition.fieldKey()) != null)) {
                problems.add("'" + definition.fieldKey() + "' is required");
            }
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException("custom-field-invalid",
                    String.join("; ", problems.stream().sorted().toList()));
        }

        Optional<CustomFieldValue> existing = values.forRecord(tenantId, entityType.name(), entityId);
        // A retired field is no longer asked for, but what was recorded under it
        // is not the form's to throw away: carry it over untouched.
        Set<String> retired = new HashSet<>();
        for (CustomFieldDefinition d : definitions.ofType(tenantId, entityType.name())) {
            if (!d.active()) {
                retired.add(d.fieldKey());
            }
        }
        existing.map(e -> parse(e.fieldValues())).ifPresent(previous -> previous.forEach((k, v) -> {
            if (retired.contains(k) && v != null) {
                clean.putIfAbsent(k, v);
            }
        }));
        if (existing.isEmpty() && clean.isEmpty()) {
            return clean;
        }
        Json after = Json.of(json.writeValueAsString(clean));
        CustomFieldValue saved = values.save(existing
                .map(e -> e.withValues(after))
                .orElseGet(() -> CustomFieldValue.of(tenantId, entityType, entityId, after)));
        audit.record("CUSTOM_FIELD_VALUES_SET", "CUSTOM_FIELD_VALUE", saved.entityId(),
                existing.map(CustomFieldValue::fieldValues).orElse(null), after);
        return clean;
    }

    /**
     * The record must exist for the caller's tenant. Row-level security hides
     * another tenant's row, so a foreign id and a missing one answer alike.
     * The table name is from a closed enum, never from the request.
     */
    private void requireRecord(EntityType entityType, UUID entityId) {
        boolean exists = jdbc.sql("SELECT 1 FROM " + entityType.table() + " WHERE id = :id")
                .param("id", entityId).query(Integer.class).optional().isPresent();
        if (!exists) {
            throw new ResourceNotFoundException(entityType + " " + entityId + " was not found");
        }
    }

    private Map<String, Object> parse(Json stored) {
        return new TreeMap<>(json.readValue(stored.value(), OBJECT));
    }

    /** The value in its stored form, or empty after recording why it was refused. */
    private static Optional<Object> coerce(CustomFieldDefinition d, Object value,
                                           List<String> problems) {
        String key = "'" + d.fieldKey() + "'";
        switch (d.fieldType()) {
            case TEXT -> {
                if (value instanceof String s && !s.isBlank() && s.length() <= MAX_TEXT) {
                    return Optional.of(s);
                }
                problems.add(key + " must be text of 1 to " + MAX_TEXT + " characters");
            }
            case NUMBER -> {
                if (value instanceof Number n && Double.isFinite(n.doubleValue())) {
                    return Optional.of(n);
                }
                problems.add(key + " must be a number");
            }
            case BOOLEAN -> {
                if (value instanceof Boolean) {
                    return Optional.of(value);
                }
                problems.add(key + " must be true or false");
            }
            case DATE -> {
                if (value instanceof String s) {
                    try {
                        return Optional.of(LocalDate.parse(s).toString());
                    } catch (DateTimeParseException e) {
                        // falls through to the message below
                    }
                }
                problems.add(key + " must be a date as YYYY-MM-DD");
            }
            case SELECT -> {
                if (value instanceof String s && d.options() != null
                        && List.of(d.options()).contains(s)) {
                    return Optional.of(s);
                }
                problems.add(key + " must be one of " + List.of(d.options()));
            }
            default -> problems.add(key + " has an unsupported type");
        }
        return Optional.empty();
    }
}
