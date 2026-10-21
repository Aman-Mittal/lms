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
import java.util.UUID;

/**
 * One row of the outbound order listing. The unit totals are summed over its
 * lines; {@code backOrderedUnits} is what is still owed.
 */
public record OutboundOrderSummaryView(
        UUID id,
        String orderNo,
        UUID warehouseId,
        String shipTo,
        String status,
        int orderedUnits,
        int pickedUnits,
        int backOrderedUnits,
        Instant createdAt) {
}
