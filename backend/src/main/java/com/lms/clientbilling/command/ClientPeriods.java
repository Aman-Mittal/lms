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
package com.lms.clientbilling.command;

import java.time.LocalDate;
import java.util.UUID;

import com.lms.clientbilling.command.domain.ClientCharge;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The period close, and the lock that makes it hold.
 *
 * <p>A charge goes into the month it occurred unless that month has been
 * invoiced, in which case it goes into the first month after that has not.
 * The check and the insert have to be atomic with respect to a billing run
 * for the same client, or a charge could be filed under a month in the instant
 * between the run reading that month's charges and closing it -- and would
 * then sit in a closed month, never billed. Both sides therefore take the same
 * transaction-scoped advisory lock, keyed on tenant and client, before
 * reading. Different clients never wait for each other.
 */
@Component
class ClientPeriods {

    private final JdbcClient jdbc;
    private final ClientInvoiceRepository invoices;

    ClientPeriods(JdbcClient jdbc, ClientInvoiceRepository invoices) {
        this.jdbc = jdbc;
        this.invoices = invoices;
    }

    /** Serialises captures and runs for one client until the transaction ends. */
    void lock(UUID tenantId, UUID clientPartnerId) {
        jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) held")
                .param("key", "client-billing:" + tenantId + ":" + clientPartnerId)
                .query(Integer.class)
                .single();
    }

    /**
     * The month a charge that occurred on {@code day} is billed in. The caller
     * must hold {@link #lock}.
     */
    LocalDate openPeriodFor(UUID tenantId, UUID clientPartnerId, LocalDate day) {
        LocalDate period = ClientCharge.periodOf(day);
        while (invoices.findForPeriod(tenantId, clientPartnerId, period).isPresent()) {
            period = period.plusMonths(1);
        }
        return period;
    }
}
