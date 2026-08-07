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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.lms.masterdata.api.PartnerStandingPort;
import com.lms.planning.api.LoadLifecyclePort;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import com.lms.sourcing.command.domain.Allocation;
import com.lms.sourcing.command.domain.AllocationOffer;
import com.lms.sourcing.command.domain.RoutingGuide;
import com.lms.sourcing.command.domain.RoutingGuideEntry;
import com.lms.sourcing.events.AllocationExhausted;
import com.lms.sourcing.events.LoadAwarded;
import com.lms.sourcing.events.LoadOffered;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs the allocation cascade (vision document 3.5.2).
 *
 * <p>Offer the load to the vendor the strategy nominates; if they reject it or
 * let the offer lapse, move to the next; when there is nobody left, stop and
 * say so.
 *
 * <p>Stopping is the important part. An exhausted allocation is terminal and
 * needs a human, because a load nobody will take is a commercial problem -- the
 * rate is wrong, or the lane is unserviceable today -- and re-offering it
 * automatically would spin against unwilling vendors while the freight sits.
 */
@Service
public class AllocationService {

    private static final Logger log = LoggerFactory.getLogger(AllocationService.class);

    private final LoadLifecyclePort loads;
    private final PartnerStandingPort partners;
    private final RoutingGuideRepository guides;
    private final RoutingGuideEntryRepository entries;
    private final AllocationRepository allocations;
    private final AllocationOfferRepository offers;
    private final VendorAwardTally tally;
    private final Map<RoutingGuide.Strategy, SourcingStrategy> strategies =
            new EnumMap<>(RoutingGuide.Strategy.class);
    private final ApplicationEventPublisher events;

    public AllocationService(LoadLifecyclePort loads, PartnerStandingPort partners,
                             RoutingGuideRepository guides, RoutingGuideEntryRepository entries,
                             AllocationRepository allocations, AllocationOfferRepository offers,
                             VendorAwardTally tally, List<SourcingStrategy> availableStrategies,
                             ApplicationEventPublisher events) {
        this.loads = loads;
        this.partners = partners;
        this.guides = guides;
        this.entries = entries;
        this.allocations = allocations;
        this.offers = offers;
        this.tally = tally;
        this.events = events;

        // Injected as a list and indexed here, so adding a strategy is adding a
        // bean. A switch in this class would be the one place every new
        // strategy has to touch, which is exactly what the interface exists to
        // avoid.
        for (SourcingStrategy strategy : availableStrategies) {
            SourcingStrategy clash = strategies.put(strategy.handles(), strategy);
            if (clash != null) {
                throw new IllegalStateException("Two strategies claim " + strategy.handles()
                        + ": " + clash.getClass().getName() + " and " + strategy.getClass().getName());
            }
        }
    }

    /**
     * Starts sourcing a planned load.
     *
     * @return the allocation, already carrying its first offer
     */
    @Transactional
    @PreAuthorize("hasAuthority('LOAD_ALLOCATE')")
    public UUID allocate(UUID loadId) {
        UUID tenantId = TenantContext.requireTenantId();

        LoadLifecyclePort.LoadSummary load = loads.summarise(loadId)
                .orElseThrow(() -> new ResourceNotFoundException("Load", loadId));

        if (!load.isSourceable()) {
            throw new BusinessRuleViolationException("load-not-sourceable",
                    "Load " + load.loadNo() + " is " + load.status()
                            + "; only a PLANNED load can be offered to vendors");
        }

        allocations.findByLoad(tenantId, loadId).ifPresent(existing -> {
            throw new BusinessRuleViolationException("load-already-allocated",
                    "Load " + load.loadNo() + " already has an allocation in status "
                            + existing.status());
        });

        RoutingGuide guide = guides.findActiveForLane(tenantId, load.originTerminalId(),
                        load.destinationTerminalId(), load.vehicleType())
                .orElseThrow(() -> new BusinessRuleViolationException("no-routing-guide",
                        "No active routing guide covers this lane for vehicle type "
                                + load.vehicleType()));

        List<RoutingGuideEntry> eligible = eligibleEntries(tenantId, guide.id());
        if (eligible.isEmpty()) {
            throw new BusinessRuleViolationException("no-eligible-vendors",
                    "Every vendor on the routing guide for this lane is inactive or blacklisted");
        }

        RoutingGuideEntry first = strategyFor(guide)
                .nextCandidate(eligible, List.of(), contextFor(tenantId, guide.id(), loadId))
                .orElseThrow(() -> new BusinessRuleViolationException("no-eligible-vendors",
                        "The sourcing strategy nominated no vendor for this lane"));

        Allocation allocation = allocations.save(Allocation.open(UUID.randomUUID(), tenantId,
                loadId, guide.id(), guide.strategy(), first.rankNo()));

        makeOffer(tenantId, allocation, first, load.loadNo());
        return allocation.id();
    }

    /** The vendor takes the load. */
    @Transactional
    @PreAuthorize("hasAuthority('LOAD_ALLOCATE')")
    public void accept(UUID allocationId, UUID vendorPartnerId) {
        UUID tenantId = TenantContext.requireTenantId();
        Allocation allocation = require(allocationId);
        AllocationOffer pending = requirePendingOffer(tenantId, allocation, vendorPartnerId);

        offers.save(pending.accept(Instant.now()));
        Allocation awarded = allocations.save(allocation.awardTo(vendorPartnerId));

        // Planning owns the load state machine, so it is told what happened
        // rather than being handed a status to set.
        loads.recordAwarded(allocation.loadId(), vendorPartnerId);

        if (allocation.guideId() != null) {
            tally.recordAward(tenantId, allocation.guideId(), vendorPartnerId, today());
        }

        events.publishEvent(new LoadAwarded(tenantId, allocation.loadId(), awarded.id(),
                vendorPartnerId, pending.rankNo(), Instant.now()));
    }

    /** The vendor declines. The cascade moves on. */
    @Transactional
    @PreAuthorize("hasAuthority('LOAD_ALLOCATE')")
    public void reject(UUID allocationId, UUID vendorPartnerId) {
        UUID tenantId = TenantContext.requireTenantId();
        Allocation allocation = require(allocationId);
        AllocationOffer pending = requirePendingOffer(tenantId, allocation, vendorPartnerId);

        offers.save(pending.reject(Instant.now()));
        cascade(tenantId, allocation);
    }

    /**
     * Settles an offer whose deadline passed, and cascades.
     *
     * <p>Called by {@link AllocationTimeoutSweeper}, and separated from it so
     * that the rule -- a lapsed offer is a rejection -- is testable without
     * waiting for a scheduler to fire.
     */
    @Transactional
    public void timeOut(UUID offerId) {
        UUID tenantId = TenantContext.requireTenantId();

        AllocationOffer offer = offers.findById(offerId)
                .orElseThrow(() -> new ResourceNotFoundException("Allocation offer", offerId));

        if (offer.outcome() != AllocationOffer.Outcome.PENDING) {
            // The vendor answered between the sweep's query and this update.
            // Their answer wins; the sweep is a safety net, not an authority.
            return;
        }

        offers.save(offer.timeOut(Instant.now()));

        Allocation allocation = require(offer.allocationId());
        if (allocation.isOpen()) {
            cascade(tenantId, allocation);
        }
    }

    /** Abandons an allocation, releasing the load for manual handling. */
    @Transactional
    @PreAuthorize("hasAuthority('LOAD_ALLOCATE')")
    public void cancel(UUID allocationId) {
        UUID tenantId = TenantContext.requireTenantId();
        Allocation allocation = require(allocationId);

        offers.findPending(tenantId, allocationId)
                .ifPresent(pending -> offers.save(pending.cancel(Instant.now())));
        allocations.save(allocation.cancel());
    }

    // --------------------------------------------------------------- cascade

    private void cascade(UUID tenantId, Allocation allocation) {
        List<AllocationOffer> history = offers.findByAllocation(tenantId, allocation.id());
        List<UUID> asked = history.stream().map(AllocationOffer::vendorPartnerId).toList();

        List<RoutingGuideEntry> eligible = allocation.guideId() == null
                ? List.of()
                : eligibleEntries(tenantId, allocation.guideId());

        Optional<RoutingGuideEntry> next = strategyFor(allocation.strategy())
                .nextCandidate(eligible, asked,
                        contextFor(tenantId, allocation.guideId(), allocation.loadId()));

        if (next.isEmpty()) {
            Allocation exhausted = allocations.save(allocation.exhaust());
            log.info("Allocation {} for load {} exhausted after {} offers",
                    exhausted.id(), exhausted.loadId(), history.size());
            events.publishEvent(new AllocationExhausted(tenantId, allocation.loadId(),
                    allocation.id(), asked, Instant.now()));
            return;
        }

        RoutingGuideEntry entry = next.get();
        Allocation moved = allocations.save(allocation.cascadeTo(entry.rankNo()));
        makeOffer(tenantId, moved, entry, null);
    }

    private void makeOffer(UUID tenantId, Allocation allocation, RoutingGuideEntry entry,
                           String loadNo) {
        Instant now = Instant.now();
        AllocationOffer offer = offers.save(AllocationOffer.make(UUID.randomUUID(), tenantId,
                allocation.id(), entry.rankNo(), entry.vendorPartnerId(),
                now, entry.deadlineFrom(now)));

        events.publishEvent(new LoadOffered(tenantId, allocation.loadId(), allocation.id(),
                offer.id(), entry.vendorPartnerId(), entry.rankNo(), offer.respondsBy(), now));

        log.debug("Offered load {} to vendor {} at rank {} until {}",
                loadNo == null ? allocation.loadId() : loadNo,
                entry.vendorPartnerId(), entry.rankNo(), offer.respondsBy());
    }

    // --------------------------------------------------------------- helpers

    /**
     * The guide's entries, minus vendors that may no longer be given work.
     *
     * <p>Checked at offer time rather than trusted from when the guide was
     * written. A routing guide is a standing arrangement and outlives the
     * standing of the vendors in it -- 3.2.1 says no work goes to a partner
     * that is not ACTIVE, and offering a load is giving work.
     */
    private List<RoutingGuideEntry> eligibleEntries(UUID tenantId, UUID guideId) {
        return entries.findByGuide(tenantId, guideId).stream()
                .filter(entry -> partners.canTransact(entry.vendorPartnerId()))
                .toList();
    }

    private AllocationOffer requirePendingOffer(UUID tenantId, Allocation allocation,
                                                UUID vendorPartnerId) {
        AllocationOffer pending = offers.findPending(tenantId, allocation.id())
                .orElseThrow(() -> new BusinessRuleViolationException("no-pending-offer",
                        "There is no offer outstanding on this allocation"));

        if (!pending.vendorPartnerId().equals(vendorPartnerId)) {
            // The offer moved on. Answering an offer that has already cascaded
            // must not award the load -- somebody else is holding it now.
            throw new BusinessRuleViolationException("offer-not-yours",
                    "The outstanding offer is with " + partners.codeOf(pending.vendorPartnerId())
                            + ", not " + partners.codeOf(vendorPartnerId));
        }
        return pending;
    }

    private SourcingStrategy strategyFor(RoutingGuide guide) {
        return strategyFor(guide.strategy());
    }

    private SourcingStrategy strategyFor(RoutingGuide.Strategy strategy) {
        SourcingStrategy chosen = strategies.get(strategy);
        if (chosen == null) {
            throw new IllegalStateException("No sourcing strategy is registered for " + strategy);
        }
        return chosen;
    }

    private SourcingStrategy.SourcingContext contextFor(UUID tenantId, UUID guideId, UUID loadId) {
        return new SourcingStrategy.SourcingContext(tenantId, guideId, loadId, today());
    }

    /**
     * UTC, deliberately. Fair share is counted per calendar month, and a tenant
     * whose month boundary moved with a server's local zone would see awards
     * land in different months depending on where the instance runs.
     */
    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    private Allocation require(UUID allocationId) {
        return allocations.findById(allocationId)
                .orElseThrow(() -> new ResourceNotFoundException("Allocation", allocationId));
    }
}
