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

/** An ASN with each line's expectation, count and variance. */
public record AsnView(
        UUID id,
        String asnNo,
        UUID warehouseId,
        UUID supplierPartnerId,
        String status,
        boolean crossDock,
        UUID handlingUnitId,
        String receivedBy,
        Instant receivedAt,
        List<Line> lines) {

    public AsnView {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /**
     * Short and excess are derived from the count, never stored, so they
     * cannot disagree with it. All three are zero until the ASN is received.
     */
    public record Line(
            int lineNo,
            UUID skuId,
            String skuCode,
            String lotNo,
            int expectedQty,
            Integer receivedQty,
            Integer damagedQty,
            int shortQty,
            int excessQty) {
    }
}
