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
package com.lms.webhooks.command;

import java.time.Duration;

/**
 * How long to wait before the next attempt: doubling from the initial delay,
 * never longer than the cap.
 */
public record RetryBackoff(Duration initial, Duration max) {

    public RetryBackoff {
        if (initial.isNegative() || initial.isZero() || max.compareTo(initial) < 0) {
            throw new IllegalArgumentException("Backoff must start above zero and cap at or above its start");
        }
    }

    /** @param attemptsMade how many attempts have failed so far, at least one */
    public Duration after(int attemptsMade) {
        int doublings = Math.max(0, Math.min(attemptsMade - 1, 30));
        Duration delay = initial.multipliedBy(1L << doublings);
        return delay.compareTo(max) > 0 ? max : delay;
    }
}
