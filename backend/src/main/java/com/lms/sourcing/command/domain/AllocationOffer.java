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
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One offer of a load to one vendor.
 *
 * <p>Kept rather than overwritten as the cascade descends. The trail is what
 * answers "why did this load go to the rank-three vendor at twice the rate",
 * and that question is asked when the invoice arrives, long after the
 * allocation finished.
 */
@Table("allocation_offer")
public record AllocationOffer(
        @Id UUID id,
        UUID tenantId,
        UUID allocationId,
        int rankNo,
        UUID vendorPartnerId,
        Instant offeredAt,
        Instant respondsBy,
        Outcome outcome,
        Instant respondedAt,
        @Version Long version,
        Instant createdAt) {

    public enum Outcome {
        PENDING, ACCEPTED, REJECTED, TIMED_OUT, CANCELLED
    }

    public static AllocationOffer make(UUID id, UUID tenantId, UUID allocationId, int rankNo,
                                       UUID vendorPartnerId, Instant offeredAt, Instant respondsBy) {
        return new AllocationOffer(id, tenantId, allocationId, rankNo, vendorPartnerId,
                offeredAt, respondsBy, Outcome.PENDING, null, null, Instant.now());
    }

    /** Whether the deadline has passed without an answer. */
    public boolean hasLapsedBy(Instant now) {
        return outcome == Outcome.PENDING && !now.isBefore(respondsBy);
    }

    public AllocationOffer accept(Instant at) {
        return settle(Outcome.ACCEPTED, at);
    }

    public AllocationOffer reject(Instant at) {
        return settle(Outcome.REJECTED, at);
    }

    public AllocationOffer timeOut(Instant at) {
        return settle(Outcome.TIMED_OUT, at);
    }

    public AllocationOffer cancel(Instant at) {
        return settle(Outcome.CANCELLED, at);
    }

    /**
     * Records the answer.
     *
     * <p>Refuses to settle an offer twice. A vendor accepting an offer that
     * timed out thirty seconds earlier -- entirely plausible, and a race the
     * sweeper makes real -- must be told the offer has gone, not quietly
     * awarded a load that has already cascaded to somebody else.
     */
    private AllocationOffer settle(Outcome result, Instant at) {
        if (outcome != Outcome.PENDING) {
            throw new BusinessRuleViolationException("offer-already-settled",
                    "This offer was already " + outcome
                            + " and cannot now be recorded as " + result);
        }
        return new AllocationOffer(id, tenantId, allocationId, rankNo, vendorPartnerId,
                offeredAt, respondsBy, result, at, version, createdAt);
    }
}
