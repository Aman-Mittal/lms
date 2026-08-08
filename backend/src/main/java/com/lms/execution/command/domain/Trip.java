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
package com.lms.execution.command.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
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
 * One vehicle movement against one load (vision document 3.6.1).
 *
 * <p>The state machine is the centre of the platform. Geofence crossings drive
 * it automatically once telematics is wired in, an invoice is priced from the
 * timestamps it records, and dispatch is the single point where the compliance
 * rules of 3.2.2 become a physical fact. A transition table that let a trip
 * skip a state would not fail loudly -- it would produce a trip that was
 * delivered without ever having been loaded, and a detention charge computed
 * from a null.
 *
 * <p>No Spring StateMachine dependency. The rule is a map from state to allowed
 * successors; a framework for that would be a reflection-heavy dependency in a
 * native image to express fifteen lines.
 */
@Table("trip")
public record Trip(
        @Id UUID id,
        UUID tenantId,
        UUID orgUnitId,
        UUID loadId,
        String tripNo,
        UUID vendorPartnerId,
        UUID vehicleId,
        UUID driverId,
        TripStatus status,
        UUID originTerminalId,
        UUID destinationTerminalId,
        Instant plannedStartAt,
        Instant gateInAt,
        Instant loadedAt,
        Instant dispatchedAt,
        Instant arrivedAt,
        Instant completedAt,
        BigDecimal tareWeightKg,
        BigDecimal grossWeightKg,
        BigDecimal payloadWeightKg,
        @Version Long version,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * The eight states of 3.6.1, plus CANCELLED.
     *
     * <p>ASSIGNED and AT_ORIGIN are separate on purpose, and so are AT_ORIGIN
     * and LOADED. The gaps between them are where vehicles actually wait, and
     * collapsing them would make the dwell that detention is billed on
     * unmeasurable.
     */
    public enum TripStatus {
        /** Raised against an awarded load; no vehicle nominated yet. */
        PLANNED,
        /** A vehicle and driver are nominated. */
        ASSIGNED,
        /** Gate-in recorded at the origin; the clock for detention starts here. */
        AT_ORIGIN,
        /** Loading finished and the gross weight taken. */
        LOADED,
        /** Gate-out recorded; the compliance gate of 3.6.3 has been passed. */
        DISPATCHED,
        /** Moving. Entered automatically on leaving the origin geofence. */
        IN_TRANSIT,
        /** Inside the destination geofence. */
        AT_DESTINATION,
        /** Proof of delivery accepted. */
        COMPLETED,
        CANCELLED
    }

    private static final Map<TripStatus, Set<TripStatus>> TRANSITIONS =
            new EnumMap<>(TripStatus.class);

    static {
        TRANSITIONS.put(TripStatus.PLANNED,
                EnumSet.of(TripStatus.ASSIGNED, TripStatus.CANCELLED));
        // Back to PLANNED when a nominated vehicle breaks down before it
        // reaches the yard: the trip is still needed, the lorry is not.
        TRANSITIONS.put(TripStatus.ASSIGNED,
                EnumSet.of(TripStatus.AT_ORIGIN, TripStatus.PLANNED, TripStatus.CANCELLED));
        TRANSITIONS.put(TripStatus.AT_ORIGIN,
                EnumSet.of(TripStatus.LOADED, TripStatus.CANCELLED));
        TRANSITIONS.put(TripStatus.LOADED,
                EnumSet.of(TripStatus.DISPATCHED, TripStatus.CANCELLED));
        // Once a lorry has left the yard, cancelling is not a state change --
        // it is a new movement to bring the freight back, and that is its own
        // trip. Allowing CANCELLED here would leave freight on the road with no
        // open trip against it.
        TRANSITIONS.put(TripStatus.DISPATCHED, EnumSet.of(TripStatus.IN_TRANSIT));
        TRANSITIONS.put(TripStatus.IN_TRANSIT, EnumSet.of(TripStatus.AT_DESTINATION));
        // Back to IN_TRANSIT: a vehicle that clips the edge of a destination
        // geofence and drives on has not arrived, and telematics will say so.
        TRANSITIONS.put(TripStatus.AT_DESTINATION,
                EnumSet.of(TripStatus.COMPLETED, TripStatus.IN_TRANSIT));
        TRANSITIONS.put(TripStatus.COMPLETED, EnumSet.noneOf(TripStatus.class));
        TRANSITIONS.put(TripStatus.CANCELLED, EnumSet.noneOf(TripStatus.class));
    }

    public static Trip raise(UUID id, UUID tenantId, UUID orgUnitId, UUID loadId, String tripNo,
                             UUID vendorPartnerId, UUID originTerminalId,
                             UUID destinationTerminalId, Instant plannedStartAt) {
        if (tripNo == null || tripNo.isBlank()) {
            throw new IllegalArgumentException("Trip number is mandatory");
        }
        if (originTerminalId == null) {
            throw new IllegalArgumentException("A trip must start somewhere");
        }
        return new Trip(id, tenantId, orgUnitId, loadId, tripNo, vendorPartnerId,
                null, null, TripStatus.PLANNED, originTerminalId, destinationTerminalId,
                plannedStartAt, null, null, null, null, null,
                null, null, null, null, Instant.now(), Instant.now());
    }

    // ------------------------------------------------------------ milestones

    /** Nominates the vehicle and driver that will run the trip. */
    public Trip assign(UUID vehicle, UUID driver) {
        if (vehicle == null) {
            throw new IllegalArgumentException("A trip cannot be assigned without a vehicle");
        }
        Trip moved = transitionTo(TripStatus.ASSIGNED);
        return moved.with(builder -> builder.vehicleId = vehicle, builder -> builder.driverId = driver);
    }

    public Trip gateIn(Instant at) {
        Trip moved = transitionTo(TripStatus.AT_ORIGIN);
        return moved.with(builder -> builder.gateInAt = at);
    }

    public Trip markLoaded(Instant at) {
        Trip moved = transitionTo(TripStatus.LOADED);
        return moved.with(builder -> builder.loadedAt = at);
    }

    public Trip dispatch(Instant at) {
        Trip moved = transitionTo(TripStatus.DISPATCHED);
        return moved.with(builder -> builder.dispatchedAt = at);
    }

    public Trip beginTransit() {
        return transitionTo(TripStatus.IN_TRANSIT);
    }

    public Trip arrive(Instant at) {
        Trip moved = transitionTo(TripStatus.AT_DESTINATION);
        return moved.with(builder -> builder.arrivedAt = at);
    }

    public Trip complete(Instant at) {
        Trip moved = transitionTo(TripStatus.COMPLETED);
        return moved.with(builder -> builder.completedAt = at);
    }

    public Trip cancel() {
        return transitionTo(TripStatus.CANCELLED);
    }

    // ------------------------------------------------------------ weighbridge

    /**
     * Records a tare or gross reading, computing payload once both exist.
     *
     * <p>Payload is stored rather than derived on read. It is the number a
     * shortage dispute is argued over, and it must not change if somebody
     * corrects a reading months later -- the correction should be visible as a
     * correction, not applied retrospectively to a settled invoice.
     */
    public Trip recordWeighing(BigDecimal tare, BigDecimal gross) {
        BigDecimal newTare = tare != null ? tare : tareWeightKg;
        BigDecimal newGross = gross != null ? gross : grossWeightKg;

        if (newTare != null && newGross != null && newGross.compareTo(newTare) <= 0) {
            throw new BusinessRuleViolationException("weighbridge-implausible",
                    "Gross weight " + newGross + " kg is not greater than tare " + newTare
                            + " kg; one of the readings is wrong");
        }

        BigDecimal payload = newTare != null && newGross != null
                ? newGross.subtract(newTare).setScale(3, RoundingMode.HALF_UP)
                : null;

        return with(builder -> {
            builder.tareWeightKg = newTare;
            builder.grossWeightKg = newGross;
            builder.payloadWeightKg = payload;
        });
    }

    public boolean hasBothWeighings() {
        return tareWeightKg != null && grossWeightKg != null;
    }

    /**
     * How far the weighed payload differs from what the load was planned at, as
     * a percentage.
     *
     * <p>The cross-check of 3.6.2. A real discrepancy means freight that was
     * not loaded, freight that was not on the manifest, or a scale that needs
     * calibrating -- all three matter, and all three are invisible without
     * this comparison.
     */
    public BigDecimal payloadVariancePct(BigDecimal plannedWeightKg) {
        if (payloadWeightKg == null || plannedWeightKg == null || plannedWeightKg.signum() == 0) {
            return null;
        }
        return payloadWeightKg.subtract(plannedWeightKg)
                .abs()
                .multiply(BigDecimal.valueOf(100))
                .divide(plannedWeightKg, 2, RoundingMode.HALF_UP);
    }

    /**
     * Time between arriving at the origin and leaving it -- what detention is
     * billed on (3.9.1).
     *
     * <p>Null until the trip has been dispatched, because the dwell is not over
     * until the lorry leaves.
     */
    public Duration originDwell() {
        if (gateInAt == null || dispatchedAt == null) {
            return null;
        }
        return Duration.between(gateInAt, dispatchedAt);
    }

    /** Whether the trip has left the origin and not yet finished. */
    public boolean isOnRoad() {
        return status == TripStatus.DISPATCHED
                || status == TripStatus.IN_TRANSIT
                || status == TripStatus.AT_DESTINATION;
    }

    // --------------------------------------------------------------- machine

    public Trip transitionTo(TripStatus target) {
        if (status == target) {
            return this;
        }
        if (!TRANSITIONS.getOrDefault(status, Set.of()).contains(target)) {
            throw new BusinessRuleViolationException("trip-illegal-transition",
                    "Trip " + tripNo + " cannot move from " + status + " to " + target);
        }
        return with(builder -> builder.status = target);
    }

    // ---------------------------------------------------------------- copying

    /**
     * Copies the record with a few fields changed.
     *
     * <p>A record with twenty-three components makes every canonical
     * constructor call a counting exercise, and a mis-ordered pair of
     * {@code Instant}s compiles perfectly. This mutable holder is used only
     * inside the aggregate; nothing escapes it.
     */
    @SafeVarargs
    private Trip with(java.util.function.Consumer<Fields>... mutations) {
        Fields fields = new Fields(this);
        for (java.util.function.Consumer<Fields> mutation : mutations) {
            mutation.accept(fields);
        }
        return new Trip(id, tenantId, orgUnitId, loadId, tripNo, fields.vendorPartnerId,
                fields.vehicleId, fields.driverId, fields.status, originTerminalId,
                fields.destinationTerminalId, plannedStartAt, fields.gateInAt, fields.loadedAt,
                fields.dispatchedAt, fields.arrivedAt, fields.completedAt,
                fields.tareWeightKg, fields.grossWeightKg, fields.payloadWeightKg,
                version, createdAt, Instant.now());
    }

    private static final class Fields {
        private UUID vendorPartnerId;
        private UUID vehicleId;
        private UUID driverId;
        private TripStatus status;
        private UUID destinationTerminalId;
        private Instant gateInAt;
        private Instant loadedAt;
        private Instant dispatchedAt;
        private Instant arrivedAt;
        private Instant completedAt;
        private BigDecimal tareWeightKg;
        private BigDecimal grossWeightKg;
        private BigDecimal payloadWeightKg;

        private Fields(Trip trip) {
            this.vendorPartnerId = trip.vendorPartnerId;
            this.vehicleId = trip.vehicleId;
            this.driverId = trip.driverId;
            this.status = trip.status;
            this.destinationTerminalId = trip.destinationTerminalId;
            this.gateInAt = trip.gateInAt;
            this.loadedAt = trip.loadedAt;
            this.dispatchedAt = trip.dispatchedAt;
            this.arrivedAt = trip.arrivedAt;
            this.completedAt = trip.completedAt;
            this.tareWeightKg = trip.tareWeightKg;
            this.grossWeightKg = trip.grossWeightKg;
            this.payloadWeightKg = trip.payloadWeightKg;
        }
    }
}
