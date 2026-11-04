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

import com.lms.planning.api.LoadAllocationGuard;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.tenant.TenantContext;
import com.lms.sourcing.command.domain.Allocation;
import org.springframework.stereotype.Component;

/**
 * Refuses a change to a load's freight while the load is out with a vendor.
 *
 * <p>Implements planning's {@link LoadAllocationGuard}. Live means OFFERED or
 * AWARDED, the same allocations a cancelled load takes down
 * ({@link LoadCancellationAdapter}); an EXHAUSTED one is finished and a
 * CANCELLED one is history, so neither stands in the way. The way out is the
 * one sourcing already has: cancel the allocation, which withdraws its offer
 * or award.
 */
@Component
public class LoadAllocationAdapter implements LoadAllocationGuard {

    private final AllocationRepository allocations;

    public LoadAllocationAdapter(AllocationRepository allocations) {
        this.allocations = allocations;
    }

    @Override
    public void requireNotAllocated(UUID loadId) {
        List<Allocation> live = allocations.findLiveForLoads(TenantContext.requireTenantId(),
                List.of(loadId));
        if (!live.isEmpty()) {
            throw new BusinessRuleViolationException("load-allocated",
                    "The load has an allocation that is " + live.get(0).status()
                            + "; cancel the allocation before changing the load");
        }
    }
}
