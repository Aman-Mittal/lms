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

import com.lms.warehouse.events.GoodsReceived;
import com.lms.warehouse.events.HandlingUnitDispatched;
import com.lms.warehouse.events.PickShortfallRecorded;
import com.lms.warehouse.events.ReceiptVarianceRecorded;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Hears what warehousing publishes, so scenarios can assert on it.
 *
 * <p>Lets a scenario prove an event was published rather than inferring it
 * from a consumer's side effect, and keeps the hand-off so a scenario can
 * redeliver the exact event planning heard. A plain
 * listener rather than an after-commit one: the assertion is that the event
 * was published, and every scenario's command commits anyway. Scenarios
 * filter by their own identifiers, so the shared list needs no clearing.
 */
@Component
public class WarehouseEventProbe {

    private final List<Object> heard = new CopyOnWriteArrayList<>();

    @EventListener
    void onReceived(GoodsReceived event) {
        heard.add(event);
    }

    @EventListener
    void onVariance(ReceiptVarianceRecorded event) {
        heard.add(event);
    }

    @EventListener
    void onShortfall(PickShortfallRecorded event) {
        heard.add(event);
    }

    @EventListener
    void onDispatched(HandlingUnitDispatched event) {
        heard.add(event);
    }

    public <T> List<T> heard(Class<T> type) {
        return heard.stream().filter(type::isInstance).map(type::cast).toList();
    }
}
