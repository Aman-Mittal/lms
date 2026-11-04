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
package com.lms.order.api;

import java.util.List;
import java.util.UUID;

/**
 * Asked, inside the transaction that cancels one order line, to wind up the
 * freight cut from it.
 *
 * <p>Implemented by planning, the same way as {@link OrderCancellationHook}:
 * order must be able to refuse the cancellation when the freight cannot be
 * cancelled, which an after-commit event could not do. Planning cancels the
 * consignment the line is on -- taking it off its load first -- and releases
 * every line that consignment was cut from through
 * {@link OrderPlanningPort#releaseLines}; the line being cancelled is among
 * them, and the others go back to waiting for a consignment.
 */
public interface OrderLineCancellationHook {

    /**
     * Called before the line is saved as cancelled.
     *
     * @return the consignments cancelled, empty when the line was on none
     * @throws com.lms.shared.error.BusinessRuleViolationException to refuse,
     *         with the code of the consignment that cannot be cancelled
     */
    List<UUID> onOrderLineCancelling(UUID orderId, UUID lineId, String reason);
}
