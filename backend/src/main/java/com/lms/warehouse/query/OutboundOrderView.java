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

public record OutboundOrderView(
        UUID id,
        String orderNo,
        UUID warehouseId,
        String shipTo,
        String status,
        Instant createdAt,
        List<Line> lines) {

    public OutboundOrderView {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public record Line(
            int lineNo,
            UUID skuId,
            String skuCode,
            int orderedQty,
            int pickedQty,
            int shortQty) {
    }
}
