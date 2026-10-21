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
package com.lms.warehouse.query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A pick wave and, for each of its orders in the sequence they were served,
 * what the order asked for, what it has been given and what it is still owed.
 */
public record PickWaveView(
        UUID id,
        String waveNo,
        UUID warehouseId,
        String status,
        String pickedBy,
        Instant pickedAt,
        Instant createdAt,
        List<Order> orders) {

    public PickWaveView {
        orders = orders == null ? List.of() : List.copyOf(orders);
    }

    public record Order(
            int seq,
            UUID orderId,
            String orderNo,
            String status,
            int orderedUnits,
            int pickedUnits,
            int shortUnits) {
    }
}
