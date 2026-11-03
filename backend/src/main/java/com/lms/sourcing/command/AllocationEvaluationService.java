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

import java.util.List;
import java.util.UUID;

import com.lms.masterdata.api.PartnerStandingPort;
import com.lms.planning.api.LoadLifecyclePort;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import com.lms.sourcing.command.domain.RoutingGuide;
import com.lms.sourcing.command.domain.RoutingGuideEntry;
import com.lms.sourcing.query.AllocationEvaluationView;
import com.lms.sourcing.query.AllocationRuleView.VendorRef;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A dry run of allocation: which rule would fire for a load, and who would be
 * offered it first, changing nothing.
 *
 * <p>It uses the same guide lookup, the same eligibility test and the same
 * nomination as {@link AllocationService#allocate}, so its answer for a load
 * that is then allocated is the vendor the first offer actually goes to.
 */
@Service
public class AllocationEvaluationService {

    private final LoadLifecyclePort loads;
    private final PartnerStandingPort partners;
    private final RoutingGuideRepository guides;
    private final RoutingGuideEntryRepository entries;
    private final AllocationRules allocationRules;
    private final List<SourcingStrategy> strategies;
    private final JdbcClient jdbc;

    public AllocationEvaluationService(LoadLifecyclePort loads, PartnerStandingPort partners,
                                       RoutingGuideRepository guides,
                                       RoutingGuideEntryRepository entries,
                                       AllocationRules allocationRules,
                                       List<SourcingStrategy> strategies, JdbcClient jdbc) {
        this.loads = loads;
        this.partners = partners;
        this.guides = guides;
        this.entries = entries;
        this.allocationRules = allocationRules;
        this.strategies = strategies;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('ALLOCATION_RULE_READ')")
    public AllocationEvaluationView evaluate(UUID loadId) {
        UUID tenantId = TenantContext.requireTenantId();
        LoadLifecyclePort.LoadSummary load = loads.summarise(loadId)
                .orElseThrow(() -> new ResourceNotFoundException("Load", loadId));

        RoutingGuide guide = guides.findActiveForLane(tenantId, load.originTerminalId(),
                        load.destinationTerminalId(), load.vehicleType())
                .orElseThrow(() -> new BusinessRuleViolationException("no-routing-guide",
                        "No active routing guide covers this lane for vehicle type "
                                + load.vehicleType()));
        List<RoutingGuideEntry> eligible = entries.findByGuide(tenantId, guide.id()).stream()
                .filter(entry -> partners.canTransact(entry.vendorPartnerId())).toList();

        AllocationRules.Decision decision = allocationRules.decide(tenantId, load);
        SourcingStrategy strategy = strategies.stream().filter(s -> s.handles() == guide.strategy())
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "No sourcing strategy is registered for " + guide.strategy()));
        RoutingGuideEntry next = allocationRules.nominate(decision, strategy, eligible, List.of(),
                new SourcingStrategy.SourcingContext(tenantId, guide.id(), loadId,
                        java.time.LocalDate.now(java.time.ZoneOffset.UTC))).orElse(null);

        return new AllocationEvaluationView(loadId, load.loadNo(),
                decision.fired() ? decision.rule().id() : null,
                decision.fired() ? decision.rule().name() : null,
                decision.fired() ? decision.rule().action().name() : null,
                next == null ? null : ref(next),
                allocationRules.orderAfter(decision, eligible).stream().map(this::ref).toList());
    }

    private VendorRef ref(RoutingGuideEntry entry) {
        String name = jdbc.sql("SELECT legal_name FROM business_partner WHERE id = :id")
                .param("id", entry.vendorPartnerId()).query(String.class).optional().orElse(null);
        return new VendorRef(entry.vendorPartnerId(), name);
    }
}
