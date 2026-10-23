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

package com.lms.telematics.command.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.lms.shared.geo.LatLon;
import com.lms.telematics.command.domain.StoppageWatch.Verdict;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StoppageWatchTest {

    private static final Instant SINCE = Instant.parse("2026-01-01T10:00:00Z");
    private static final Duration THRESHOLD = Duration.ofMinutes(45);
    private static final double RADIUS = 100;

    private final StoppageWatch watch = StoppageWatch.begin(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), new BigDecimal("19.200000"), new BigDecimal("73.000000"), SINCE);

    @Test
    void sittingStillForLessThanTheThresholdIsNothing() {
        assertThat(watch.judge(new LatLon(19.2, 73.0), SINCE.plus(Duration.ofMinutes(44)),
                RADIUS, THRESHOLD)).isEqualTo(Verdict.NOTHING);
    }

    @Test
    void sittingStillForTheThresholdRaises() {
        assertThat(watch.judge(new LatLon(19.2, 73.0), SINCE.plus(THRESHOLD), RADIUS, THRESHOLD))
                .isEqualTo(Verdict.RAISE);
    }

    @Test
    void jitterInsideTheRadiusStillCounts() {
        // About 47 m away.
        assertThat(watch.judge(new LatLon(19.2003, 73.0003), SINCE.plus(Duration.ofHours(2)),
                RADIUS, THRESHOLD)).isEqualTo(Verdict.RAISE);
    }

    @Test
    void movingBeyondTheRadiusRestartsTheClock() {
        // About 1 km away.
        assertThat(watch.judge(new LatLon(19.2, 73.01), SINCE.plus(Duration.ofHours(2)),
                RADIUS, THRESHOLD)).isEqualTo(Verdict.RESTART);
    }

    @Test
    void aStopThatWasReportedIsNotReportedAgain() {
        StoppageWatch raised = watch.raised(SINCE.plus(THRESHOLD));
        assertThat(raised.judge(new LatLon(19.2, 73.0), SINCE.plus(Duration.ofHours(5)),
                RADIUS, THRESHOLD)).isEqualTo(Verdict.NOTHING);
    }

    @Test
    void movingOffAfterBeingReportedStartsAFreshStop() {
        StoppageWatch raised = watch.raised(SINCE.plus(THRESHOLD));
        assertThat(raised.judge(new LatLon(19.25, 73.05), SINCE.plus(Duration.ofHours(3)),
                RADIUS, THRESHOLD)).isEqualTo(Verdict.RESTART);
        StoppageWatch next = raised.restartedAt(new BigDecimal("19.25"), new BigDecimal("73.05"),
                SINCE.plus(Duration.ofHours(3)));
        assertThat(next.isRaised()).isFalse();
        assertThat(next.stationarySince()).isEqualTo(SINCE.plus(Duration.ofHours(3)));
    }

    @Test
    void aLateArrivingOlderPointIsIgnored() {
        assertThat(watch.judge(new LatLon(19.5, 73.5), SINCE.minus(Duration.ofMinutes(10)),
                RADIUS, THRESHOLD)).isEqualTo(Verdict.NOTHING);
    }
}
