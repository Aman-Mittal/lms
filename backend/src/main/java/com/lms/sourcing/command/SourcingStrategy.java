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
import java.util.Optional;
import java.util.UUID;

import com.lms.sourcing.command.domain.RoutingGuide;
import com.lms.sourcing.command.domain.RoutingGuideEntry;

/**
 * Chooses which vendor to offer a load to next.
 *
 * <p>Deliberately narrow. A strategy answers one question -- given this guide
 * and the vendors already asked, who is next -- and knows nothing about
 * allocations, offers, timeouts or awards. That is what makes the vision
 * document's deferred reverse auction a new implementation of this interface
 * rather than a change to {@link AllocationService}.
 *
 * <p>Returning an entry rather than a vendor identifier matters: the entry
 * carries the response SLA and the agreed rate, and a strategy that returned
 * only "who" would force the caller to look those up again and to guess which
 * rank it had been given.
 */
public interface SourcingStrategy {

    /** Which guide strategy this implementation serves. */
    RoutingGuide.Strategy handles();

    /**
     * The next vendor to ask, or empty when the cascade is exhausted.
     *
     * @param candidates    every entry on the guide whose vendor may currently
     *                      be given work, in rank order. Vendors that have gone
     *                      inactive since the guide was written are already
     *                      filtered out.
     * @param alreadyOffered vendors this allocation has asked, in any order.
     *                      Asking somebody twice reads to them as the system
     *                      being broken, and would make the cascade loop.
     */
    Optional<RoutingGuideEntry> nextCandidate(List<RoutingGuideEntry> candidates,
                                              List<UUID> alreadyOffered,
                                              SourcingContext context);

    /**
     * What a strategy may know about the load being sourced.
     *
     * <p>An empty-ish record today. It exists so that adding a signal a future
     * strategy needs -- a spot rate, a service level, the freight's value -- is
     * a field here rather than a new parameter on every implementation.
     */
    record SourcingContext(UUID tenantId, UUID guideId, UUID loadId, java.time.LocalDate awardOn) {
    }
}
