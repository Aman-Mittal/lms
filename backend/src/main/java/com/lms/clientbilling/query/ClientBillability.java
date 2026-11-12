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
package com.lms.clientbilling.query;

/**
 * When a captured charge may go on an invoice, as one SQL predicate over a
 * {@code client_charge} aliased {@code ch}.
 *
 * <p>Warehouse charges and hand-off charges are billable as soon as they are
 * captured. A trip's transport charge waits until every live consignment of
 * that client on the trip has a confirmed proof of delivery or has been marked
 * billable by hand -- unless the client's contract in force on the day bills
 * on dispatch. Kept in one place so the billing run and the charge listing can
 * never disagree about what the next invoice holds.
 */
public final class ClientBillability {

    /** True when the charge {@code ch} is billable now. */
    public static final String BILLABLE = """
            NOT (ch.service_type = 'TRANSPORT' AND ch.trip_id IS NOT NULL
                 AND NOT EXISTS (
                     SELECT 1 FROM client_contract k
                      WHERE k.tenant_id = ch.tenant_id
                        AND k.client_partner_id = ch.client_partner_id
                        AND k.bill_on_dispatch
                        AND k.status = 'ACTIVE'
                        AND k.effective_from <= ch.occurred_on
                        AND (k.effective_to IS NULL OR k.effective_to >= ch.occurred_on)
                        AND (k.valid_till IS NULL OR k.valid_till >= ch.occurred_on))
                 AND EXISTS (
                     SELECT 1
                       FROM trip t
                       JOIN load_consignment lc ON lc.load_id = t.load_id
                       JOIN consignment c ON c.id = lc.consignment_id
                       JOIN sales_order so ON so.id = c.order_id
                      WHERE t.id = ch.trip_id
                        AND so.customer_partner_id = ch.client_partner_id
                        AND c.freight_mode <> 'LTL'
                        AND c.status NOT IN ('CANCELLED', 'SPLIT')
                        AND NOT EXISTS (
                            SELECT 1 FROM delivery_proof dp
                             WHERE dp.tenant_id = c.tenant_id AND dp.consignment_id = c.id
                               AND dp.status = 'CONFIRMED')
                        AND NOT EXISTS (
                            SELECT 1 FROM consignment_billing_mark m
                             WHERE m.tenant_id = c.tenant_id AND m.consignment_id = c.id)))
            """;

    private ClientBillability() {
    }
}
