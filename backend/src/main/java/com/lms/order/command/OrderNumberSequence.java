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

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hands out the order numbers the platform assigns, one tenant and one year at
 * a time.
 *
 * <p>The counter row is advanced with a single upsert in the caller's
 * transaction ({@code MANDATORY}). That statement locks the tenant's row for
 * the year until the raise commits, so two concurrent raises cannot draw the
 * same value, and a raise that rolls back returns its value with it: numbers
 * are dense, not merely unique. The cost is that raises without a number
 * serialise per tenant for the length of their transaction, which at the
 * volume one tenant enters orders is not a cost anyone sees.
 */
@Component
public class OrderNumberSequence {

    /** The prefix of every assigned number. */
    static final String PREFIX = "ORD";

    private final JdbcClient jdbc;
    private final Clock clock = Clock.systemUTC();

    public OrderNumberSequence(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The tenant's next order number for the current UTC year. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String next(UUID tenantId) {
        int year = clock.instant().atZone(ZoneOffset.UTC).getYear();
        long value = jdbc.sql("""
                        INSERT INTO order_number_counter (tenant_id, year, last_value)
                        VALUES (:tenantId, :year, 1)
                        ON CONFLICT (tenant_id, year)
                        DO UPDATE SET last_value = order_number_counter.last_value + 1,
                                      updated_at = now()
                        RETURNING last_value
                        """)
                .param("tenantId", tenantId)
                .param("year", year)
                .query(Long.class)
                .single();
        return format(year, value);
    }

    /**
     * {@code ORD-<yyyy>-<counter>}, the counter zero-padded to six digits.
     * A seventh digit is simply added once a tenant passes 999 999 orders in a
     * year; the number stays unique, it only stops sorting as text.
     */
    static String format(int year, long value) {
        if (value <= 0) {
            throw new IllegalArgumentException("An order counter starts at 1, not " + value);
        }
        return "%s-%04d-%06d".formatted(PREFIX, year, value);
    }
}
