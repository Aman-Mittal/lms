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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.lms.sourcing.command.AllocationRules.Decision;
import com.lms.sourcing.command.domain.AllocationRule;
import com.lms.sourcing.command.domain.AllocationRule.LoadFacts;
import com.lms.sourcing.command.domain.AllocationRule.RuleAction;
import com.lms.sourcing.command.domain.RoutingGuide;
import com.lms.sourcing.command.domain.RoutingGuideEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a rule matches, and what it does to the order vendors are asked in,
 * without a database: the rules are plain data and the nomination is a pure
 * function of them, the guide's entries and who has already been asked.
 */
class AllocationRulesTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID GUIDE = UUID.randomUUID();
    private static final UUID ORIGIN = UUID.randomUUID();
    private static final UUID DESTINATION = UUID.randomUUID();
    private static final UUID V1 = UUID.randomUUID();
    private static final UUID V2 = UUID.randomUUID();
    private static final UUID V3 = UUID.randomUUID();

    private final AllocationRules rules = new AllocationRules(null, null);
    private final SourcingStrategy guideOrder = new ContractualRoutingGuideStrategy();
    private final SourcingStrategy.SourcingContext context =
            new SourcingStrategy.SourcingContext(TENANT, GUIDE, UUID.randomUUID(), java.time.LocalDate.now());

    /** The guide's vendors in rank order V1, V2, V3. */
    private final List<RoutingGuideEntry> eligible = List.of(entry(1, V1), entry(2, V2), entry(3, V3));

    private static RoutingGuideEntry entry(int rank, UUID vendor) {
        return RoutingGuideEntry.at(UUID.randomUUID(), TENANT, GUIDE, rank, vendor, 60, BigDecimal.TEN);
    }

    private static AllocationRule rule(RuleAction action, UUID origin, UUID destination, String min,
                                       String max, String materialClass, UUID... vendors) {
        return AllocationRule.of(TENANT, "r", 10, true, origin, destination,
                min == null ? null : new BigDecimal(min), max == null ? null : new BigDecimal(max),
                materialClass, action, vendors);
    }

    private static LoadFacts load(String weight, String... classes) {
        return new LoadFacts(ORIGIN, DESTINATION, weight == null ? null : new BigDecimal(weight),
                Set.of(classes));
    }

    private List<UUID> order(Decision decision) {
        return rules.orderAfter(decision, eligible).stream().map(RoutingGuideEntry::vendorPartnerId).toList();
    }

    private UUID first(Decision decision, UUID... asked) {
        return rules.nominate(decision, guideOrder, eligible, List.of(asked), context)
                .map(RoutingGuideEntry::vendorPartnerId).orElse(null);
    }

    // ------------------------------------------------------------ matching

    @Test
    @DisplayName("a rule with no conditions matches every load")
    void noConditionsMatchesAll() {
        assertThat(rule(RuleAction.PREFER, null, null, null, null, null, V1).matches(load("100"))).isTrue();
        assertThat(rule(RuleAction.PREFER, null, null, null, null, null, V1).matches(load(null))).isTrue();
    }

    @Test
    @DisplayName("every condition stated must hold")
    void allConditionsMustHold() {
        AllocationRule lane = rule(RuleAction.PREFER, ORIGIN, DESTINATION, null, null, null, V1);
        assertThat(lane.matches(load("100"))).isTrue();
        assertThat(rule(RuleAction.PREFER, UUID.randomUUID(), null, null, null, null, V1)
                .matches(load("100"))).as("another origin").isFalse();
        assertThat(rule(RuleAction.PREFER, null, UUID.randomUUID(), null, null, null, V1)
                .matches(load("100"))).as("another destination").isFalse();
        assertThat(rule(RuleAction.PREFER, ORIGIN, DESTINATION, "50", "500", "FOOD", V1)
                .matches(load("100", "FOOD", "GENERAL"))).isTrue();
        assertThat(rule(RuleAction.PREFER, ORIGIN, DESTINATION, "50", "500", "FOOD", V1)
                .matches(load("100", "GENERAL"))).as("class absent").isFalse();
    }

    @Test
    @DisplayName("a weight band includes both its ends, and an unknown weight fails any band")
    void weightBand() {
        AllocationRule band = rule(RuleAction.PREFER, null, null, "1000", "5000", null, V1);
        assertThat(band.matches(load("999.999"))).isFalse();
        assertThat(band.matches(load("1000"))).isTrue();
        assertThat(band.matches(load("5000"))).isTrue();
        assertThat(band.matches(load("5000.001"))).isFalse();
        assertThat(band.matches(load(null))).as("no weight to test").isFalse();
        assertThat(rule(RuleAction.PREFER, null, null, "1000", null, null, V1).matches(load("9999999")))
                .as("open above").isTrue();
        assertThat(rule(RuleAction.PREFER, null, null, null, "1000", null, V1).matches(load("0")))
                .as("open below").isTrue();
    }

    // ------------------------------------------------------------ ordering

    @Test
    @DisplayName("with no rule the guide's own order stands")
    void noRuleChangesNothing() {
        assertThat(order(Decision.NONE)).containsExactly(V1, V2, V3);
        assertThat(first(Decision.NONE)).isEqualTo(V1);
        assertThat(first(Decision.NONE, V1)).isEqualTo(V2);
    }

    @Test
    @DisplayName("PREFER asks its vendors first in its own order, then the rest as the guide would")
    void preferGoesFirst() {
        Decision decision = new Decision(rule(RuleAction.PREFER, null, null, null, null, null, V3, V2));

        assertThat(order(decision)).containsExactly(V3, V2, V1);
        assertThat(first(decision)).isEqualTo(V3);
        assertThat(first(decision, V3)).isEqualTo(V2);
        assertThat(first(decision, V3, V2)).as("preferred vendors used up: the strategy resumes").isEqualTo(V1);
        assertThat(first(decision, V3, V2, V1)).as("nobody left").isNull();
    }

    @Test
    @DisplayName("a preferred vendor that is not eligible is simply not there")
    void preferredButIneligible() {
        UUID stranger = UUID.randomUUID();
        Decision decision = new Decision(rule(RuleAction.PREFER, null, null, null, null, null, stranger, V2));
        assertThat(order(decision)).containsExactly(V2, V1, V3);
        assertThat(first(decision)).isEqualTo(V2);
    }

    @Test
    @DisplayName("EXCLUDE never asks its vendors, and the strategy chooses among the others")
    void excludeTakesThemOut() {
        Decision decision = new Decision(rule(RuleAction.EXCLUDE, null, null, null, null, null, V1, V3));

        assertThat(order(decision)).containsExactly(V2);
        assertThat(first(decision)).isEqualTo(V2);
        assertThat(first(decision, V2)).as("only V2 was left").isNull();
    }

    @Test
    @DisplayName("excluding everyone leaves nobody to ask")
    void excludeEveryone() {
        Decision decision = new Decision(rule(RuleAction.EXCLUDE, null, null, null, null, null, V1, V2, V3));
        assertThat(order(decision)).isEmpty();
        assertThat(rules.nominate(decision, guideOrder, eligible, List.of(), context)).isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("the decision reports whether a rule fired")
    void decisionReportsFiring() {
        assertThat(Decision.NONE.fired()).isFalse();
        assertThat(new Decision(rule(RuleAction.PREFER, null, null, null, null, null, V1)).fired()).isTrue();
    }

    @Test
    @DisplayName("a strategy that would pick someone else is overridden by PREFER while preferred vendors remain")
    void preferOverridesStrategy() {
        SourcingStrategy lastFirst = new SourcingStrategy() {
            @Override
            public RoutingGuide.Strategy handles() {
                return RoutingGuide.Strategy.ROUND_ROBIN;
            }

            @Override
            public Optional<RoutingGuideEntry> nextCandidate(List<RoutingGuideEntry> candidates,
                                                             List<UUID> alreadyOffered,
                                                             SourcingContext ctx) {
                return candidates.reversed().stream()
                        .filter(e -> !alreadyOffered.contains(e.vendorPartnerId())).findFirst();
            }
        };
        Decision decision = new Decision(rule(RuleAction.PREFER, null, null, null, null, null, V1));

        assertThat(rules.nominate(decision, lastFirst, eligible, List.of(), context)
                .map(RoutingGuideEntry::vendorPartnerId)).contains(V1);
        assertThat(rules.nominate(decision, lastFirst, eligible, List.of(V1), context)
                .map(RoutingGuideEntry::vendorPartnerId)).as("then the strategy").contains(V3);
        assertThat(rules.nominate(Decision.NONE, lastFirst, eligible, List.of(), context)
                .map(RoutingGuideEntry::vendorPartnerId)).as("no rule, no change").contains(V3);
    }
}
