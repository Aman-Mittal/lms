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
import java.util.Set;
import java.util.UUID;

import com.lms.sourcing.command.domain.RoutingGuide;
import com.lms.sourcing.command.domain.RoutingGuideEntry;
import org.springframework.stereotype.Component;

/**
 * Walks the guide in rank order (vision document 3.5.1).
 *
 * <p>The default, and the one that matches how contracts are actually written:
 * the rank-one vendor holds the lane at an agreed rate, and the others exist
 * for when they cannot take it. Rank is respected strictly -- a cheaper
 * rank-three vendor does not jump the queue, because the ranking already
 * encodes commitments the rate alone does not.
 */
@Component
public class ContractualRoutingGuideStrategy implements SourcingStrategy {

    @Override
    public RoutingGuide.Strategy handles() {
        return RoutingGuide.Strategy.CONTRACTUAL;
    }

    @Override
    public Optional<RoutingGuideEntry> nextCandidate(List<RoutingGuideEntry> candidates,
                                                     List<UUID> alreadyOffered,
                                                     SourcingContext context) {
        Set<UUID> asked = Set.copyOf(alreadyOffered);

        // Candidates arrive in rank order, so "the next one not yet asked" is
        // the whole rule. Filtering on who has been asked rather than on the
        // current rank means a vendor removed from the guide mid-cascade does
        // not strand the allocation on a rank that no longer exists.
        return candidates.stream()
                .filter(entry -> !asked.contains(entry.vendorPartnerId()))
                .findFirst();
    }
}
