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
package com.lms.multimodal.command;

import java.util.UUID;

import com.lms.masterdata.api.PartnerStandingPort;
import com.lms.masterdata.api.TerminalCategoryPort;
import com.lms.multimodal.command.domain.RailTariff;
import com.lms.shared.audit.AuditTrail;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishing rail rate cards.
 *
 * <p>Guarded by {@code TARIFF_MANAGE}, the authority that publishes road rate
 * cards, rather than by {@code MULTIMODAL_PLAN}: whoever plans an itinerary
 * should not also decide what the rail leg on it costs. And, like every create
 * command, held to the caller's subtree: a card is filed where its origin
 * railhead is ({@code rail-tariff-write-org-subtree}, seeded by V26).
 */
@Service
public class RailTariffCommandService {

    private final RailTariffRepository tariffs;
    private final TerminalCategoryPort terminals;
    private final PartnerStandingPort partners;
    private final AuditTrail audit;

    public RailTariffCommandService(RailTariffRepository tariffs, TerminalCategoryPort terminals,
                                    PartnerStandingPort partners, AuditTrail audit) {
        this.tariffs = tariffs;
        this.terminals = terminals;
        this.partners = partners;
        this.audit = audit;
    }

    /**
     * Publishes a version of a carrier's card for a railhead pair, closing the
     * incumbent on the day before it starts -- in one transaction, so the
     * partial unique index never sees two open versions.
     */
    @Transactional
    @PreAuthorize("hasAuthority('TARIFF_MANAGE')"
            + " and @abac.permits('TARIFF_MANAGE', 'TERMINAL',"
            + " #command == null ? null : #command.originTerminalId())")
    public UUID publish(PublishRailTariffCommand command) {
        UUID tenantId = TenantContext.requireTenantId();
        RailTariff candidate = RailTariff.publish(UUID.randomUUID(), tenantId, command.code(),
                command.carrierPartnerId(), command.originTerminalId(),
                command.destinationTerminalId(), command.currency(), command.perWagonRate(),
                command.perContainerRate(), command.minimumCharge(), command.effectiveFrom(),
                currentActor());

        requireRailhead(candidate.originTerminalId());
        requireRailhead(candidate.destinationTerminalId());
        if (!partners.canTransact(candidate.carrierPartnerId())) {
            throw new BusinessRuleViolationException("rail-tariff-carrier-cannot-transact",
                    "The rail carrier is unknown or not active");
        }

        tariffs.findOpenVersion(tenantId, candidate.carrierPartnerId(),
                candidate.originTerminalId(), candidate.destinationTerminalId()).ifPresent(
                incumbent -> tariffs.save(incumbent.closeOn(candidate.effectiveFrom().minusDays(1))));

        RailTariff published = tariffs.save(candidate);
        audit.recordAtomically("RAIL_TARIFF_PUBLISHED", "RAIL_TARIFF", published.id(), null,
                Json.object("code", published.code(),
                        "effectiveFrom", published.effectiveFrom().toString()));
        return published.id();
    }

    private void requireRailhead(UUID terminalId) {
        String category = terminals.functionalCategoryOf(terminalId)
                .orElseThrow(() -> new ResourceNotFoundException("Terminal", terminalId));
        if (!ItineraryCommandService.RAILHEAD.equals(category)) {
            throw new BusinessRuleViolationException("rail-tariff-not-railhead",
                    "A rail rate card runs between railheads, not from or to a " + category);
        }
    }

    private static String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || authentication.getName() == null
                ? ItineraryCommandService.SYSTEM_ACTOR
                : authentication.getName();
    }
}
