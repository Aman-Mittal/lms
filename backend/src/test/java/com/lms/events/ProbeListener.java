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
package com.lms.events;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * A transactional listener that fails on command, so the retry and parking
 * machinery can be driven through a real publication registry.
 *
 * <p>Only present where {@code lms.test.probe-listener=true}: it reacts to
 * {@link ProbeEvent} alone, but no other test should have to know it exists.
 */
@Component
@ConditionalOnProperty(name = "lms.test.probe-listener", havingValue = "true")
public class ProbeListener {

    /** Fail this many more times, then succeed. */
    public static final int ALWAYS = -1;

    private static final Map<String, AtomicInteger> FAILURES_LEFT = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> CALLS = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> SUCCESSES = new ConcurrentHashMap<>();

    public static void failTimes(String key, int times) {
        FAILURES_LEFT.put(key, new AtomicInteger(times));
    }

    public static int calls(String key) {
        return CALLS.getOrDefault(key, new AtomicInteger()).get();
    }

    public static int successes(String key) {
        return SUCCESSES.getOrDefault(key, new AtomicInteger()).get();
    }

    @TransactionalEventListener
    public void on(ProbeEvent event) {
        CALLS.computeIfAbsent(event.key(), k -> new AtomicInteger()).incrementAndGet();
        AtomicInteger left = FAILURES_LEFT.get(event.key());
        if (left != null && (left.get() == ALWAYS || left.getAndUpdate(n -> n > 0 ? n - 1 : n) > 0)) {
            throw new IllegalStateException("probe " + event.key() + " was told to fail");
        }
        SUCCESSES.computeIfAbsent(event.key(), k -> new AtomicInteger()).incrementAndGet();
    }
}
