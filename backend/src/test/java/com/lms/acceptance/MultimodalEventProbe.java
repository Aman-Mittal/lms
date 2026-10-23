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
import java.util.concurrent.CopyOnWriteArrayList;

import com.lms.multimodal.events.ConnectionMissed;
import com.lms.multimodal.events.RailLegCompleted;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Hears the missed connections and released rail legs multimodal publishes, so a scenario can deliver
 * one again exactly as the outbox would after a restart.
 *
 * <p>A plain listener, like {@link WarehouseEventProbe}. Scenarios filter by
 * their own itinerary, so the shared list needs no clearing.
 */
@Component
public class MultimodalEventProbe {

    private final List<ConnectionMissed> missed = new CopyOnWriteArrayList<>();

    @EventListener
    void onConnectionMissed(ConnectionMissed event) {
        missed.add(event);
    }

    private final List<RailLegCompleted> completed = new CopyOnWriteArrayList<>();

    @EventListener
    void onRailLegCompleted(RailLegCompleted event) {
        completed.add(event);
    }

    public List<RailLegCompleted> completed() {
        return List.copyOf(completed);
    }

    public List<ConnectionMissed> missed() {
        return List.copyOf(missed);
    }
}
