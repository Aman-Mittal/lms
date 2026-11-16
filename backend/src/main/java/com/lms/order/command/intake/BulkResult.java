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

package com.lms.order.command.intake;

import java.util.List;
import java.util.UUID;

/**
 * What happened to one item of a batch: CREATED (a new draft-validated order),
 * DUPLICATE (its key was already used for this very content, so the earlier
 * order is named) or FAILED, with every reason known.
 */
public record BulkResult(int index, Status status, UUID orderId, List<String> reasons) {

    public enum Status {
        CREATED, DUPLICATE, FAILED
    }

    public BulkResult {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    static BulkResult created(int index, UUID orderId) {
        return new BulkResult(index, Status.CREATED, orderId, List.of());
    }

    static BulkResult duplicate(int index, UUID orderId) {
        return new BulkResult(index, Status.DUPLICATE, orderId, List.of());
    }

    static BulkResult failed(int index, List<String> reasons) {
        return new BulkResult(index, Status.FAILED, null, reasons);
    }
}
