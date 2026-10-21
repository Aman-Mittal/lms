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
package com.lms.warehouse.query;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.lms.warehouse.api.OwnerStockPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Answers {@link OwnerStockPort} from the ledger.
 *
 * <p>The holding at the end of a day is worked backwards from the balance now:
 * what the owner holds today, less every movement after that day. That reads
 * only the movements since the start of the asked-for period rather than the
 * owner's whole history, and both reads ride indexes that lead with the SKU
 * ({@code stock_balance_sku_idx}, {@code stock_movement_sku_idx}), reached
 * through the owner's SKUs ({@code sku_owner_idx}).
 */
@Component
class OwnerStockLedgerReader implements OwnerStockPort {

    private final JdbcClient jdbc;

    OwnerStockLedgerReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<DailyHolding> unitsHeldEachDay(UUID ownerPartnerId, LocalDate from,
                                               LocalDate to) {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("The period ends before it starts");
        }
        return jdbc.sql("""
                        WITH owned AS (
                            SELECT id FROM sku WHERE owner_partner_id = :owner
                        ),
                        held_now AS (
                            SELECT coalesce(sum(b.on_hand), 0) AS units
                              FROM stock_balance b
                             WHERE b.sku_id IN (SELECT id FROM owned)
                        ),
                        moved AS (
                            SELECT (m.occurred_at AT TIME ZONE 'UTC')::date AS day,
                                   sum(m.quantity) AS delta
                              FROM stock_movement m
                             WHERE m.sku_id IN (SELECT id FROM owned)
                               AND m.occurred_at >= :since
                             GROUP BY 1
                        ),
                        days AS (
                            SELECT d::date AS day
                              FROM generate_series(CAST(:from AS date), CAST(:to AS date),
                                                   INTERVAL '1 day') d
                        )
                        -- End of day d = now, less what moved on any later day.
                        SELECT days.day,
                               ((SELECT units FROM held_now)
                                   - coalesce((SELECT sum(delta) FROM moved
                                                WHERE moved.day > days.day), 0))::bigint AS units
                          FROM days
                         ORDER BY days.day
                        """)
                .param("owner", ownerPartnerId)
                .param("since", from.atStartOfDay().atOffset(ZoneOffset.UTC))
                .param("from", from)
                .param("to", to)
                .query(DailyHolding.class)
                .list();
    }
}
