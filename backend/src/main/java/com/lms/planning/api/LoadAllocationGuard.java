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
package com.lms.planning.api;

import java.util.UUID;

/**
 * Implemented by a context that offers loads to vendors, so that a load's
 * freight is not changed under an offer or an award.
 *
 * <p>Sourcing implements it. A vendor asked to carry a load was asked to
 * carry what was aboard at the time; taking a consignment off, or reopening
 * the load for building, while the offer is out would leave the vendor
 * answering for freight that is no longer the load. The allocation is
 * withdrawn first (cancelling it in sourcing), and then the load may change.
 *
 * <p>Synchronous and inside the caller's transaction, as {@link LoadInUseGuard}
 * is, because a refusal has to stop the change.
 */
public interface LoadAllocationGuard {

    /**
     * @throws com.lms.shared.error.BusinessRuleViolationException
     *         {@code load-allocated} while the load has an allocation that is
     *         out with a vendor or awarded
     */
    void requireNotAllocated(UUID loadId);
}
