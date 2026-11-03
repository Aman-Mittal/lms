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
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A fixed-window limit per client address for the public tracking route.
 *
 * <p>The route needs no credential, so the only thing standing between it and a
 * script walking token space is how fast a caller may ask. With 256-bit tokens
 * guessing one is hopeless either way; the limit is there so a hostile or
 * runaway client cannot turn the route into load on a small database.
 *
 * <p>In memory and per instance, which is all a single free-tier instance
 * needs. Entries of finished windows are dropped once the table grows, so the
 * memory is bounded by the number of distinct clients in one window.
 */
@Component
public class ClientRateLimiter {

    private static final int PRUNE_ABOVE = 10_000;

    private record Window(long startedAtMillis, int count) {
    }

    private final int maxRequests;
    private final long windowMillis;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public ClientRateLimiter(
            @Value("${lms.tracking.rate-limit.requests:30}") int maxRequests,
            @Value("${lms.tracking.rate-limit.window-seconds:60}") long windowSeconds) {
        this(maxRequests, windowSeconds, Clock.systemUTC());
    }

    ClientRateLimiter(int maxRequests, long windowSeconds, Clock clock) {
        this.maxRequests = maxRequests;
        this.windowMillis = windowSeconds * 1000;
        this.clock = clock;
    }

    /** Counts a request from {@code client}; false when it is over the limit for the window. */
    public boolean tryAcquire(String client) {
        long now = clock.millis();
        if (windows.size() > PRUNE_ABOVE) {
            windows.values().removeIf(w -> now - w.startedAtMillis() >= windowMillis);
        }
        Window window = windows.compute(client, (key, current) ->
                current == null || now - current.startedAtMillis() >= windowMillis
                        ? new Window(now, 1)
                        : new Window(current.startedAtMillis(), current.count() + 1));
        return window.count() <= maxRequests;
    }

    /** Whole seconds until the client's window ends, at least one. */
    public long retryAfterSeconds(String client) {
        Window window = windows.get(client);
        if (window == null) {
            return 1;
        }
        long left = windowMillis - (clock.millis() - window.startedAtMillis());
        return Math.max(1, (left + 999) / 1000);
    }
}
