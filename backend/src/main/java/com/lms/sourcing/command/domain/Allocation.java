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
package com.lms.sourcing.command.domain;

import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * The attempt to find a vendor for one load (vision document 3.5.2).
 *
 * <p>One per load, enforced by a unique constraint. Two live allocations for
 * the same load is how one trip gets sold to two vendors, and it is not
 * recoverable after the fact -- both of them will have committed a lorry.
 */
@Table("allocation")
public record Allocation(
        @Id UUID id,
        UUID tenantId,
        UUID loadId,
        UUID guideId,
        RoutingGuide.Strategy strategy,
        AllocationStatus status,
        Integer currentRank,
        UUID awardedVendorPartnerId,
        Instant awardedAt,
        @Version Long version,
        Instant createdAt,
        Instant updatedAt) {

    public enum AllocationStatus {
        /** Out with a vendor, waiting for an answer. */
        OFFERED,
        /** A vendor has accepted. */
        AWARDED,
        /**
         * Every eligible vendor has declined or let the offer lapse.
         *
         * <p>Terminal, and deliberately not "try again from the top". A load
         * nobody will take is a commercial problem -- the rate is wrong, or the
         * lane is unserviceable today -- and quietly re-offering it would spin
         * against unwilling vendors while the freight sits.
         */
        EXHAUSTED,
        CANCELLED
    }

    private static final Map<AllocationStatus, Set<AllocationStatus>> TRANSITIONS =
            new EnumMap<>(AllocationStatus.class);

    static {
        TRANSITIONS.put(AllocationStatus.OFFERED, EnumSet.of(
                AllocationStatus.AWARDED, AllocationStatus.EXHAUSTED, AllocationStatus.CANCELLED));
        TRANSITIONS.put(AllocationStatus.AWARDED, EnumSet.of(AllocationStatus.CANCELLED));
        // Terminal: a human decides what happens to a load nobody wanted.
        TRANSITIONS.put(AllocationStatus.EXHAUSTED, EnumSet.noneOf(AllocationStatus.class));
        TRANSITIONS.put(AllocationStatus.CANCELLED, EnumSet.noneOf(AllocationStatus.class));
    }

    public static Allocation open(UUID id, UUID tenantId, UUID loadId, UUID guideId,
                                  RoutingGuide.Strategy strategy, int firstRank) {
        return new Allocation(id, tenantId, loadId, guideId, strategy,
                AllocationStatus.OFFERED, firstRank, null, null,
                null, Instant.now(), Instant.now());
    }

    public boolean isOpen() {
        return status == AllocationStatus.OFFERED;
    }

    /** Moves the offer down to the next vendor in the cascade. */
    public Allocation cascadeTo(int nextRank) {
        requireOpen();
        return new Allocation(id, tenantId, loadId, guideId, strategy, AllocationStatus.OFFERED,
                nextRank, null, null, version, createdAt, Instant.now());
    }

    public Allocation awardTo(UUID vendorPartnerId) {
        requireOpen();
        return new Allocation(id, tenantId, loadId, guideId, strategy, AllocationStatus.AWARDED,
                currentRank, vendorPartnerId, Instant.now(), version, createdAt, Instant.now());
    }

    public Allocation exhaust() {
        requireOpen();
        return new Allocation(id, tenantId, loadId, guideId, strategy, AllocationStatus.EXHAUSTED,
                currentRank, null, null, version, createdAt, Instant.now());
    }

    public Allocation cancel() {
        return transitionTo(AllocationStatus.CANCELLED);
    }

    private Allocation transitionTo(AllocationStatus target) {
        if (status == target) {
            return this;
        }
        if (!TRANSITIONS.getOrDefault(status, Set.of()).contains(target)) {
            throw new BusinessRuleViolationException("allocation-illegal-transition",
                    "Allocation for load " + loadId + " cannot move from " + status + " to " + target);
        }
        return new Allocation(id, tenantId, loadId, guideId, strategy, target, currentRank,
                awardedVendorPartnerId, awardedAt, version, createdAt, Instant.now());
    }

    private void requireOpen() {
        if (status != AllocationStatus.OFFERED) {
            throw new BusinessRuleViolationException("allocation-not-open",
                    "Allocation for load " + loadId + " is " + status + " and is no longer running");
        }
    }
}
