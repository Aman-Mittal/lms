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
package com.lms.planning.command;

import java.util.UUID;

import com.lms.order.api.OrderAmendmentGuard;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refuses to let an order be amended once freight has been cut from it.
 *
 * <p>A consignment is built from the order's lines as they were, and a
 * consignment that is cancelled is wound up and no longer holds demand, so
 * only the live ones count. One indexed count, however many consignments.
 */
@Service
class OrderAmendmentAdapter implements OrderAmendmentGuard {

    private final ConsignmentRepository consignments;

    OrderAmendmentAdapter(ConsignmentRepository consignments) {
        this.consignments = consignments;
    }

    @Override
    @Transactional(readOnly = true)
    public void onOrderAmending(UUID orderId) {
        long live = consignments.countLiveByOrder(TenantContext.requireTenantId(), orderId);
        if (live > 0) {
            throw new BusinessRuleViolationException("order-has-consignments",
                    "The order already has " + live + " consignment(s) cut from it and can no longer"
                            + " be amended; cancel it and raise a new one");
        }
    }
}
