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

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import com.lms.masterdata.api.CustomerCreditPort;
import com.lms.masterdata.api.CustomerCreditPort.CreditCheck;
import com.lms.masterdata.api.CustomerCreditPort.CreditEvent;
import com.lms.order.command.domain.OrderLine;
import com.lms.order.command.domain.SalesOrder;
import org.springframework.stereotype.Component;

/**
 * The customer standing and credit checks an order goes through.
 *
 * <p>Kept apart from {@link OrderCommandService} so the order's own rules and
 * the customer's commercial standing can be read, and changed, separately.
 * Standing is checked before anything is written; credit after, so the
 * exposure master data reads already includes the change and a refusal rolls
 * it back with everything else.
 */
@Component
class OrderCreditGate {

    private final CustomerCreditPort credit;

    OrderCreditGate(CustomerCreditPort credit) {
        this.credit = credit;
    }

    /** Before an order is validated, or lines are added: the customer must be trading. */
    void requireTrading(SalesOrder order) {
        credit.requireTrading(order.customerPartnerId());
    }

    /** After the order is VALIDATED, inside the same transaction. */
    void afterValidating(SalesOrder order) {
        credit.requireHeadroom(CreditCheck.forOrder(order.customerPartnerId(),
                CreditEvent.ORDER_VALIDATE, order.id()));
    }

    /** After lines were added to or replaced on a draft. */
    void afterLinesChanged(SalesOrder order) {
        credit.requireHeadroom(CreditCheck.forOrder(order.customerPartnerId(),
                CreditEvent.ORDER_LINES, order.id()));
    }

    /** Before an amendment is written: the customer it will name must be trading. */
    void beforeAmending(SalesOrder amended) {
        credit.requireTrading(amended.customerPartnerId());
    }

    /**
     * After an amendment. Checked only when it can have added exposure: a new
     * customer, or more chargeable weight. An amendment that shrinks an order
     * must stay possible for a customer already over its limit -- that is how
     * the exposure comes down.
     */
    void afterAmending(SalesOrder before, SalesOrder after, List<OrderLine> linesBefore,
                       List<OrderLine> linesAfter) {
        boolean sameCustomer = Objects.equals(before.customerPartnerId(), after.customerPartnerId());
        if (sameCustomer && weightOf(linesAfter).compareTo(weightOf(linesBefore)) <= 0) {
            return;
        }
        credit.requireHeadroom(CreditCheck.forOrder(after.customerPartnerId(),
                CreditEvent.ORDER_AMEND, after.id()));
    }

    private static BigDecimal weightOf(List<OrderLine> lines) {
        return lines.stream().map(OrderLine::chargeableWeightKg)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
