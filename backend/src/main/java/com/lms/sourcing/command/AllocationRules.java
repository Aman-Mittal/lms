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

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.lms.planning.api.LoadLifecyclePort.LoadSummary;
import com.lms.sourcing.command.domain.AllocationRule;
import com.lms.sourcing.command.domain.AllocationRule.LoadFacts;
import com.lms.sourcing.command.domain.AllocationRule.RuleAction;
import com.lms.sourcing.command.domain.RoutingGuideEntry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Applies a tenant's allocation rules to the vendors a load would be offered to.
 *
 * <p>The first matching rule by priority decides, alone. It either puts the
 * vendors it names at the front (PREFER) or takes them out (EXCLUDE); with no
 * rule, or none matching, the guide's strategy chooses exactly as it always
 * has, which is what keeps a tenant without rules unaffected.
 *
 * <p>The decision is made once, when the load is first offered, and recorded.
 * The cascade that follows a rejection or a lapse then keeps applying that
 * same rule, even if it has been edited or retired in the meantime: an
 * allocation is decided under one rule, not whichever is current when each
 * vendor happens to answer.
 */
@Component
public class AllocationRules {

    /** The rule that decided, or {@code NONE} when none matched. */
    public record Decision(AllocationRule rule) {

        public static final Decision NONE = new Decision(null);

        public boolean fired() {
            return rule != null;
        }
    }

    private final AllocationRuleRepository rules;
    private final JdbcClient jdbc;

    public AllocationRules(AllocationRuleRepository rules, JdbcClient jdbc) {
        this.rules = rules;
        this.jdbc = jdbc;
    }

    /** The first active rule, by priority, that matches the load. */
    public Decision decide(UUID tenantId, LoadSummary load) {
        List<AllocationRule> active = rules.findActive(tenantId);
        if (active.isEmpty()) {
            return Decision.NONE;
        }
        LoadFacts facts = new LoadFacts(load.originTerminalId(), load.destinationTerminalId(),
                load.plannedWeightKg(), materialClassesOf(load.loadId()));
        return active.stream().filter(rule -> rule.matches(facts)).findFirst()
                .map(Decision::new).orElse(Decision.NONE);
    }

    /** What an allocation was decided under, as recorded when it was opened. */
    public Decision recordedFor(UUID allocationId) {
        Optional<UUID> ruleId = jdbc.sql("SELECT rule_id FROM allocation_rule_firing WHERE allocation_id = :a")
                .param("a", allocationId).query(UUID.class).optional();
        return ruleId.flatMap(rules::findById).map(Decision::new).orElse(Decision.NONE);
    }

    /** Writes down what decided the allocation, or that nothing did. */
    public void record(UUID tenantId, UUID allocationId, Decision decision) {
        AllocationRule rule = decision.rule();
        jdbc.sql("""
                        INSERT INTO allocation_rule_firing (allocation_id, tenant_id, rule_id, rule_name, action)
                        VALUES (:a, :t, :rule, :name, :action)
                        """)
                .param("a", allocationId)
                .param("t", tenantId)
                .param("rule", rule == null ? null : rule.id())
                .param("name", rule == null ? null : rule.name())
                .param("action", rule == null ? null : rule.action().name())
                .update();
    }

    /**
     * The vendors in the order they will be considered, after the rule: the
     * ones a PREFER rule names first in its order, the rest as given; an
     * EXCLUDE rule's vendors gone.
     */
    public List<RoutingGuideEntry> orderAfter(Decision decision, List<RoutingGuideEntry> eligible) {
        AllocationRule rule = decision.rule();
        if (rule == null) {
            return eligible;
        }
        if (rule.action() == RuleAction.EXCLUDE) {
            return eligible.stream().filter(e -> !rule.names(e.vendorPartnerId())).toList();
        }
        List<RoutingGuideEntry> preferred = eligible.stream()
                .filter(e -> rule.names(e.vendorPartnerId()))
                .sorted(Comparator.comparingInt(e -> rule.positionOf(e.vendorPartnerId())))
                .toList();
        List<RoutingGuideEntry> rest = eligible.stream()
                .filter(e -> !rule.names(e.vendorPartnerId())).toList();
        return java.util.stream.Stream.concat(preferred.stream(), rest.stream()).toList();
    }

    /**
     * The next vendor to ask. A PREFER rule's vendors come first, in the rule's
     * order, while any of them is still unasked; after that the guide's strategy
     * chooses among the rest, as it would with no rule.
     */
    public Optional<RoutingGuideEntry> nominate(Decision decision, SourcingStrategy strategy,
                                                List<RoutingGuideEntry> eligible, List<UUID> asked,
                                                SourcingStrategy.SourcingContext context) {
        AllocationRule rule = decision.rule();
        if (rule != null && rule.action() == RuleAction.PREFER) {
            Set<UUID> alreadyAsked = new HashSet<>(asked);
            Optional<RoutingGuideEntry> preferred = orderAfter(decision, eligible).stream()
                    .filter(e -> rule.names(e.vendorPartnerId()))
                    .filter(e -> !alreadyAsked.contains(e.vendorPartnerId()))
                    .findFirst();
            if (preferred.isPresent()) {
                return preferred;
            }
        }
        return strategy.nextCandidate(orderAfter(decision, eligible), asked, context);
    }

    private Set<String> materialClassesOf(UUID loadId) {
        return new HashSet<>(jdbc.sql("""
                        SELECT DISTINCT unnest(string_to_array(c.material_classes, ',')) AS material_class
                          FROM load_consignment lc
                          JOIN consignment c ON c.id = lc.consignment_id
                         WHERE lc.load_id = :load
                        """)
                .param("load", loadId).query(String.class).list());
    }
}
