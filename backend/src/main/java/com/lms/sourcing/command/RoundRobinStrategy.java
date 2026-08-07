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

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.lms.sourcing.command.domain.RoutingGuide;
import com.lms.sourcing.command.domain.RoutingGuideEntry;
import org.springframework.stereotype.Component;

/**
 * Spreads work evenly across the vendors on a lane within a calendar month
 * (vision document 3.5.1).
 *
 * <p>Used where several vendors hold the same lane on equal terms and the
 * commercial arrangement is a share of the volume rather than a first refusal.
 *
 * <p>Fairness is measured over the calendar month rather than over all time. A
 * vendor added in June must not spend two years catching up on loads awarded
 * before they existed, and a month is the period the commitments themselves are
 * written in.
 *
 * <p>Ties break on rank, then on vendor identifier. Both fallbacks are there to
 * make the choice <em>deterministic</em>: the first award of a month has every
 * vendor on zero, and a strategy that picked arbitrarily would give different
 * answers to the same question and be untestable.
 */
@Component
public class RoundRobinStrategy implements SourcingStrategy {

    private final VendorAwardTally tally;

    public RoundRobinStrategy(VendorAwardTally tally) {
        this.tally = tally;
    }

    @Override
    public RoutingGuide.Strategy handles() {
        return RoutingGuide.Strategy.ROUND_ROBIN;
    }

    @Override
    public Optional<RoutingGuideEntry> nextCandidate(List<RoutingGuideEntry> candidates,
                                                     List<UUID> alreadyOffered,
                                                     SourcingContext context) {
        Set<UUID> asked = Set.copyOf(alreadyOffered);
        Map<UUID, Integer> awardsThisMonth =
                tally.countsFor(context.tenantId(), context.guideId(), context.awardOn());

        return candidates.stream()
                .filter(entry -> !asked.contains(entry.vendorPartnerId()))
                .min(Comparator
                        .comparingInt((RoutingGuideEntry e) ->
                                awardsThisMonth.getOrDefault(e.vendorPartnerId(), 0))
                        .thenComparingInt(RoutingGuideEntry::rankNo)
                        .thenComparing(e -> e.vendorPartnerId().toString()));
    }
}
