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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.planning.command.domain.Consignment;
import com.lms.planning.command.domain.ConsignmentAmendment;
import com.lms.planning.command.domain.ConsignmentAmendment.FieldResult;
import com.lms.planning.command.domain.ConsignmentAmendment.Outcome;
import com.lms.planning.command.domain.ConsignmentParticulars;
import com.lms.shared.audit.TransitionAudit;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Amends a consignment's particulars: contact, references, delivery window,
 * remarks.
 *
 * <p>Guarded by the version the caller read ({@code If-Match}), like an order
 * edit: a stale one is 409 {@code concurrent-modification} and nothing
 * changes. Allowed only while the consignment is PLANNED
 * ({@code consignment-not-amendable} otherwise). Judged field by field
 * ({@link ConsignmentAmendment}) and applied all or nothing: when any field is
 * refused, the refusal is 409 with every refused field named and nothing
 * written. A client reference already on another consignment of the tenant is
 * 409 {@code client-reference-taken}; two amendments racing for the same one
 * are settled by the unique index, the loser getting 409
 * {@code duplicate-resource}.
 */
@Service
public class ConsignmentAmendmentService {

    /** What an amendment did: the version now, and each named field's outcome. */
    public record Amended(UUID consignmentId, long version, List<FieldResult> fields) {
    }

    private final ConsignmentRepository consignments;
    private final TransitionAudit audit;

    ConsignmentAmendmentService(ConsignmentRepository consignments, TransitionAudit audit) {
        this.consignments = consignments;
        this.audit = audit;
    }

    /**
     * @param changes field name to its new value; see {@link ConsignmentAmendment#judge}
     */
    @Transactional
    @PreAuthorize("hasAuthority('LOAD_CREATE')")
    public Amended amend(UUID consignmentId, long expectedVersion, Map<String, ?> changes) {
        UUID tenantId = TenantContext.requireTenantId();
        Consignment consignment = consignments.findById(consignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Consignment", consignmentId));
        if (consignment.version() == null || consignment.version() != expectedVersion) {
            throw new OptimisticLockingFailureException("Consignment " + consignment.trackingRef()
                    + " has changed since version " + expectedVersion);
        }
        ConsignmentParticulars before = consignment.particularsOrNone();
        ConsignmentAmendment.Result result = ConsignmentAmendment.judge(before, changes);
        // The state first: a consignment that cannot be amended is refused for
        // that, whatever was asked of it.
        Consignment amended = consignment.amended(result.next(), expectedVersion);
        if (result.refused()) {
            throw new BusinessRuleViolationException(result.refusalCode(),
                    "Consignment " + consignment.trackingRef() + " was not amended: "
                            + String.join(", ", result.refusals().keySet()) + " refused",
                    result.refusals());
        }
        String reference = result.next().clientReference();
        if (reference != null && !reference.equals(before.clientReference())
                && consignments.clientReferenceTaken(tenantId, reference, consignmentId)) {
            throw new BusinessRuleViolationException("client-reference-taken",
                    "Client reference " + reference + " is already on another consignment",
                    Map.of("clientReference", "Already on another consignment"));
        }
        if (!result.changed()) {
            return new Amended(consignmentId, consignment.version(), result.fields());
        }

        Consignment saved = consignments.save(amended);
        List<String> was = new ArrayList<>();
        List<String> now = new ArrayList<>();
        for (FieldResult field : result.fields()) {
            if (field.outcome() == Outcome.APPLIED) {
                was.add(field.field());
                was.add(text(ConsignmentAmendment.valueOf(before, field.field())));
                now.add(field.field());
                now.add(text(ConsignmentAmendment.valueOf(result.next(), field.field())));
            }
        }
        audit.record("CONSIGNMENT_AMENDED", PlanningAudit.CONSIGNMENT, saved.id(),
                Json.object(was.toArray(String[]::new)), Json.object(now.toArray(String[]::new)));
        return new Amended(consignmentId, saved.version(), result.fields());
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
