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
package com.lms.tracking.web;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The limiter's arithmetic, against a clock the test moves by hand. */
class ClientRateLimiterTest {

    private static final class Hand extends Clock {
        private final AtomicLong millis = new AtomicLong(1_000_000L);

        void advance(long ms) {
            millis.addAndGet(ms);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }
    }

    @Test
    @DisplayName("a client gets its allowance in a window and no more")
    void allowanceThenRefusal() {
        ClientRateLimiter limiter = new ClientRateLimiter(2, 60, new Hand());
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isFalse();
        assertThat(limiter.tryAcquire("a")).isFalse();
    }

    @Test
    @DisplayName("clients do not share a window")
    void clientsAreIndependent() {
        ClientRateLimiter limiter = new ClientRateLimiter(1, 60, new Hand());
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isFalse();
        assertThat(limiter.tryAcquire("b")).isTrue();
    }

    @Test
    @DisplayName("a new window starts from zero")
    void windowRollsOver() {
        Hand clock = new Hand();
        ClientRateLimiter limiter = new ClientRateLimiter(1, 60, clock);
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isFalse();
        clock.advance(59_999);
        assertThat(limiter.tryAcquire("a")).isFalse();
        clock.advance(1);
        assertThat(limiter.tryAcquire("a")).isTrue();
    }

    @Test
    @DisplayName("the wait is what is left of the window, rounded up, and at least a second")
    void retryAfter() {
        Hand clock = new Hand();
        ClientRateLimiter limiter = new ClientRateLimiter(1, 60, clock);
        assertThat(limiter.retryAfterSeconds("a")).isEqualTo(1);
        limiter.tryAcquire("a");
        assertThat(limiter.retryAfterSeconds("a")).isEqualTo(60);
        clock.advance(30_500);
        assertThat(limiter.retryAfterSeconds("a")).isEqualTo(30);
        clock.advance(29_000);
        assertThat(limiter.retryAfterSeconds("a")).isEqualTo(1);
    }
}
