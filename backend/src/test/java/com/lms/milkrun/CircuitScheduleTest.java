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
package com.lms.milkrun;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import com.lms.milkrun.command.domain.CircuitRun;
import com.lms.milkrun.command.domain.CircuitRunStop;
import com.lms.milkrun.command.domain.MilkRunRoute;
import com.lms.milkrun.command.domain.MilkRunRouteStop;
import com.lms.shared.error.BusinessRuleViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The arithmetic of a milk-run schedule, without a database. */
class CircuitScheduleTest {

    private static final UUID TENANT = UUID.randomUUID();

    @Test
    @DisplayName("days are stored Monday first and de-duplicated, however they are listed")
    void canonicalDays() {
        MilkRunRoute route = route(List.of("fri", "MON", "Wednesday", "mon"), "UTC");
        assertThat(route.daysOfWeek()).isEqualTo("MON,WED,FRI");
        assertThat(route.runsOn(LocalDate.parse("2030-01-09"))).isTrue();   // a Wednesday
        assertThat(route.runsOn(LocalDate.parse("2030-01-08"))).isFalse();  // a Tuesday
    }

    @Test
    @DisplayName("a departure is a local time at the plant, across a daylight-saving change")
    void departureFollowsTheLocalClock() {
        MilkRunRoute route = route(List.of("SUN"), "Europe/Berlin");
        // 06:30 in Berlin is 05:30 UTC in winter and 04:30 UTC in summer.
        assertThat(route.plannedDepartureOn(LocalDate.parse("2030-03-24")))
                .isEqualTo(Instant.parse("2030-03-24T05:30:00Z"));
        assertThat(route.plannedDepartureOn(LocalDate.parse("2030-03-31")))
                .isEqualTo(Instant.parse("2030-03-31T04:30:00Z"));
    }

    @Test
    @DisplayName("a schedule with no days, or an unknown zone, is refused")
    void refusals() {
        assertThatThrownBy(() -> route(List.of(), "UTC"))
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> route(List.of("MON"), "Mars/Olympus"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("lateness is whole minutes after the window closed, rounded up")
    void lateness() {
        Instant departure = Instant.parse("2030-01-07T01:00:00Z");
        CircuitRunStop stop = CircuitRunStop.plan(TENANT, UUID.randomUUID(),
                MilkRunRouteStop.of(TENANT, UUID.randomUUID(), 1, UUID.randomUUID(), 0, 30, 10),
                departure);

        assertThat(stop.arrive(departure.plusSeconds(30 * 60), CircuitRun.Source.MANUAL)
                .minutesLate()).isZero();
        assertThat(stop.arrive(departure.plusSeconds(30 * 60 + 1), CircuitRun.Source.MANUAL)
                .minutesLate()).isEqualTo(1);
    }

    @Test
    @DisplayName("a stop already reached cannot be reached again or skipped")
    void stopStateMachine() {
        CircuitRunStop stop = CircuitRunStop.plan(TENANT, UUID.randomUUID(),
                MilkRunRouteStop.of(TENANT, UUID.randomUUID(), 1, UUID.randomUUID(), 0, 30, 10),
                Instant.parse("2030-01-07T01:00:00Z"));
        CircuitRunStop reached = stop.arrive(Instant.parse("2030-01-07T01:10:00Z"),
                CircuitRun.Source.GEOFENCE);

        assertThatThrownBy(() -> reached.arrive(Instant.now(), CircuitRun.Source.GEOFENCE))
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> reached.skip("late"))
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> stop.pickUp(BigDecimal.TEN))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    private static MilkRunRoute route(List<String> days, String zone) {
        return MilkRunRoute.publish(UUID.randomUUID(), TENANT, "MR-1", "Circuit",
                UUID.randomUUID(), LocalDate.parse("2030-01-01"), days, LocalTime.of(6, 30), zone,
                new BigDecimal("4000"), new BigDecimal("500"), "INR");
    }
}
