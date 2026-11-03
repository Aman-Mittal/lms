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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.lms.planning.api.LoadCancellationHook;
import com.lms.shared.audit.TransitionAudit;
import com.lms.shared.tenant.TenantContext;
import com.lms.sourcing.command.domain.Allocation;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Takes a cancelled load's sourcing work down with it.
 *
 * <p>Implements planning's {@link LoadCancellationHook}, called inside the
 * transaction that cancels the load, so either the load is cancelled and no
 * vendor is left holding an offer for it or nothing changed. An after-commit
 * event would leave a window, and a failed listener would leave the offer open
 * with nothing to say so.
 *
 * <p>An allocation still out with a vendor loses its pending offer; an
 * awarded one is cancelled and the award taken off the vendor's tally. An
 * EXHAUSTED allocation is already finished and is left as the record of why
 * nobody took the load. A load with no open allocation changes nothing, so a
 * repeat is harmless.
 *
 * <p>One read for all the loads, then the writes of each allocation: bounded
 * by the loads being cancelled, not by their consignments.
 */
@Component
public class LoadCancellationAdapter implements LoadCancellationHook {

    private final AllocationRepository allocations;
    private final AllocationCanceller canceller;
    private final TransitionAudit audit;

    public LoadCancellationAdapter(AllocationRepository allocations,
                                   AllocationCanceller canceller, TransitionAudit audit) {
        this.allocations = allocations;
        this.canceller = canceller;
        this.audit = audit;
    }

    @Override
    @Transactional
    public void onLoadsCancelling(Collection<UUID> loadIds) {
        if (loadIds.isEmpty()) {
            return;
        }
        UUID tenantId = TenantContext.requireTenantId();
        List<TransitionAudit.Entry> entries = new ArrayList<>();
        for (Allocation live : allocations.findLiveForLoads(tenantId, loadIds)) {
            entries.add(canceller.cancel(tenantId, live, "the load was cancelled"));
        }
        audit.recordAll("ALLOCATION_CANCELLED", AllocationService.AUDIT_TYPE, entries);
    }
}
