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
package com.lms.events.command;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(5, Duration.ofMinutes(1), Duration.ofMinutes(10),
            Duration.ofMinutes(5));
    private final Instant published = Instant.parse("2026-10-30T10:00:00Z");

    @Test
    @DisplayName("the wait doubles with each failure and stops at the cap")
    void backoffDoublesAndCaps() {
        assertThat(policy.backoffAfter(0)).isEqualTo(Duration.ZERO);
        assertThat(policy.backoffAfter(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(policy.backoffAfter(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(policy.backoffAfter(4)).isEqualTo(Duration.ofMinutes(8));
        assertThat(policy.backoffAfter(5)).isEqualTo(Duration.ofMinutes(10));
        assertThat(policy.backoffAfter(1_000)).as("no overflow on a huge count").isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("a publication with no recorded failure waits out the grace period")
    void graceForNoFailure() {
        assertThat(policy.decide(0, published, null, published.plusSeconds(60)))
                .isEqualTo(RetryPolicy.Decision.WAIT);
        assertThat(policy.decide(0, published, null, published.plus(Duration.ofMinutes(5))))
                .isEqualTo(RetryPolicy.Decision.RESUBMIT);
    }

    @Test
    @DisplayName("a failed publication is resubmitted only once its backoff has run out")
    void backoffDecides() {
        Instant failed = published.plusSeconds(10);
        assertThat(policy.decide(2, published, failed, failed.plusSeconds(119)))
                .isEqualTo(RetryPolicy.Decision.WAIT);
        assertThat(policy.decide(2, published, failed, failed.plusSeconds(120)))
                .isEqualTo(RetryPolicy.Decision.RESUBMIT);
    }

    @Test
    @DisplayName("the last attempt parks the publication, whatever the clock says")
    void parkAtTheLimit() {
        assertThat(policy.decide(5, published, published, published))
                .isEqualTo(RetryPolicy.Decision.PARK);
        assertThat(policy.decide(9, published, published, published))
                .isEqualTo(RetryPolicy.Decision.PARK);
    }

    @Test
    @DisplayName("a policy that could never retry is refused")
    void invalidPolicies() {
        assertThatThrownBy(() -> new RetryPolicy(0, Duration.ZERO, Duration.ZERO, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(1, Duration.ofSeconds(-1), Duration.ZERO, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
