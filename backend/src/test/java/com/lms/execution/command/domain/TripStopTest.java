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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lms.execution.command.domain.TripStop.Draft;
import com.lms.execution.command.domain.TripStop.Kind;
import com.lms.execution.command.domain.TripStop.Source;
import com.lms.execution.command.domain.TripStop.Status;
import com.lms.shared.error.BusinessRuleViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Stop sequencing and the stop state machine (issue #223), with no database. */
class TripStopTest {

    private static final UUID ORIGIN = UUID.randomUUID();
    private static final UUID MID = UUID.randomUUID();
    private static final UUID DEST = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-11-04T06:00:00Z");

    private static TripStop stop(int seq, UUID terminal) {
        return TripStop.planned(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), seq,
                new Draft(seq == 1 ? Kind.PICKUP : Kind.DROP, terminal, null));
    }

    @Test
    @DisplayName("the stops of a trip start as the origin pickup and a drop at each place the load delivers to, destination last")
    void derivedFromTheDrops() {
        List<Draft> drafts = TripStop.derive(ORIGIN, List.of(MID, DEST), DEST);

        assertThat(drafts).extracting(Draft::kind).containsExactly(Kind.PICKUP, Kind.DROP, Kind.DROP);
        assertThat(drafts).extracting(Draft::terminalId).containsExactly(ORIGIN, MID, DEST);
    }

    @Test
    @DisplayName("a place the load drops at twice is one stop, and the trip's destination is last even when the load names it earlier")
    void derivedStopsAreDistinctAndEndAtTheDestination() {
        assertThat(TripStop.derive(ORIGIN, List.of(DEST, MID, MID), DEST))
                .extracting(Draft::terminalId).containsExactly(ORIGIN, MID, DEST);
    }

    @Test
    @DisplayName("a load with nothing aboard still gets a pickup and a drop at the trip's destination")
    void derivedFromAnEmptyLoad() {
        assertThat(TripStop.derive(ORIGIN, List.of(), DEST)).extracting(Draft::terminalId)
                .containsExactly(ORIGIN, DEST);
        assertThat(TripStop.derive(ORIGIN, null, null)).extracting(Draft::terminalId).containsExactly(ORIGIN);
    }

    @Test
    @DisplayName("a plan needs a stop with a kind and a terminal, at most fifty, and none right after another at the same terminal")
    void planValidation() {
        TripStop.requireValid(List.of(new Draft(Kind.PICKUP, ORIGIN, null), new Draft(Kind.DROP, MID, T0)));

        assertThatThrownBy(() -> TripStop.requireValid(List.of()))
                .isInstanceOf(BusinessRuleViolationException.class).extracting("code").isEqualTo("trip-stops-invalid");
        assertThatThrownBy(() -> TripStop.requireValid(List.of(new Draft(Kind.DROP, null, null))))
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> TripStop.requireValid(List.of(new Draft(Kind.DROP, MID, null),
                new Draft(Kind.CHECKPOINT, MID, null)))).isInstanceOf(BusinessRuleViolationException.class);
        List<Draft> fiftyOne = java.util.stream.IntStream.range(0, 51)
                .mapToObj(i -> new Draft(Kind.DROP, i % 2 == 0 ? MID : DEST, null)).toList();
        assertThatThrownBy(() -> TripStop.requireValid(fiftyOne)).isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    @DisplayName("PLANNED goes to ARRIVED, then DEPARTED; the last stop's departure is COMPLETED")
    void happyPath() {
        TripStop arrived = stop(2, MID).arrive(T0, Source.GEOFENCE, false);
        assertThat(arrived.status()).isEqualTo(Status.ARRIVED);
        assertThat(arrived.arrivedAt()).isEqualTo(T0);
        assertThat(arrived.arrivalSource()).isEqualTo(Source.GEOFENCE);

        TripStop departed = arrived.depart(T0.plusSeconds(600), Source.MANUAL, false);
        assertThat(departed.status()).isEqualTo(Status.DEPARTED);
        assertThat(departed.departedAt()).isEqualTo(T0.plusSeconds(600));
        assertThat(departed.completedAt()).isNull();

        TripStop completed = stop(3, DEST).arrive(T0, Source.TRIP, false).depart(T0.plusSeconds(60), Source.TRIP, true);
        assertThat(completed.status()).isEqualTo(Status.COMPLETED);
        assertThat(completed.completedAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(completed.departedAt()).isNull();
    }

    @Test
    @DisplayName("every move out of the wrong state is refused by name")
    void illegalMoves() {
        TripStop planned = stop(2, MID);
        TripStop arrived = planned.arrive(T0, Source.MANUAL, false);
        TripStop departed = arrived.depart(T0.plusSeconds(1), Source.MANUAL, false);
        TripStop skipped = planned.skip("road closed", T0);

        for (Runnable move : List.<Runnable>of(
                () -> planned.depart(T0, Source.MANUAL, false),
                () -> arrived.arrive(T0, Source.MANUAL, false),
                () -> arrived.skip("too late", T0),
                () -> departed.arrive(T0, Source.MANUAL, false),
                () -> departed.depart(T0, Source.MANUAL, false),
                () -> skipped.arrive(T0, Source.MANUAL, false),
                () -> skipped.skip("again", T0))) {
            assertThatThrownBy(move::run).isInstanceOf(BusinessRuleViolationException.class)
                    .extracting("code").isEqualTo("trip-stop-illegal-transition");
        }
    }

    @Test
    @DisplayName("a stop cannot be left before it was reached")
    void departureBeforeArrival() {
        TripStop arrived = stop(2, MID).arrive(T0, Source.MANUAL, false);

        assertThatThrownBy(() -> arrived.depart(T0.minusSeconds(1), Source.MANUAL, false))
                .isInstanceOf(BusinessRuleViolationException.class).extracting("code")
                .isEqualTo("trip-stop-time-invalid");
    }

    @Test
    @DisplayName("skipping needs a reason, and keeps it")
    void skipping() {
        TripStop planned = stop(2, MID);

        assertThatThrownBy(() -> planned.skip(" ", T0)).isInstanceOf(IllegalArgumentException.class);
        TripStop skipped = planned.skip("  customer closed  ", T0);
        assertThat(skipped.status()).isEqualTo(Status.SKIPPED);
        assertThat(skipped.skipReason()).isEqualTo("customer closed");
        assertThat(skipped.skippedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("an arrival is out of order only while an earlier stop is still PLANNED; a skipped or reached one is not waiting")
    void outOfOrder() {
        TripStop first = stop(1, ORIGIN);
        TripStop second = stop(2, MID);
        TripStop third = stop(3, DEST);

        assertThat(TripStop.arrivesOutOfOrder(List.of(first, second, third), third)).isTrue();
        assertThat(TripStop.arrivesOutOfOrder(List.of(first, second, third), first)).isFalse();

        TripStop firstDone = first.arrive(T0, Source.TRIP, false);
        TripStop secondSkipped = second.skip("not needed", T0);
        assertThat(TripStop.arrivesOutOfOrder(List.of(firstDone, secondSkipped, third), third)).isFalse();
        assertThat(TripStop.arrivesOutOfOrder(List.of(firstDone, second, third), third)).isTrue();
    }

    @Test
    @DisplayName("an out-of-order arrival is kept on the stop")
    void outOfOrderIsRecorded() {
        TripStop arrived = stop(3, DEST).arrive(T0, Source.GEOFENCE, true);

        assertThat(arrived.outOfOrder()).isTrue();
        assertThat(arrived.depart(T0, Source.GEOFENCE, true).outOfOrder()).isTrue();
    }
}
