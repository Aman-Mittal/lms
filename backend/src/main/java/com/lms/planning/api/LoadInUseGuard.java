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

import java.util.Collection;
import java.util.UUID;

/**
 * Implemented by a context that runs work against a load, so that the load's
 * award or freight cannot be taken away while that work is live.
 *
 * <p>Planning cannot see trips: execution depends on planning, never the other
 * way round. So planning publishes the question and execution answers it,
 * synchronously and inside the caller's transaction, because a refusal has to
 * stop the change. Asked before an award is withdrawn
 * ({@link LoadLifecyclePort#recordAwardWithdrawn}) and before an order's
 * freight is taken off its loads.
 */
public interface LoadInUseGuard {

    /**
     * @throws com.lms.shared.error.BusinessRuleViolationException to refuse,
     *         which rolls back everything the caller has done so far
     */
    void requireNotInUse(Collection<UUID> loadIds);
}
