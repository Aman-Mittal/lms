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
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * What happened at one supplier on one circuit.
 *
 * <p>Walks PENDING, ARRIVED, DEPARTED -- or PENDING to SKIPPED. Each step is a
 * guard as much as a transition: a geofence crossing delivered twice, or a
 * vehicle sitting on a fence line, finds the stop already past that step and
 * changes nothing.
 */
@Table("circuit_run_stop")
public record CircuitRunStop(
        @Id UUID id,
        UUID tenantId,
        UUID runId,
        int sequence,
        UUID terminalId,
        Instant windowOpensAt,
        Instant windowClosesAt,
        int plannedDwellMin,
        Status status,
        Instant arrivedAt,
        Instant departedAt,
        CircuitRun.Source arrivalSource,
        Integer minutesLate,
        BigDecimal pickedUpKg,
        String skipReason,
        @Version Long version,
        Instant updatedAt) {

    public enum Status {
        PENDING,
        ARRIVED,
        DEPARTED,
        SKIPPED
    }

    /** A stop as planned, its window resolved against the run's departure. */
    public static CircuitRunStop plan(UUID tenantId, UUID runId, MilkRunRouteStop stop,
                                      Instant plannedDeparture) {
        return new CircuitRunStop(UUID.randomUUID(), tenantId, runId, stop.sequence(),
                stop.terminalId(),
                plannedDeparture.plus(Duration.ofMinutes(stop.windowOpenMin())),
                plannedDeparture.plus(Duration.ofMinutes(stop.windowCloseMin())),
                stop.plannedDwellMin(), Status.PENDING, null, null, null, null, null, null,
                null, Instant.now());
    }

    public boolean isPending() {
        return status == Status.PENDING;
    }

    /** Collected from, or at least reached -- what the per-stop fee pays for. */
    public boolean wasVisited() {
        return status == Status.ARRIVED || status == Status.DEPARTED;
    }

    /**
     * The vehicle reached the supplier.
     *
     * <p>Lateness is measured against the close of the window, in whole
     * minutes, rounded up: a lorry thirty seconds late was late, and a figure
     * of zero would say otherwise.
     */
    public CircuitRunStop arrive(Instant at, CircuitRun.Source source) {
        if (status != Status.PENDING) {
            throw refuse("arrive at");
        }
        int late = 0;
        if (at.isAfter(windowClosesAt)) {
            long seconds = Duration.between(windowClosesAt, at).toSeconds();
            late = (int) ((seconds + 59) / 60);
        }
        return new CircuitRunStop(id, tenantId, runId, sequence, terminalId, windowOpensAt,
                windowClosesAt, plannedDwellMin, Status.ARRIVED, at, null, source, late,
                pickedUpKg, null, version, Instant.now());
    }

    public CircuitRunStop depart(Instant at) {
        if (status != Status.ARRIVED) {
            throw refuse("depart from");
        }
        if (at.isBefore(arrivedAt)) {
            throw new BusinessRuleViolationException("circuit-stop-departs-before-arrival",
                    "Stop " + sequence + " was reached at " + arrivedAt
                            + " and cannot have been left at " + at);
        }
        return new CircuitRunStop(id, tenantId, runId, sequence, terminalId, windowOpensAt,
                windowClosesAt, plannedDwellMin, Status.DEPARTED, arrivedAt, at, arrivalSource,
                minutesLate, pickedUpKg, null, version, Instant.now());
    }

    /**
     * Records what was collected.
     *
     * <p>Replaces rather than adds, so a driver correcting a figure, or a
     * retried request, leaves the right number behind.
     */
    public CircuitRunStop pickUp(BigDecimal quantityKg) {
        if (!wasVisited()) {
            throw refuse("record a pickup at");
        }
        if (quantityKg == null || quantityKg.signum() < 0) {
            throw new BusinessRuleViolationException("circuit-pickup-invalid",
                    "A pickup at stop " + sequence + " needs a quantity of zero or more");
        }
        return new CircuitRunStop(id, tenantId, runId, sequence, terminalId, windowOpensAt,
                windowClosesAt, plannedDwellMin, status, arrivedAt, departedAt, arrivalSource,
                minutesLate, quantityKg, null, version, Instant.now());
    }

    public CircuitRunStop skip(String reason) {
        if (status != Status.PENDING) {
            throw refuse("skip");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleViolationException("circuit-skip-not-explained",
                    "Skipping stop " + sequence + " needs a reason the supplier can be told");
        }
        return new CircuitRunStop(id, tenantId, runId, sequence, terminalId, windowOpensAt,
                windowClosesAt, plannedDwellMin, Status.SKIPPED, null, null, null, null, null,
                reason.strip(), version, Instant.now());
    }

    private BusinessRuleViolationException refuse(String what) {
        return new BusinessRuleViolationException("circuit-stop-illegal-transition",
                "Cannot " + what + " stop " + sequence + ": it is " + status);
    }
}
