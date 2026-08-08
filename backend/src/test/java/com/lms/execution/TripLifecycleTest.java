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
package com.lms.execution;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.lms.execution.command.domain.Trip;
import com.lms.execution.command.domain.Trip.TripStatus;
import com.lms.shared.error.BusinessRuleViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The trip state machine of vision document 3.6.1, and the weighbridge
 * arithmetic that dispatch is gated on.
 *
 * <p>Worth testing to this depth because the machine is what telematics will
 * drive automatically. A transition that is wrong here does not throw in
 * production -- it produces a trip delivered without ever having been loaded,
 * and a detention charge computed from a null.
 */
class TripLifecycleTest {

    private static final Instant T0 = Instant.parse("2026-08-07T06:00:00Z");

    private static Trip planned() {
        return Trip.raise(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "TRIP-1", UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), T0);
    }

    /** A trip taken as far as the gate, weighed and ready to leave. */
    private static Trip readyToDispatch() {
        return planned()
                .assign(UUID.randomUUID(), UUID.randomUUID())
                .gateIn(T0.plusSeconds(1800))
                .recordWeighing(new BigDecimal("6000"), null)
                .recordWeighing(null, new BigDecimal("14000"))
                .markLoaded(T0.plusSeconds(5400));
    }

    @Nested
    @DisplayName("the state machine")
    class Machine {

        @Test
        @DisplayName("walks the happy path through all eight states")
        void happyPath() {
            Trip completed = readyToDispatch()
                    .dispatch(T0.plusSeconds(7200))
                    .beginTransit()
                    .arrive(T0.plusSeconds(20_000))
                    .complete(T0.plusSeconds(21_000));

            assertThat(completed.status()).isEqualTo(TripStatus.COMPLETED);
            assertThat(completed.dispatchedAt()).isEqualTo(T0.plusSeconds(7200));
            assertThat(completed.completedAt()).isEqualTo(T0.plusSeconds(21_000));
        }

        @Test
        @DisplayName("refuses to skip a state")
        void cannotSkipStates() {
            // The failure this prevents is not a crash. It is a trip that was
            // delivered without ever having been loaded, which every downstream
            // calculation then treats as real.
            assertThatThrownBy(() -> planned().transitionTo(TripStatus.DISPATCHED))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("cannot move from PLANNED to DISPATCHED");

            assertThatThrownBy(() -> planned().transitionTo(TripStatus.COMPLETED))
                    .isInstanceOf(BusinessRuleViolationException.class);
        }

        @Test
        @DisplayName("a nominated vehicle can be swapped before it reaches the yard")
        void assignedBackToPlanned() {
            // A lorry breaking down before it arrives is ordinary. The trip is
            // still needed; the vehicle is not.
            Trip released = planned()
                    .assign(UUID.randomUUID(), UUID.randomUUID())
                    .transitionTo(TripStatus.PLANNED);

            assertThat(released.status()).isEqualTo(TripStatus.PLANNED);
        }

        @Test
        @DisplayName("a dispatched trip cannot be cancelled")
        void cannotCancelOnRoad() {
            // Bringing freight back is a new movement with its own trip, not a
            // status change. Allowing CANCELLED here would leave a lorry on the
            // road with no open trip against it.
            Trip onRoad = readyToDispatch().dispatch(T0.plusSeconds(7200));

            assertThatThrownBy(() -> onRoad.cancel())
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("cannot move from DISPATCHED to CANCELLED");
        }

        @Test
        @DisplayName("a vehicle that clips the destination and drives on has not arrived")
        void arrivalCanBeUndone() {
            Trip clipped = readyToDispatch()
                    .dispatch(T0.plusSeconds(7200))
                    .beginTransit()
                    .arrive(T0.plusSeconds(9000))
                    .transitionTo(TripStatus.IN_TRANSIT);

            assertThat(clipped.status()).isEqualTo(TripStatus.IN_TRANSIT);
        }

        @Test
        @DisplayName("completed and cancelled are terminal")
        void terminalStates() {
            Trip cancelled = planned().cancel();
            Trip done = readyToDispatch()
                    .dispatch(T0.plusSeconds(7200))
                    .beginTransit()
                    .arrive(T0.plusSeconds(20_000))
                    .complete(T0.plusSeconds(21_000));

            assertThatThrownBy(() -> cancelled.transitionTo(TripStatus.ASSIGNED))
                    .isInstanceOf(BusinessRuleViolationException.class);
            assertThatThrownBy(() -> done.transitionTo(TripStatus.IN_TRANSIT))
                    .isInstanceOf(BusinessRuleViolationException.class);
        }

        @Test
        @DisplayName("re-declaring the current state is a no-op")
        void selfTransitionIsIdempotent() {
            // Telematics will report the same geofence on consecutive pings.
            // Treating that as an illegal self-transition would make ordinary
            // GPS noise an error.
            Trip onRoad = readyToDispatch().dispatch(T0.plusSeconds(7200)).beginTransit();

            assertThat(onRoad.transitionTo(TripStatus.IN_TRANSIT).status())
                    .isEqualTo(TripStatus.IN_TRANSIT);
        }

        @Test
        @DisplayName("a trip cannot be assigned without a vehicle")
        void assignmentNeedsAVehicle() {
            assertThatThrownBy(() -> planned().assign(null, UUID.randomUUID()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("weighbridge")
    class Weighbridge {

        @Test
        @DisplayName("payload is gross less tare")
        void payloadIsDifference() {
            Trip weighed = planned()
                    .recordWeighing(new BigDecimal("6000"), null)
                    .recordWeighing(null, new BigDecimal("14500"));

            assertThat(weighed.payloadWeightKg()).isEqualByComparingTo("8500");
            assertThat(weighed.hasBothWeighings()).isTrue();
        }

        @Test
        @DisplayName("payload is unknown until both readings exist")
        void partialWeighingHasNoPayload() {
            Trip tareOnly = planned().recordWeighing(new BigDecimal("6000"), null);

            assertThat(tareOnly.payloadWeightKg()).isNull();
            assertThat(tareOnly.hasBothWeighings()).isFalse();
        }

        @Test
        @DisplayName("a gross no heavier than the tare is refused")
        void implausibleReadings() {
            // Physically impossible, and the likely cause is the two readings
            // having been entered the wrong way round -- which would otherwise
            // yield a negative payload that every downstream sum accepts.
            Trip tare = planned().recordWeighing(new BigDecimal("14000"), null);

            assertThatThrownBy(() -> tare.recordWeighing(null, new BigDecimal("6000")))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("one of the readings is wrong");
        }

        @Test
        @DisplayName("variance against the planned weight is a percentage of the plan")
        void variance() {
            Trip weighed = planned()
                    .recordWeighing(new BigDecimal("6000"), null)
                    .recordWeighing(null, new BigDecimal("14500"));

            // 8500 weighed against 8000 planned is 6.25% over.
            assertThat(weighed.payloadVariancePct(new BigDecimal("8000")))
                    .isEqualByComparingTo("6.25");
            // Under by the same amount reports the same magnitude: a shortage
            // and an excess are both discrepancies worth stopping for.
            assertThat(weighed.payloadVariancePct(new BigDecimal("9000")))
                    .isEqualByComparingTo("5.56");
        }

        @Test
        @DisplayName("variance is unknown rather than zero when nothing was weighed")
        void varianceWithoutReadings() {
            // Zero would read as "matches the plan exactly", which is the
            // opposite of the truth and would wave an unweighed load through.
            assertThat(planned().payloadVariancePct(new BigDecimal("8000"))).isNull();
        }
    }

    @Nested
    @DisplayName("detention dwell")
    class Dwell {

        @Test
        @DisplayName("is measured from gate-in to departure")
        void dwellSpansTheYardVisit() {
            Trip dispatched = readyToDispatch().dispatch(T0.plusSeconds(9000));

            // Gate-in at T+1800, out at T+9000: two hours in the yard.
            assertThat(dispatched.originDwell().toMinutes()).isEqualTo(120);
        }

        @Test
        @DisplayName("is unknown while the vehicle is still in the yard")
        void dwellIsOpenUntilDeparture() {
            Trip waiting = planned()
                    .assign(UUID.randomUUID(), UUID.randomUUID())
                    .gateIn(T0.plusSeconds(1800));

            // Not zero. A vehicle that has been waiting three hours and has not
            // left has not accrued zero detention -- the figure is simply not
            // final, and billing it as zero would be a real refund.
            assertThat(waiting.originDwell()).isNull();
        }
    }
}
