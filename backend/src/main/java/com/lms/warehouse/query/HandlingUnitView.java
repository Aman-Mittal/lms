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
 * A handling unit and, until it is dispatched, what is in it. A dispatched
 * unit's contents have left the ledger; its movements still record them.
 */
public record HandlingUnitView(
        UUID id,
        String huNo,
        UUID warehouseId,
        String source,
        UUID outboundOrderId,
        UUID asnId,
        String status,
        String dispatchRef,
        Instant dispatchedAt,
        List<Content> contents) {

    public HandlingUnitView {
        contents = contents == null ? List.of() : List.copyOf(contents);
    }

    public record Content(UUID skuId, String skuCode, String lotNo, int quantity) {
    }
}
