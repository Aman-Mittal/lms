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

import java.time.Instant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link EventRetrier} on a timer. Off with {@code lms.events.retry.enabled=false},
 * which tests use so that they, not the clock, decide when a pass happens.
 */
@Component
@ConditionalOnProperty(name = "lms.events.retry.enabled", havingValue = "true", matchIfMissing = true)
class EventRetryScheduler {

    private final EventRetrier retrier;

    EventRetryScheduler(EventRetrier retrier) {
        this.retrier = retrier;
    }

    @Scheduled(fixedDelayString = "${lms.events.retry.interval:PT1M}",
            initialDelayString = "${lms.events.retry.interval:PT1M}")
    void run() {
        retrier.runOnce(Instant.now());
    }
}
