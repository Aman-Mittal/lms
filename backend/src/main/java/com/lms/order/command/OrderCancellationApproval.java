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
package com.lms.order.command;

import com.lms.approvals.api.ApprovalOutcome;
import com.lms.approvals.api.ApprovalOutcomeHandler;
import com.lms.approvals.api.ApprovalSubjectTypes;
import org.springframework.stereotype.Component;

/**
 * An approved order cancellation cancels the order through the one
 * cancellation path, in the deciding transaction.
 *
 * <p>Re-validated then, not trusted from when it was asked: freight that has
 * left since, or a delivery, refuses the decision with the cancellation's own
 * code ({@code order-work-dispatched}, {@code order-illegal-transition},
 * {@code load-has-active-trip}) and the request stays open. An order someone
 * else cancelled meanwhile is already where the request wanted it, so the
 * approval succeeds and changes nothing. A rejected or expired request leaves
 * the order exactly as it was: nothing was done while it was open.
 */
@Component
public class OrderCancellationApproval implements ApprovalOutcomeHandler {

    private final OrderCommandService orders;

    public OrderCancellationApproval(OrderCommandService orders) {
        this.orders = orders;
    }

    @Override
    public String subjectType() {
        return ApprovalSubjectTypes.ORDER_CANCELLATION;
    }

    @Override
    public void onApproved(ApprovalOutcome outcome) {
        orders.applyCancellation(outcome.subjectId(), outcome.text("reason"));
    }
}
