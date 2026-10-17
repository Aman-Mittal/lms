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
package com.lms.milkrun.command.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One day's execution of one milk-run route.
 *
 * <p>Generated ahead of time from the route version in force on the day, with
 * the price copied in: a run is charged what the plan said on the day it ran,
 * not what it says when somebody looks at the bill.
 *
 * <p>Inserted by the generator with plain SQL -- that is how generation stays
 * idempotent under concurrent wake-ups -- and loaded and saved through the
 * repository afterwards.
 */
@Table("circuit_run")
public record CircuitRun(
        @Id UUID id,
        UUID tenantId,
        UUID routeId,
        String routeCode,
        LocalDate runDate,
        Instant plannedDeparture,
        UUID plantTerminalId,
        Status status,
        UUID tripId,
        String tripNo,
        Instant startedAt,
        Instant completedAt,
        Source completionSource,
        BigDecimal runRate,
        BigDecimal perStopFee,
        String currency,
        Integer stopsVisited,
        Integer stopsSkipped,
        BigDecimal chargeAmount,
        @Version Long version,
        Instant createdAt,
        Instant updatedAt) {

    public enum Status {
        /** Generated; no vehicle yet. */
        PLANNED,
        /** Carried by a trip; stops are being recorded. */
        IN_PROGRESS,
        /** Reached the plant, and priced. Terminal. */
        COMPLETED
    }

    /** What recorded a milestone. */
    public enum Source {
        GEOFENCE,
        MANUAL
    }

    /**
     * Hands the circuit to an execution trip.
     *
     * <p>From then on geofence crossings for that trip are this run's stops.
     */
    public CircuitRun carriedBy(UUID trip, String tripNumber, Instant at) {
        if (status != Status.PLANNED) {
            throw new BusinessRuleViolationException("circuit-run-already-started",
                    "Circuit " + routeCode + " on " + runDate + " is " + status
                            + " and already carried by trip " + tripNo);
        }
        return new CircuitRun(id, tenantId, routeId, routeCode, runDate, plannedDeparture,
                plantTerminalId, Status.IN_PROGRESS, trip, tripNumber, at, completedAt,
                completionSource, runRate, perStopFee, currency, stopsVisited, stopsSkipped,
                chargeAmount, version, createdAt, at);
    }

    /**
     * The circuit reached the plant, and is priced.
     *
     * <p>A fixed rate for running it plus a fee per supplier actually visited.
     * A skipped supplier is not charged for: the carrier did not do that part
     * of the work.
     */
    public CircuitRun complete(int visited, int skipped, Source source, Instant at) {
        if (status != Status.IN_PROGRESS) {
            throw new BusinessRuleViolationException("circuit-run-not-in-progress",
                    "Circuit " + routeCode + " on " + runDate + " is " + status
                            + "; only a circuit under way can reach the plant");
        }
        BigDecimal charge = runRate.add(perStopFee.multiply(BigDecimal.valueOf(visited)))
                .setScale(2, RoundingMode.HALF_UP);
        return new CircuitRun(id, tenantId, routeId, routeCode, runDate, plannedDeparture,
                plantTerminalId, Status.COMPLETED, tripId, tripNo, startedAt, at, source,
                runRate, perStopFee, currency, visited, skipped, charge, version, createdAt, at);
    }

    public boolean isInProgress() {
        return status == Status.IN_PROGRESS;
    }
}
