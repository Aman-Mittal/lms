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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import com.lms.customfield.command.domain.CustomFieldDefinition;
import com.lms.customfield.command.domain.EntityType;
import com.lms.customfield.command.domain.FieldType;
import com.lms.shared.audit.TransitionAudit;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Defining the fields a tenant tracks.
 *
 * <p>Guarded by {@code CUSTOM_FIELD_MANAGE}, separate from filling a field in:
 * what a form asks and what is typed into it are different decisions, and the
 * first reshapes every record that follows.
 */
@Service
public class CustomFieldDefinitionService {

    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9_]{1,39}$");
    private static final int MAX_LABEL = 80;
    private static final int MAX_OPTIONS = 50;

    private final CustomFieldDefinitionRepository definitions;
    private final TransitionAudit audit;

    public CustomFieldDefinitionService(CustomFieldDefinitionRepository definitions,
                                        TransitionAudit audit) {
        this.definitions = definitions;
        this.audit = audit;
    }

    /** A definition as the API shows it. */
    public record View(UUID id, EntityType entityType, String key, String label,
                       FieldType fieldType, boolean required, List<String> options,
                       int displayOrder, boolean active) {

        static View of(CustomFieldDefinition d) {
            return new View(d.id(), d.entityType(), d.fieldKey(), d.label(), d.fieldType(),
                    d.required(), d.options() == null ? List.of() : List.of(d.options()),
                    d.displayOrder(), d.active());
        }
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('CUSTOM_FIELD_READ')")
    public List<View> list(EntityType entityType) {
        UUID tenantId = TenantContext.requireTenantId();
        List<CustomFieldDefinition> found = entityType == null
                ? definitions.forTenant(tenantId)
                : definitions.ofType(tenantId, entityType.name());
        return found.stream().map(View::of).toList();
    }

    @Transactional
    @PreAuthorize("hasAuthority('CUSTOM_FIELD_MANAGE')")
    public UUID define(EntityType entityType, String key, String label, FieldType fieldType,
                       boolean required, List<String> options, Integer displayOrder) {
        UUID tenantId = TenantContext.requireTenantId();
        if (key == null || !KEY.matcher(key).matches()) {
            throw new BusinessRuleViolationException("custom-field-key-invalid",
                    "A field key starts with a lower-case letter and has 2 to 40 lower-case "
                            + "letters, digits or underscores");
        }
        String cleanLabel = requireLabel(label);
        String[] cleanOptions = cleanOptions(fieldType, options);

        boolean taken = definitions.ofType(tenantId, entityType.name()).stream()
                .anyMatch(d -> d.fieldKey().equals(key));
        if (taken) {
            throw new BusinessRuleViolationException("custom-field-key-taken",
                    "A " + entityType + " field with key '" + key + "' already exists");
        }
        CustomFieldDefinition saved = definitions.save(CustomFieldDefinition.of(tenantId,
                entityType, key, cleanLabel, fieldType, required, cleanOptions,
                displayOrder == null ? 0 : displayOrder));
        audit.record("CUSTOM_FIELD_DEFINED", "CUSTOM_FIELD_DEFINITION", saved.id(), null,
                Json.object("entityType", entityType.name(), "key", key,
                        "fieldType", fieldType.name(), "required", String.valueOf(required)));
        return saved.id();
    }

    /**
     * Changes what a field is called, whether it is required, its options and
     * its place, or retires it. Its key and type stay as they were.
     *
     * <p>Narrowing the options of a choice does not touch values already
     * stored; they are validated when next written, not retroactively.
     */
    @Transactional
    @PreAuthorize("hasAuthority('CUSTOM_FIELD_MANAGE')")
    public void revise(UUID id, String label, boolean required, List<String> options,
                       Integer displayOrder, boolean active) {
        CustomFieldDefinition existing = definitions.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CustomFieldDefinition", id));
        String cleanLabel = requireLabel(label);
        String[] cleanOptions = cleanOptions(existing.fieldType(), options);

        definitions.save(existing.revised(cleanLabel, required, cleanOptions,
                displayOrder == null ? existing.displayOrder() : displayOrder, active));
        audit.record("CUSTOM_FIELD_REVISED", "CUSTOM_FIELD_DEFINITION", id,
                Json.object("label", existing.label(), "required", String.valueOf(existing.required()),
                        "active", String.valueOf(existing.active())),
                Json.object("label", cleanLabel, "required", String.valueOf(required),
                        "active", String.valueOf(active)));
    }

    private static String requireLabel(String label) {
        if (label == null || label.isBlank() || label.strip().length() > MAX_LABEL) {
            throw new BusinessRuleViolationException("custom-field-label-invalid",
                    "A label is 1 to " + MAX_LABEL + " characters");
        }
        return label.strip();
    }

    /** Options for a choice, none for anything else, each distinct and non-blank. */
    private static String[] cleanOptions(FieldType type, List<String> options) {
        List<String> given = options == null ? List.of() : options;
        if (type != FieldType.SELECT) {
            if (!given.isEmpty()) {
                throw new BusinessRuleViolationException("custom-field-options-invalid",
                        "Only a SELECT field has options");
            }
            return null;
        }
        List<String> cleaned = new ArrayList<>();
        for (String option : given) {
            if (option == null || option.isBlank() || option.strip().length() > MAX_LABEL) {
                throw new BusinessRuleViolationException("custom-field-options-invalid",
                        "Each option is 1 to " + MAX_LABEL + " characters");
            }
            cleaned.add(option.strip());
        }
        if (cleaned.isEmpty() || cleaned.size() > MAX_OPTIONS
                || new HashSet<>(cleaned).size() != cleaned.size()) {
            throw new BusinessRuleViolationException("custom-field-options-invalid",
                    "A SELECT field has 1 to " + MAX_OPTIONS + " distinct options");
        }
        return cleaned.toArray(String[]::new);
    }
}
