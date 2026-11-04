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
package com.lms.masterdata.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Whether a customer may be given more business: its standing, and its credit.
 *
 * <p>Asked synchronously, inside the caller's transaction, by the modules that
 * add to what a customer owes -- order (validate, amend, lines) and client
 * billing (the monthly run) -- so that a refusal stops the action and leaves
 * nothing behind. Implemented by master data, which holds the limits; the
 * exposure is assembled from {@link OpenOrderDemandSource} and
 * {@link ReceivablesSource}, implemented by the modules that hold the facts.
 */
public interface CustomerCreditPort {

    /**
     * Refuses with 409 {@code customer-not-trading} when the customer has been
     * suspended or blacklisted. An unknown partner is left to the caller, which
     * reports it as not found in its own words.
     */
    void requireTrading(UUID customerPartnerId);

    /**
     * Checks the customer's exposure, after the caller's change, against its
     * limits. Above the hard limit the action is refused with 409
     * {@code credit-limit-exceeded} unless an unused override for the same
     * subject covers the exposure, which is then used up. Above the soft limit
     * the action proceeds and a warning is recorded and published. A customer
     * with no limit is not checked.
     */
    void requireHeadroom(CreditCheck check);

    /** The action asking. */
    enum CreditEvent {
        ORDER_VALIDATE, ORDER_AMEND, ORDER_LINES, CLIENT_INVOICE
    }

    /**
     * What is being checked.
     *
     * @param orderId for an order event, the order -- counted in the exposure
     *                even while it is still a draft, since it is about to
     *                become a commitment
     * @param period  for a billing run, the first day of the month billed
     */
    record CreditCheck(UUID customerPartnerId, CreditEvent event, UUID orderId, LocalDate period) {

        public static CreditCheck forOrder(UUID customerPartnerId, CreditEvent event, UUID orderId) {
            return new CreditCheck(customerPartnerId, event, orderId, null);
        }

        public static CreditCheck forInvoice(UUID clientPartnerId, LocalDate periodStart) {
            return new CreditCheck(clientPartnerId, CreditEvent.CLIENT_INVOICE, null,
                    periodStart.withDayOfMonth(1));
        }

        /** What an override must name to cover this check. */
        public String subjectKey() {
            return event == CreditEvent.CLIENT_INVOICE
                    ? subjectKeyOfInvoice(period)
                    : subjectKeyOfOrder(orderId);
        }

        public static String subjectKeyOfOrder(UUID orderId) {
            return "ORDER:" + orderId;
        }

        public static String subjectKeyOfInvoice(LocalDate anyDayInPeriod) {
            return "CLIENT_INVOICE:" + anyDayInPeriod.withDayOfMonth(1);
        }
    }
}
