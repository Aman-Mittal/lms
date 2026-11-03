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
 * Implemented by a context that holds work against a load, so that cancelling
 * the load winds that work up in the same transaction.
 *
 * <p>Sourcing implements it: a cancelled load's pending offer is withdrawn and
 * its allocation cancelled, so no vendor is left holding an offer for freight
 * that no longer exists. Synchronous rather than an after-commit event, for the
 * reason {@code order::api}'s cancellation hook is: with an event, the load is
 * cancelled and committed first, and if the listener then fails the offer stays
 * open until somebody notices.
 *
 * <p>Idempotent: a load with nothing outstanding changes nothing, so a repeat
 * is harmless.
 */
public interface LoadCancellationHook {

    /** Called after the loads are decided to be cancelled, inside their transaction. */
    void onLoadsCancelling(Collection<UUID> loadIds);
}
