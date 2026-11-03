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
package com.lms.multimodal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.lms.multimodal.command.domain.Itinerary;
import com.lms.multimodal.command.domain.ItineraryLeg;
import com.lms.multimodal.command.domain.ItineraryLeg.Mode;
import com.lms.multimodal.command.domain.ItineraryLeg.Status;
import com.lms.multimodal.command.domain.ItineraryLeg.UnitType;
import com.lms.shared.error.BusinessRuleViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The leg state machine and the hand-off rule, without a database.
 *
 * <p>{@code multimodal.feature} proves the rules hold end to end, through
 * real trips and the outbox; these pin the edges -- redelivery, ordering,
 * the exact instant the hand-off opens -- that a scenario would make slow
 * and noisy to state.
 */
class ItineraryLegTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ITINERARY = UUID.randomUUID();
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();
    private static final UUID CARRIER = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    @DisplayName("a rail leg walks its milestones one step at a time")
    void railWalk() {
        ItineraryLeg leg = rail(1, 0);
        leg = leg.recordMilestone(Status.PLACED, h(1), null);
        leg = leg.recordMilestone(Status.LOADED, h(2), null);
        leg = leg.recordMilestone(Status.DEPARTED, h(3), null);
        leg = leg.recordMilestone(Status.ARRIVED, h(10), null);
        leg = leg.recordMilestone(Status.RELEASED, h(11), null);

        assertThat(leg.status()).isEqualTo(Status.RELEASED);
        assertThat(leg.departedAt()).isEqualTo(h(3));
        assertThat(leg.arrivedAt()).isEqualTo(h(10));
    }

    @Test
    @DisplayName("a skipped milestone is refused")
    void skippedMilestone() {
        ItineraryLeg placed = rail(1, 0).recordMilestone(Status.PLACED, h(1), null);
        assertRefused(() -> placed.recordMilestone(Status.DEPARTED, h(2), null),
                "leg-illegal-transition");
    }

    @Test
    @DisplayName("the same milestone at the same time again is a no-op, at another time a refusal")
    void repeatedMilestone() {
        ItineraryLeg placed = rail(1, 0).recordMilestone(Status.PLACED, h(1), null);
        assertThat(placed.recordMilestone(Status.PLACED, h(1), null)).isSameAs(placed);
        assertRefused(() -> placed.recordMilestone(Status.PLACED, h(2), null),
                "leg-illegal-transition");
    }

    @Test
    @DisplayName("a milestone earlier than the one before it is refused")
    void outOfOrder() {
        ItineraryLeg placed = rail(1, 0).recordMilestone(Status.PLACED, h(5), null);
        assertRefused(() -> placed.recordMilestone(Status.LOADED, h(4), null),
                "milestone-out-of-order");
    }

    @Test
    @DisplayName("a road leg cannot be moved by terminal events")
    void roadIsNotRail() {
        assertRefused(() -> road(1, 0).recordMilestone(Status.PLACED, h(1), null), "leg-not-rail");
    }

    @Test
    @DisplayName("the hand-off opens exactly at the predecessor's arrival plus the dwell")
    void handOffBoundary() {
        ItineraryLeg first = road(1, 0).tripCompleted(h(0), h(10), null);
        ItineraryLeg loaded = rail(2, 240)
                .recordMilestone(Status.PLACED, h(9), first)
                .recordMilestone(Status.LOADED, h(11), first);

        assertRefused(() -> loaded.recordMilestone(Status.DEPARTED, h(14).minusSeconds(1), first),
                "handoff-too-early");
        assertThat(loaded.recordMilestone(Status.DEPARTED, h(14), first).status())
                .isEqualTo(Status.DEPARTED);
    }

    @Test
    @DisplayName("a train cannot leave before the predecessor has arrived at all")
    void predecessorNotArrived() {
        ItineraryLeg first = road(1, 0).tripDispatched(h(0), null);
        ItineraryLeg loaded = rail(2, 0)
                .recordMilestone(Status.PLACED, h(1), first)
                .recordMilestone(Status.LOADED, h(2), first);
        assertRefused(() -> loaded.recordMilestone(Status.DEPARTED, h(3), first),
                "handoff-predecessor-not-arrived");
    }

    @Test
    @DisplayName("trip events are idempotent, and a completion heard first still makes a whole leg")
    void roadEventsIdempotent() {
        ItineraryLeg dispatched = road(1, 0).tripDispatched(h(1), null);
        assertThat(dispatched.tripDispatched(h(1), null)).isSameAs(dispatched);

        ItineraryLeg arrived = dispatched.tripCompleted(h(1), h(8), null);
        assertThat(arrived.tripCompleted(h(1), h(8), null)).isSameAs(arrived);

        ItineraryLeg overtaken = road(1, 0).tripCompleted(h(1), h(8), null);
        assertThat(overtaken.status()).isEqualTo(Status.ARRIVED);
        assertThat(overtaken.departedAt()).isEqualTo(h(1));
        assertThat(overtaken.arrivedAt()).isEqualTo(h(8));
    }

    @Test
    @DisplayName("a road dispatch before the hand-off is recorded as an early start, not refused")
    void roadEarlyStart() {
        ItineraryLeg rail = rail(1, 0)
                .recordMilestone(Status.PLACED, h(0), null)
                .recordMilestone(Status.LOADED, h(1), null)
                .recordMilestone(Status.DEPARTED, h(2), null)
                .recordMilestone(Status.ARRIVED, h(10), null);

        assertThat(road(2, 120).tripDispatched(h(11), rail).startedEarly()).isTrue();
        assertThat(road(2, 120).tripDispatched(h(12), rail).startedEarly()).isFalse();
    }

    @Test
    @DisplayName("a late arrival marks the next leg's connection missed, once")
    void missedConnectionOnce() {
        // Leg 2 is planned to leave at hour 12 and needs four hours of transfer.
        ItineraryLeg late = road(1, 0).tripCompleted(h(0), h(9), null);
        ItineraryLeg next = rail(2, 240);
        assertThat(next.missesConnectionAfter(late)).isTrue();

        ItineraryLeg marked = next.markConnectionMissed(next.earliestStartAfter(late), h(9));
        assertThat(marked.earliestStartAt()).isEqualTo(h(13));
        assertThat(marked.markConnectionMissed(h(13), h(10))).isSameAs(marked);

        ItineraryLeg onTime = road(1, 0).tripCompleted(h(0), h(8), null);
        assertThat(next.missesConnectionAfter(onTime)).isFalse();
    }

    @Test
    @DisplayName("an itinerary's legs must meet, and its plan must leave time to transfer")
    void planShape() {
        ItineraryLeg first = road(1, 0);
        ItineraryLeg elsewhere = ItineraryLeg.plan(UUID.randomUUID(), TENANT, ITINERARY, 2,
                new ItineraryLeg.Plan(Mode.RAIL, C, A, h(12), h(20), 0, null, UnitType.WAGON, 40,
                        "RR-1", null, CARRIER, BigDecimal.TEN), T0);
        assertRefused(() -> Itinerary.plan(ITINERARY, TENANT, "MM-1", "SHP", null,
                List.of(first, elsewhere), "planner", T0), "itinerary-not-contiguous");

        ItineraryLeg tooSoon = ItineraryLeg.plan(UUID.randomUUID(), TENANT, ITINERARY, 2,
                new ItineraryLeg.Plan(Mode.RAIL, B, C, h(9), h(20), 120, null, UnitType.WAGON, 40,
                        "RR-1", null, CARRIER, BigDecimal.TEN), T0);
        assertRefused(() -> Itinerary.plan(ITINERARY, TENANT, "MM-1", "SHP", null,
                List.of(first, tooSoon), "planner", T0), "itinerary-plan-misses-connection");

        assertThat(Itinerary.plan(ITINERARY, TENANT, "MM-1", "SHP", null,
                List.of(first, rail(2, 240)), "planner", T0).currency()).isEqualTo("INR");
    }

    @Test
    @DisplayName("each mode carries what it needs and nothing it should not")
    void legShape() {
        assertRefused(() -> ItineraryLeg.plan(UUID.randomUUID(), TENANT, ITINERARY, 1,
                new ItineraryLeg.Plan(Mode.ROAD, A, B, h(0), h(8), 0, null, null, null, null,
                        null, CARRIER, BigDecimal.ONE), T0), "road-leg-needs-trip");
        assertRefused(() -> ItineraryLeg.plan(UUID.randomUUID(), TENANT, ITINERARY, 1,
                new ItineraryLeg.Plan(Mode.RAIL, A, B, h(0), h(8), 0, null, UnitType.WAGON, 0,
                        "RR", null, CARRIER, BigDecimal.ONE), T0), "rail-leg-needs-capacity");
        assertRefused(() -> ItineraryLeg.plan(UUID.randomUUID(), TENANT, ITINERARY, 1,
                new ItineraryLeg.Plan(Mode.RAIL, A, B, h(0), h(8), 0, null, UnitType.WAGON, 10,
                        " ", null, CARRIER, BigDecimal.ONE), T0), "rail-leg-needs-booking");
        assertRefused(() -> ItineraryLeg.plan(UUID.randomUUID(), TENANT, ITINERARY, 1,
                new ItineraryLeg.Plan(Mode.RAIL, A, B, h(8), h(8), 0, null, UnitType.WAGON, 10,
                        "RR", null, CARRIER, BigDecimal.ONE), T0), "leg-window-invalid");
        assertRefused(() -> ItineraryLeg.plan(UUID.randomUUID(), TENANT, ITINERARY, 1,
                new ItineraryLeg.Plan(Mode.RAIL, A, B, h(0), h(8), 0, null, UnitType.WAGON, 10,
                        "RR", null, CARRIER, new BigDecimal("-1")), T0), "leg-cost-invalid");
    }

    @Test
    @DisplayName("a rail leg may be planned without a cost, to be priced from a card once")
    void railPricedLater() {
        ItineraryLeg unpriced = ItineraryLeg.plan(UUID.randomUUID(), TENANT, ITINERARY, 1,
                new ItineraryLeg.Plan(Mode.RAIL, A, B, h(0), h(8), 0, null, UnitType.WAGON, 10,
                        "RR", null, CARRIER, null), T0);
        assertThat(unpriced.isPriced()).isFalse();

        UUID card = UUID.randomUUID();
        ItineraryLeg priced = unpriced.pricedBy(card, new BigDecimal("20000"));
        assertThat(priced.isPriced()).isTrue();
        assertThat(priced.costAmount()).isEqualByComparingTo("20000");
        assertThat(priced.railTariffId()).isEqualTo(card);

        // An explicit cost is never replaced by a card.
        assertThatThrownBy(() -> rail(1, 0).pricedBy(card, BigDecimal.ONE))
                .isInstanceOf(IllegalStateException.class);
        // A road leg has no card to fall back on.
        assertRefused(() -> ItineraryLeg.plan(UUID.randomUUID(), TENANT, ITINERARY, 1,
                new ItineraryLeg.Plan(Mode.ROAD, A, B, h(0), h(8), 0, UUID.randomUUID(), null,
                        null, null, null, CARRIER, null), T0), "leg-cost-invalid");
    }

    @Test
    @DisplayName("a predecessor is overdue only once its arrival plus dwell has passed and time has run out")
    void overduePredecessor() {
        // Leg 1 due at hour 8; leg 2 transfers in 4 hours and leaves at 12.
        ItineraryLeg first = road(1, 0);
        ItineraryLeg next = rail(2, 240);

        // Before hour 12 it is not yet late at all.
        assertThat(next.isOverdueAfter(first, h(11))).isFalse();
        // After it, arriving now would start leg 2 after 12: missed.
        assertThat(next.isOverdueAfter(first, h(12).plusSeconds(1))).isTrue();
        // Nothing to wait on for the first leg; nothing to miss once arrived.
        assertThat(first.isOverdueAfter(null, h(100))).isFalse();
        assertThat(next.isOverdueAfter(first.tripCompleted(h(0), h(30), null), h(40))).isFalse();
        // Marked once, it is never overdue again.
        ItineraryLeg marked = next.markConnectionMissed(h(17), h(13));
        assertThat(marked.isOverdueAfter(first, h(20))).isFalse();
    }

    @Test
    @DisplayName("with slack in the plan, an overdue predecessor still has time to make the connection")
    void slackKeepsTheConnection() {
        ItineraryLeg first = road(1, 0);
        // Leg 2 leaves at hour 30 after a one-hour transfer; leg 1 was due at 8.
        ItineraryLeg late = ItineraryLeg.plan(UUID.randomUUID(), TENANT, ITINERARY, 2,
                new ItineraryLeg.Plan(Mode.RAIL, B, C, h(30), h(40), 60, null, UnitType.WAGON,
                        10, "RR-2", null, CARRIER, BigDecimal.TEN), T0);
        assertThat(late.isOverdueAfter(first, h(20))).isFalse();
        assertThat(late.isOverdueAfter(first, h(29).plusSeconds(1))).isTrue();
    }

    @Test
    @DisplayName("an arrival after the sweep's miss revises the earliest start, and only that")
    void revisedEarliestStart() {
        ItineraryLeg next = rail(2, 240);
        assertThat(next.reviseEarliestStart(h(20), h(16))).isSameAs(next);

        ItineraryLeg marked = next.markConnectionMissed(h(17), h(13));
        ItineraryLeg revised = marked.reviseEarliestStart(h(20), h(16));
        assertThat(revised.earliestStartAt()).isEqualTo(h(20));
        assertThat(revised.connectionMissedAt()).isEqualTo(h(13));
        assertThat(revised.reviseEarliestStart(h(20), h(18))).isSameAs(revised);
    }

    // --------------------------------------------------------------- helpers

    /** Leg 1 by road A to B, hours 0 to 8. */
    private static ItineraryLeg road(int seq, int dwell) {
        UUID from = seq == 1 ? A : B;
        UUID to = seq == 1 ? B : C;
        int start = seq == 1 ? 0 : 12;
        return ItineraryLeg.plan(UUID.randomUUID(), TENANT, ITINERARY, seq,
                new ItineraryLeg.Plan(Mode.ROAD, from, to, h(start), h(start + 8), dwell,
                        UUID.randomUUID(), null, null, null, null, CARRIER, new BigDecimal("100")),
                T0);
    }

    /** Leg 2 by rail B to C, hours 12 to 20; or leg 1 A to B, hours 0 to 8. */
    private static ItineraryLeg rail(int seq, int dwell) {
        UUID from = seq == 1 ? A : B;
        UUID to = seq == 1 ? B : C;
        int start = seq == 1 ? 0 : 12;
        return ItineraryLeg.plan(UUID.randomUUID(), TENANT, ITINERARY, seq,
                new ItineraryLeg.Plan(Mode.RAIL, from, to, h(start), h(start + 8), dwell, null,
                        UnitType.CONTAINER, 20, "RR-" + seq, "TRAIN-9", CARRIER,
                        new BigDecimal("1000")),
                T0);
    }

    private static Instant h(int hours) {
        return T0.plus(hours, ChronoUnit.HOURS);
    }

    private static void assertRefused(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo(code));
    }
}
