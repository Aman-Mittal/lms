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
package com.lms.sourcing.command;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.lms.masterdata.api.PartnerStandingPort;
import com.lms.masterdata.api.TerminalCategoryPort;
import com.lms.shared.audit.TransitionAudit;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import com.lms.sourcing.command.domain.AllocationRule;
import com.lms.sourcing.command.domain.AllocationRule.RuleAction;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writing and changing allocation rules.
 *
 * <p>Deciding who allocation asks first is a commercial decision, so it has
 * its own permission, as the routing guide does. A rule is never deleted:
 * deactivating it takes it out of play while the allocations it decided keep a
 * truthful answer to "which rule was that".
 */
@Service
public class AllocationRuleService {

    static final String AUDIT_TYPE = "AllocationRule";
    private static final int MAX_NAME = 120;
    private static final int MAX_PRIORITY = 100_000;
    private static final int MAX_VENDORS = 20;
    private static final Set<String> MATERIAL_CLASSES = Set.of("GENERAL", "FOOD", "PHARMA",
            "CHEMICAL", "TOXIC", "FLAMMABLE", "FRAGILE", "TEMPERATURE_CONTROLLED");

    /** What a caller supplies for a rule; the same shape to create and to revise. */
    public record RuleDraft(String name, Integer priority, Boolean active, UUID originTerminalId,
                            UUID destinationTerminalId, BigDecimal minWeightKg,
                            BigDecimal maxWeightKg, String materialClass, RuleAction action,
                            List<UUID> vendorPartnerIds) {
    }

    private final AllocationRuleRepository rules;
    private final PartnerStandingPort partners;
    private final TerminalCategoryPort terminals;
    private final TransitionAudit audit;

    public AllocationRuleService(AllocationRuleRepository rules, PartnerStandingPort partners,
                                 TerminalCategoryPort terminals, TransitionAudit audit) {
        this.rules = rules;
        this.partners = partners;
        this.terminals = terminals;
        this.audit = audit;
    }

    @Transactional
    @PreAuthorize("hasAuthority('ALLOCATION_RULE_MANAGE')")
    public UUID create(RuleDraft draft) {
        UUID tenantId = TenantContext.requireTenantId();
        Checked c = check(draft);
        AllocationRule saved = save(AllocationRule.of(tenantId, c.name, c.priority, c.active,
                draft.originTerminalId(), draft.destinationTerminalId(), c.min, c.max,
                c.materialClass, draft.action(), c.vendors));
        audit.record("ALLOCATION_RULE_CREATED", AUDIT_TYPE, saved.id(), null, snapshot(saved));
        return saved.id();
    }

    @Transactional
    @PreAuthorize("hasAuthority('ALLOCATION_RULE_MANAGE')")
    public void revise(UUID id, RuleDraft draft) {
        AllocationRule current = rules.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Allocation rule", id));
        Checked c = check(draft);
        AllocationRule saved = save(current.revised(c.name, c.priority, c.active,
                draft.originTerminalId(), draft.destinationTerminalId(), c.min, c.max,
                c.materialClass, draft.action(), c.vendors));
        audit.record("ALLOCATION_RULE_REVISED", AUDIT_TYPE, id, snapshot(current), snapshot(saved));
    }

    /** The unique name index is the arbiter, so two writers racing on one name cannot both win. */
    private AllocationRule save(AllocationRule rule) {
        try {
            return rules.save(rule);
        } catch (DuplicateKeyException e) {
            throw new BusinessRuleViolationException("allocation-rule-name-taken",
                    "A rule named '" + rule.name() + "' already exists");
        }
    }

    private record Checked(String name, int priority, boolean active, BigDecimal min,
                           BigDecimal max, String materialClass, UUID[] vendors) {
    }

    private Checked check(RuleDraft d) {
        String name = d.name() == null ? "" : d.name().strip();
        if (name.isEmpty() || name.length() > MAX_NAME) {
            throw new BusinessRuleViolationException("allocation-rule-name-invalid",
                    "A rule's name is 1 to " + MAX_NAME + " characters");
        }
        int priority = d.priority() == null ? 100 : d.priority();
        if (priority < 0 || priority > MAX_PRIORITY) {
            throw new BusinessRuleViolationException("allocation-rule-priority-invalid",
                    "Priority is between 0 and " + MAX_PRIORITY + "; the lowest number is tried first");
        }
        BigDecimal min = d.minWeightKg();
        BigDecimal max = d.maxWeightKg();
        if ((min != null && (min.signum() < 0 || min.scale() > 3))
                || (max != null && (max.signum() <= 0 || max.scale() > 3))
                || (min != null && max != null && min.compareTo(max) > 0)) {
            throw new BusinessRuleViolationException("allocation-rule-weight-band-invalid",
                    "A weight band needs a minimum of zero or more, a maximum above zero, "
                            + "no more than three decimals, and the minimum not above the maximum");
        }
        String materialClass = d.materialClass() == null || d.materialClass().isBlank()
                ? null : d.materialClass().strip();
        if (materialClass != null && !MATERIAL_CLASSES.contains(materialClass)) {
            throw new BusinessRuleViolationException("allocation-rule-material-class-invalid",
                    "Material class " + materialClass + " is not one of " + MATERIAL_CLASSES);
        }
        if (d.action() == null) {
            throw new BusinessRuleViolationException("allocation-rule-action-invalid",
                    "A rule either prefers or excludes its vendors");
        }
        for (UUID terminalId : new UUID[] {d.originTerminalId(), d.destinationTerminalId()}) {
            if (terminalId != null && terminals.functionalCategoryOf(terminalId).isEmpty()) {
                throw new ResourceNotFoundException("Terminal", terminalId);
            }
        }
        List<UUID> vendors = d.vendorPartnerIds() == null ? List.of() : d.vendorPartnerIds();
        if (vendors.isEmpty() || vendors.size() > MAX_VENDORS) {
            throw new BusinessRuleViolationException("allocation-rule-vendors-invalid",
                    "A rule names 1 to " + MAX_VENDORS + " vendors");
        }
        Set<UUID> seen = new HashSet<>();
        for (UUID vendor : vendors) {
            if (vendor == null || !seen.add(vendor)) {
                throw new BusinessRuleViolationException("allocation-rule-vendors-invalid",
                        "A vendor can be named once in a rule");
            }
            if (!partners.canTransact(vendor)) {
                throw new BusinessRuleViolationException("vendor-not-active",
                        "Partner " + vendor + " is not an active vendor of this company");
            }
        }
        return new Checked(name, priority, d.active() == null || d.active(), min, max,
                materialClass, vendors.toArray(UUID[]::new));
    }

    private static Json snapshot(AllocationRule r) {
        return Json.object("name", r.name(), "priority", Integer.toString(r.priority()),
                "active", Boolean.toString(r.active()), "action", r.action().name(),
                "originTerminalId", r.originTerminalId() == null ? null : r.originTerminalId().toString(),
                "destinationTerminalId",
                r.destinationTerminalId() == null ? null : r.destinationTerminalId().toString(),
                "minWeightKg", r.minWeightKg() == null ? null : r.minWeightKg().toPlainString(),
                "maxWeightKg", r.maxWeightKg() == null ? null : r.maxWeightKg().toPlainString(),
                "materialClass", r.materialClass(),
                "vendors", java.util.Arrays.stream(r.vendorPartnerIds()).map(UUID::toString)
                        .collect(java.util.stream.Collectors.joining(",")));
    }
}
