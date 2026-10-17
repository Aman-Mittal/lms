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
package com.lms.acceptance;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.lms.milkrun.events.CircuitRunCompleted;
import com.lms.milkrun.events.CircuitStopLate;
import com.lms.milkrun.events.CircuitStopSkipped;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * What the milk-run module announced, so a scenario can assert on it.
 *
 * <p>Nothing in the platform consumes these events yet, so the only way to
 * prove a skipped supplier or a late window is <em>announced</em> -- rather
 * than merely stored -- is to listen. A singleton shared by every scenario;
 * each asks only about its own circuit, whose identifier is unique.
 */
@Component
public class MilkRunEventLog {

    private final List<Object> events = new CopyOnWriteArrayList<>();

    @EventListener
    public void skipped(CircuitStopSkipped event) {
        events.add(event);
    }

    @EventListener
    public void late(CircuitStopLate event) {
        events.add(event);
    }

    @EventListener
    public void completed(CircuitRunCompleted event) {
        events.add(event);
    }

    public List<CircuitStopSkipped> skipsOf(UUID runId) {
        return events.stream()
                .filter(CircuitStopSkipped.class::isInstance)
                .map(CircuitStopSkipped.class::cast)
                .filter(event -> event.runId().equals(runId))
                .toList();
    }

    public List<CircuitStopLate> latesOf(UUID runId) {
        return events.stream()
                .filter(CircuitStopLate.class::isInstance)
                .map(CircuitStopLate.class::cast)
                .filter(event -> event.runId().equals(runId))
                .toList();
    }

    public List<CircuitRunCompleted> completionsOf(UUID runId) {
        return events.stream()
                .filter(CircuitRunCompleted.class::isInstance)
                .map(CircuitRunCompleted.class::cast)
                .filter(event -> event.runId().equals(runId))
                .toList();
    }
}
