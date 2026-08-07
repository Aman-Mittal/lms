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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One vendor's place in a routing guide: their rank, the rate agreed with them,
 * and how long they have to answer an offer.
 */
@Table("routing_guide_entry")
public record RoutingGuideEntry(
        @Id UUID id,
        UUID tenantId,
        UUID guideId,
        int rankNo,
        UUID vendorPartnerId,
        int responseSlaMinutes,
        BigDecimal agreedRate,
        @Version Long version,
        Instant createdAt) {

    public static RoutingGuideEntry at(UUID id, UUID tenantId, UUID guideId, int rankNo,
                                       UUID vendorPartnerId, int responseSlaMinutes,
                                       BigDecimal agreedRate) {
        if (rankNo <= 0) {
            throw new IllegalArgumentException("Rank must be 1 or greater");
        }
        if (responseSlaMinutes <= 0) {
            // A zero-minute SLA would time an offer out in the same transaction
            // that made it, so the cascade would run to exhaustion instantly and
            // no vendor would ever get a chance to answer.
            throw new IllegalArgumentException("Response SLA must be at least one minute");
        }
        return new RoutingGuideEntry(id, tenantId, guideId, rankNo, vendorPartnerId,
                responseSlaMinutes, agreedRate, null, Instant.now());
    }

    /** When an offer made now would lapse. */
    public Instant deadlineFrom(Instant offeredAt) {
        return offeredAt.plus(Duration.ofMinutes(responseSlaMinutes));
    }
}
