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
 * A module's say in whether a validated order may still be amended.
 *
 * <p>Implemented by planning, which alone knows whether freight has been cut
 * from the order. The order cannot ask planning directly (planning depends on
 * orders, never the reverse), and this must be a synchronous answer: an
 * after-commit event could not stop the amendment it is told about.
 */
public interface OrderAmendmentGuard {

    /**
     * Called before a validated order is amended, inside its transaction.
     *
     * @throws com.lms.shared.error.BusinessRuleViolationException to refuse the
     *         amendment, which rolls back everything done so far
     */
    void onOrderAmending(UUID orderId);
}
