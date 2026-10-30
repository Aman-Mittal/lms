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

/**
 * When an incomplete publication is retried, and when it is given up on.
 *
 * <p>Pure arithmetic, so the schedule can be tested without a database: the
 * wait after the n-th failure is {@code initialBackoff * 2^(n-1)}, capped at
 * {@code maxBackoff}; a publication that has failed {@code maxAttempts} times
 * is parked rather than retried.
 */
public record RetryPolicy(int maxAttempts, Duration initialBackoff, Duration maxBackoff, Duration gracePeriod) {

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        if (initialBackoff.isNegative() || maxBackoff.isNegative() || gracePeriod.isNegative()) {
            throw new IllegalArgumentException("durations must not be negative");
        }
    }

    /** What to do with one incomplete publication now. */
    public enum Decision {
        /** Not yet: in flight, or still inside its backoff. */
        WAIT,
        RESUBMIT,
        PARK
    }

    /** The pause after the given number of failures. */
    public Duration backoffAfter(int failures) {
        if (failures <= 0) {
            return Duration.ZERO;
        }
        // Shifting past 62 bits overflows; the cap is reached long before.
        int exponent = Math.min(failures - 1, 30);
        Duration wait = initialBackoff.multipliedBy(1L << exponent);
        return wait.compareTo(maxBackoff) > 0 ? maxBackoff : wait;
    }

    /**
     * @param failures      failures recorded so far, zero if none was (a crash between
     *                      commit and listener leaves no record)
     * @param publishedAt   when the event was published
     * @param lastFailedAt  when it last failed, null if never recorded
     */
    public Decision decide(int failures, Instant publishedAt, Instant lastFailedAt, Instant now) {
        if (failures >= maxAttempts) {
            return Decision.PARK;
        }
        if (failures == 0 || lastFailedAt == null) {
            // Nothing recorded: the listener may still be running. Wait out the grace period.
            return publishedAt.plus(gracePeriod).isAfter(now) ? Decision.WAIT : Decision.RESUBMIT;
        }
        return lastFailedAt.plus(backoffAfter(failures)).isAfter(now) ? Decision.WAIT : Decision.RESUBMIT;
    }
}
