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

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * The standing arrangement for a lane: who is contracted to move freight from
 * this origin to this destination on this class of vehicle (vision document
 * 3.5.1).
 *
 * <p>Entries are a separate table rather than a nested collection. Under Spring
 * Data JDBC a nested collection is deleted and reinserted on every save of the
 * parent, which would rewrite every rank each time a guide is touched and
 * invalidate the entry identifiers the allocation trail refers to.
 */
@Table("routing_guide")
public record RoutingGuide(
        @Id UUID id,
        UUID tenantId,
        UUID orgUnitId,
        UUID originTerminalId,
        UUID destinationTerminalId,
        String vehicleType,
        Strategy strategy,
        boolean active,
        @Version Long version,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * How the vendor is chosen.
     *
     * <p>Two today. The interface they sit behind is the point: the vision
     * document's reverse auction is deferred, and adding it must not mean
     * touching the code that asks for a vendor.
     */
    public enum Strategy {
        /** Walk the ranks in order, cascading on rejection or timeout. */
        CONTRACTUAL,
        /** Spread awards evenly across eligible vendors within a calendar month. */
        ROUND_ROBIN
    }

    public static RoutingGuide define(UUID id, UUID tenantId, UUID orgUnitId,
                                      UUID originTerminalId, UUID destinationTerminalId,
                                      String vehicleType, Strategy strategy) {
        if (originTerminalId == null || destinationTerminalId == null) {
            throw new IllegalArgumentException("A lane needs both an origin and a destination");
        }
        if (originTerminalId.equals(destinationTerminalId)) {
            throw new IllegalArgumentException(
                    "A lane cannot start and end at the same terminal");
        }
        if (vehicleType == null || vehicleType.isBlank()) {
            throw new IllegalArgumentException("A routing guide is specific to a vehicle type");
        }
        return new RoutingGuide(id, tenantId, orgUnitId, originTerminalId, destinationTerminalId,
                vehicleType, strategy == null ? Strategy.CONTRACTUAL : strategy, true,
                null, Instant.now(), Instant.now());
    }

    /**
     * Retires a guide without deleting it.
     *
     * <p>Allocations already made refer to it, and the answer to "under what
     * arrangement did this load go out" must survive the arrangement ending.
     */
    public RoutingGuide deactivate() {
        return new RoutingGuide(id, tenantId, orgUnitId, originTerminalId, destinationTerminalId,
                vehicleType, strategy, false, version, createdAt, Instant.now());
    }
}
