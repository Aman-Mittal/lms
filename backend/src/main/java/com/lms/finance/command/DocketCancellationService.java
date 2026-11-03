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
package com.lms.finance.command;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.lms.finance.command.domain.DocketReceivable;
import com.lms.shared.audit.TransitionAudit;
import com.lms.shared.config.Json;
import com.lms.shared.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retracts what a cancelled docket was billed.
 *
 * <p>The counterpart of {@link LtlRatingService#rateDocket}: that raises a
 * receivable when a docket is booked, this cancels it when the docket is.
 * Unguarded by {@code @PreAuthorize} for the same reason, since the actor is
 * the platform reacting to a cancellation somebody with authority over the
 * order already made.
 *
 * <p>A docket receivable is not on any invoice -- client billing leaves part
 * loads out and finance has no invoicing of dockets -- so there is nothing
 * issued to credit-note and cancelling is a state change. The day dockets are
 * invoiced, a receivable already on an issued invoice must go through the
 * credit-note path instead, and this is where that branch belongs.
 *
 * <p>Idempotent: a receivable already CANCELLED, and a docket that never had
 * one, are skipped, so a redelivered event changes nothing. Opens its own
 * transaction because it runs after the cancelling transaction has committed.
 * One read for the batch, then one update per docket.
 */
@Service
public class DocketCancellationService {

    private final DocketReceivableRepository receivables;
    private final TransitionAudit audit;

    public DocketCancellationService(DocketReceivableRepository receivables,
                                     TransitionAudit audit) {
        this.receivables = receivables;
        this.audit = audit;
    }

    /** @return how many receivables were cancelled by this call */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int cancelReceivables(Collection<UUID> docketIds) {
        if (docketIds.isEmpty()) {
            return 0;
        }
        UUID tenantId = TenantContext.requireTenantId();
        List<TransitionAudit.Entry> entries = new ArrayList<>();
        for (DocketReceivable receivable : receivables.findByDockets(tenantId, docketIds)) {
            if (receivable.status() == DocketReceivable.Status.CANCELLED) {
                continue;
            }
            DocketReceivable cancelled = receivables.save(receivable.cancel(
                    "Docket " + receivable.docketNo() + " was cancelled with its order"));
            entries.add(new TransitionAudit.Entry(cancelled.id(),
                    Json.object("status", receivable.status().name(),
                            "totalAmount", receivable.totalAmount().toPlainString()),
                    Json.object("status", "CANCELLED",
                            "totalAmount", cancelled.totalAmount().toPlainString(),
                            "reason", cancelled.reason())));
        }
        audit.recordAll("DOCKET_RECEIVABLE_CANCELLED", "DocketReceivable", entries);
        return entries.size();
    }
}
