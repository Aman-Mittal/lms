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

import java.util.UUID;

/**
 * Implemented by a context that holds work an order has spawned, so that
 * cancelling the order can refuse, or clean up, in the same transaction.
 *
 * <p>The inverse of {@link OrderPlanningPort}: order cannot call planning
 * (planning depends on order, never the other way round), so the order module
 * publishes the shape of the question and planning answers it. The call is
 * synchronous on purpose. A cancellation that cannot be honoured -- the freight
 * is already on the road -- has to stop the order becoming CANCELLED, and an
 * event delivered afterwards could only apologise.
 */
public interface OrderCancellationHook {

    /**
     * Called before the order is saved as CANCELLED, inside its transaction.
     *
     * <p>Idempotent: an order is never offered twice for the same
     * cancellation, but an implementation must tolerate work that has already
     * been wound up.
     *
     * @throws com.lms.shared.error.BusinessRuleViolationException to refuse the
     *         cancellation, which rolls back everything done so far
     */
    void onOrderCancelling(UUID orderId);
}
