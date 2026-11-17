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
import java.util.UUID;

import com.lms.masterdata.api.PartnerStandingPort;
import com.lms.masterdata.api.TerminalCategoryPort;
import com.lms.shared.audit.TransitionAudit;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import com.lms.sourcing.command.domain.RoutingGuide;
import com.lms.sourcing.command.domain.RoutingGuideEntry;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintaining the contractual routing guide (vision document 3.5).
 *
 * <p>A guide is a standing arrangement: for this lane and this vehicle type,
 * offer the load to these vendors in this order. Writing one is a commercial
 * decision, so it carries its own permission -- being allowed to press
 * "allocate" is not the same as deciding who allocation asks.
 */
@Service
public class RoutingGuideCommandService {

    private final RoutingGuideRepository guides;
    private final RoutingGuideEntryRepository entries;
    private final PartnerStandingPort partners;
    private final JdbcClient jdbc;
    private final TerminalCategoryPort terminals;
    private final TransitionAudit audit;

    public RoutingGuideCommandService(RoutingGuideRepository guides,
                                      RoutingGuideEntryRepository entries,
                                      PartnerStandingPort partners, JdbcClient jdbc,
                                      TerminalCategoryPort terminals, TransitionAudit audit) {
        this.audit = audit;
        this.guides = guides;
        this.entries = entries;
        this.partners = partners;
        this.jdbc = jdbc;
        this.terminals = terminals;
    }

    /**
     * Opens a guide for a lane.
     *
     * <p>One per lane and vehicle type, ever -- retired ones included. A
     * retired guide is still what old allocations point at, and the answer to
     * "which arrangement did this load go out under" must not become ambiguous
     * because a second guide was written for the same lane later. Changing a
     * lane's vendors is done by editing its entries, not by starting over.
     */
    @Transactional
    @PreAuthorize("hasAuthority('ROUTING_GUIDE_MANAGE')"
            + " and @abac.permitsIn('ROUTING_GUIDE_MANAGE', #orgUnitId)")
    public UUID define(UUID orgUnitId, UUID originTerminalId, UUID destinationTerminalId,
                       String vehicleType, RoutingGuide.Strategy strategy) {
        UUID tenantId = TenantContext.requireTenantId();

        // From the body, so looked up under row-level security: another
        // tenant's terminal reads as absent.
        for (UUID terminalId : new UUID[] {originTerminalId, destinationTerminalId}) {
            if (terminals.functionalCategoryOf(terminalId).isEmpty()) {
                throw new ResourceNotFoundException("Terminal", terminalId);
            }
        }

        boolean exists = jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM routing_guide
                                        WHERE tenant_id = :tenantId
                                          AND origin_terminal_id = :origin
                                          AND destination_terminal_id = :destination
                                          AND vehicle_type = :vehicleType)
                        """)
                .param("tenantId", tenantId)
                .param("origin", originTerminalId)
                .param("destination", destinationTerminalId)
                .param("vehicleType", vehicleType)
                .query(Boolean.class)
                .single();
        if (exists) {
            throw new BusinessRuleViolationException("routing-guide-exists",
                    "This lane already has a routing guide for vehicle type " + vehicleType
                            + "; change its vendors rather than writing a second one");
        }

        return guides.save(RoutingGuide.define(UUID.randomUUID(), tenantId, orgUnitId,
                originTerminalId, destinationTerminalId, vehicleType, strategy)).id();
    }

    /**
     * Puts a vendor on the guide at a rank.
     *
     * <p>Refused for a vendor that may not currently be given work. Offering
     * still re-checks eligibility at the time, because standing changes after
     * a guide is written -- but writing a blacklisted vendor into a guide in
     * the first place is a mistake worth catching while somebody is looking.
     */
    @Transactional
    @PreAuthorize("hasAuthority('ROUTING_GUIDE_MANAGE')")
    public UUID addVendor(UUID guideId, UUID vendorPartnerId, int rankNo, int responseSlaMinutes,
                          BigDecimal agreedRate) {
        UUID tenantId = TenantContext.requireTenantId();
        RoutingGuide guide = require(guideId);
        if (!guide.active()) {
            throw new BusinessRuleViolationException("routing-guide-retired",
                    "This routing guide has been retired and can no longer be changed");
        }
        if (!partners.canTransact(vendorPartnerId)) {
            throw new BusinessRuleViolationException("vendor-not-active",
                    "Vendor " + partners.codeOf(vendorPartnerId)
                            + " is not active and cannot be offered freight");
        }

        List<RoutingGuideEntry> current = entries.findByGuide(tenantId, guideId);
        if (current.stream().anyMatch(entry -> entry.rankNo() == rankNo)) {
            throw new BusinessRuleViolationException("rank-taken",
                    "Rank " + rankNo + " on this guide is already held by another vendor");
        }
        if (current.stream().anyMatch(entry -> entry.vendorPartnerId().equals(vendorPartnerId))) {
            throw new BusinessRuleViolationException("vendor-already-ranked",
                    "Vendor " + partners.codeOf(vendorPartnerId) + " is already on this guide");
        }

        return entries.save(RoutingGuideEntry.at(UUID.randomUUID(), tenantId, guideId, rankNo,
                vendorPartnerId, responseSlaMinutes, agreedRate)).id();
    }

    /** Stops the guide being used for new allocations; old ones keep pointing at it. */
    @Transactional
    @PreAuthorize("hasAuthority('ROUTING_GUIDE_MANAGE')")
    public void retire(UUID guideId) {
        guides.save(require(guideId).deactivate());
    }

    /**
     * Takes a vendor out of play on this guide, with a reason. Allocation skips an
     * inactive entry and offers to the next rank; the vendor keeps the rank and
     * the rate for when they come back. Refused with {@code routing-guide-vendor-in-use}
     * while the vendor holds an offer on this guide that has not been answered.
     */
    @Transactional
    @PreAuthorize("hasAuthority('ROUTING_GUIDE_MANAGE')")
    public void deactivateVendor(UUID guideId, UUID entryId, Long expectedVersion, String reason) {
        requireReason(reason);
        RoutingGuideEntry before = requireEntry(guideId, entryId, expectedVersion);
        if (!before.active()) {
            throw new BusinessRuleViolationException("routing-guide-vendor-already-inactive",
                    "Vendor " + partners.codeOf(before.vendorPartnerId()) + " is already inactive on this guide");
        }
        long pending = jdbc.sql("""
                        SELECT count(*) FROM allocation_offer o
                          JOIN allocation a ON a.id = o.allocation_id
                         WHERE a.guide_id = :guideId AND o.vendor_partner_id = :vendorId
                           AND o.outcome = 'PENDING'
                        """)
                .param("guideId", guideId).param("vendorId", before.vendorPartnerId())
                .query(Long.class).single();
        if (pending > 0) {
            throw new BusinessRuleViolationException("routing-guide-vendor-in-use",
                    "Vendor " + partners.codeOf(before.vendorPartnerId()) + " holds " + pending
                            + " open offer(s) on this guide; wait for the answer or withdraw them first");
        }
        RoutingGuideEntry saved = entries.save(before.withActive(false));
        audit.record("ROUTING_GUIDE_VENDOR_DEACTIVATED", "RoutingGuideEntry", entryId, snapshot(before, null),
                snapshot(saved, reason));
    }

    @Transactional
    @PreAuthorize("hasAuthority('ROUTING_GUIDE_MANAGE')")
    public void reactivateVendor(UUID guideId, UUID entryId, Long expectedVersion, String reason) {
        requireReason(reason);
        RoutingGuideEntry before = requireEntry(guideId, entryId, expectedVersion);
        if (before.active()) {
            throw new BusinessRuleViolationException("routing-guide-vendor-not-inactive",
                    "Vendor " + partners.codeOf(before.vendorPartnerId()) + " is active on this guide, not inactive");
        }
        if (!partners.canTransact(before.vendorPartnerId())) {
            throw new BusinessRuleViolationException("vendor-not-active",
                    "Vendor " + partners.codeOf(before.vendorPartnerId())
                            + " is not active and cannot be offered freight");
        }
        RoutingGuideEntry saved = entries.save(before.withActive(true));
        audit.record("ROUTING_GUIDE_VENDOR_REACTIVATED", "RoutingGuideEntry", entryId, snapshot(before, null),
                snapshot(saved, reason));
    }

    private RoutingGuideEntry requireEntry(UUID guideId, UUID entryId, Long expectedVersion) {
        RoutingGuide guide = require(guideId);
        RoutingGuideEntry entry = entries.findById(entryId)
                .filter(e -> e.guideId().equals(guide.id()))
                .orElseThrow(() -> new ResourceNotFoundException("Routing guide vendor", entryId));
        if (expectedVersion != null && (entry.version() == null || !entry.version().equals(expectedVersion))) {
            throw new OptimisticLockingFailureException("The vendor entry changed since it was read");
        }
        return entry;
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required");
        }
    }

    private static Json snapshot(RoutingGuideEntry e, String reason) {
        return Json.object("vendorPartnerId", e.vendorPartnerId().toString(), "rankNo", Integer.toString(e.rankNo()),
                "active", Boolean.toString(e.active()), "reason", reason);
    }

    private RoutingGuide require(UUID guideId) {
        return guides.findById(guideId)
                .orElseThrow(() -> new ResourceNotFoundException("Routing guide", guideId));
    }
}
