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
package com.lms.finance.command;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.lms.shared.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finance's record of which trips the control tower has frozen (vision
 * document 3.8).
 *
 * <p>Keyed on the ticket, so two critical tickets on one trip are two holds
 * and closing the first leaves the second in force.
 *
 * <p>Both writes are upserts that commute. The outbox delivers at least once
 * and, after a restart, in no promised order, so a close can arrive before
 * its raise. Placing never clears a release that is already recorded, and
 * releasing creates the row if the placement has not arrived yet -- whichever
 * order the two land in, the result is a released hold, never a hold that
 * nothing will ever lift.
 */
@Component
public class SettlementHolds {

    private final JdbcClient jdbc;

    public SettlementHolds(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void place(UUID exceptionId, UUID tripId, String ticketNo, Instant placedAt) {
        jdbc.sql("""
                        INSERT INTO settlement_hold (tenant_id, exception_id, trip_id, ticket_no,
                                                     placed_at)
                        VALUES (:tenantId, :exceptionId, :tripId, :ticketNo, :placedAt)
                        ON CONFLICT (tenant_id, exception_id) DO NOTHING
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("exceptionId", exceptionId)
                .param("tripId", tripId)
                .param("ticketNo", ticketNo)
                .param("placedAt", placedAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(UUID exceptionId, UUID tripId, String ticketNo, Instant placedAt,
                        Instant releasedAt) {
        jdbc.sql("""
                        INSERT INTO settlement_hold (tenant_id, exception_id, trip_id, ticket_no,
                                                     placed_at, released_at)
                        VALUES (:tenantId, :exceptionId, :tripId, :ticketNo, :placedAt,
                                :releasedAt)
                        ON CONFLICT (tenant_id, exception_id)
                        DO UPDATE SET released_at = coalesce(settlement_hold.released_at,
                                                             EXCLUDED.released_at)
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("exceptionId", exceptionId)
                .param("tripId", tripId)
                .param("ticketNo", ticketNo)
                .param("placedAt", placedAt.atOffset(ZoneOffset.UTC))
                .param("releasedAt", releasedAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    /** The tickets still holding this trip, oldest first; empty when it may settle. */
    @Transactional(readOnly = true)
    public List<String> holdingTickets(UUID tripId) {
        return jdbc.sql("""
                        SELECT ticket_no FROM settlement_hold
                         WHERE tenant_id = :tenantId AND trip_id = :tripId
                           AND released_at IS NULL
                         ORDER BY placed_at
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("tripId", tripId)
                .query(String.class)
                .list();
    }
}
