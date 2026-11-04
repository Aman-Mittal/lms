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

import com.lms.order.events.OrderAmended;
import com.lms.order.events.OrderCancelled;
import com.lms.order.events.OrderFulfilled;
import com.lms.order.events.OrderRaised;
import com.lms.order.events.OrderValidated;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Hears what the order module publishes, so a scenario can assert that an
 * event was published rather than infer it from a consumer. As
 * {@link WarehouseEventProbe}: a plain listener, filtered by order id, never
 * cleared.
 */
@Component
public class OrderEventProbe {

    /** One heard event: its simple type name and the order it concerns. */
    public record Heard(String type, UUID orderId, Object event) {
    }

    private final List<Heard> heard = new CopyOnWriteArrayList<>();

    @EventListener
    void on(OrderRaised event) {
        heard.add(new Heard("OrderRaised", event.orderId(), event));
    }

    @EventListener
    void on(OrderValidated event) {
        heard.add(new Heard("OrderValidated", event.orderId(), event));
    }

    @EventListener
    void on(OrderAmended event) {
        heard.add(new Heard("OrderAmended", event.orderId(), event));
    }

    @EventListener
    void on(OrderCancelled event) {
        heard.add(new Heard("OrderCancelled", event.orderId(), event));
    }

    @EventListener
    void on(OrderFulfilled event) {
        heard.add(new Heard("OrderFulfilled", event.orderId(), event));
    }

    /** Every event heard about one order, in publication order. */
    public List<Heard> about(UUID orderId) {
        return heard.stream().filter(h -> h.orderId().equals(orderId)).toList();
    }
}
