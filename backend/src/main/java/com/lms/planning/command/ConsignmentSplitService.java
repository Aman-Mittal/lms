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
package com.lms.planning.command;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.lms.planning.command.domain.Consignment;
import com.lms.planning.events.ConsignmentSplit;
import com.lms.shared.audit.TransitionAudit;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One consignment carried by many vehicles.
 *
 * <p>Consignments are cut per consignee and destination, and a consignment
 * rides on one load per leg. Bulk freight for one consignee -- a few thousand
 * tonnes -- therefore arrives as one consignment no vehicle can carry. Splitting
 * it gives each vehicle its own part, an ordinary full truckload that is loaded,
 * dispatched, delivered, proved, claimed against and billed exactly as any
 * other; see {@link Consignment#split} for the rules.
 *
 * <p>Kept apart from {@link PlanningCommandService} because nothing else in
 * load building needs it: it reads one consignment and writes its parts.
 */
@Service
public class ConsignmentSplitService {

    /** A split part's reference and identity, for the caller to quote. */
    public record SplitPart(UUID id, String trackingRef) {
    }

    private final ConsignmentRepository consignments;
    private final PlanningLinks links;
    private final TransitionAudit audit;
    private final ApplicationEventPublisher events;

    public ConsignmentSplitService(ConsignmentRepository consignments, PlanningLinks links,
                                   TransitionAudit audit, ApplicationEventPublisher events) {
        this.consignments = consignments;
        this.links = links;
        this.audit = audit;
        this.events = events;
    }

    /**
     * Splits a planned full truckload into parts.
     *
     * <p>All or nothing: the whole becomes SPLIT, the parts are written and
     * linked to the order lines the whole was cut from, and both are audited,
     * in one transaction. Two planners splitting -- or one splitting while
     * another puts the whole on a load -- race on the whole's version, and the
     * loser gets 409 {@code concurrent-modification} with nothing written.
     *
     * @return the parts, in receipt order
     */
    @Transactional
    @PreAuthorize("hasAuthority('LOAD_CREATE')")
    public List<SplitPart> split(UUID consignmentId, List<Consignment.PartSpec> specs) {
        UUID tenantId = TenantContext.requireTenantId();
        Consignment whole = consignments.findById(consignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Consignment", consignmentId));

        int count = specs == null ? 0 : specs.size();
        List<UUID> ids = new ArrayList<>(count);
        List<String> refs = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            ids.add(UUID.randomUUID());
            refs.add(Consignment.partRef(whole.trackingRef(), i, count));
        }
        Consignment.Split split = whole.split(specs, ids, refs);

        // The whole first: its version is the lock, so a lost race stops here
        // before any part is written.
        Consignment parent = consignments.save(split.parent());
        List<Consignment> parts = new ArrayList<>(count);
        consignments.saveAll(split.parts()).forEach(parts::add);
        links.linkPartsToLinesOf(tenantId, parent.id());

        audit.record("CONSIGNMENT_SPLIT", PlanningAudit.CONSIGNMENT, parent.id(),
                PlanningAudit.consignment(whole), PlanningAudit.consignment(parent));
        audit.recordAll("CONSIGNMENT_GENERATED", PlanningAudit.CONSIGNMENT, parts.stream()
                .map(part -> new TransitionAudit.Entry(part.id(), null, PlanningAudit.consignment(part)))
                .toList());

        events.publishEvent(new ConsignmentSplit(tenantId, parent.id(), parent.trackingRef(),
                parent.orderId(), parts.stream().map(Consignment::id).toList(),
                parts.stream().map(Consignment::trackingRef).toList(), Instant.now()));
        return parts.stream().map(part -> new SplitPart(part.id(), part.trackingRef())).toList();
    }
}
