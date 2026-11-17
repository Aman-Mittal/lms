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
package com.lms.masterdata.command;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Answers "is any live work still pointed at this terminal", which is what
 * stops it being switched off.
 *
 * <p>Narrower than {@link TerminalReferenceCheck}, which asks whether any row
 * at all names the terminal. A terminal that has only history behind it -- a
 * delivered order, a completed trip -- may be deactivated, because deactivating
 * deletes nothing; one that an open order, load or trip still depends on may
 * not, because those would be left waiting at a place that is switched off.
 *
 * <p>One statement, one {@code EXISTS} per kind of work, each an index probe on
 * {@code (tenant_id, terminal column)}.
 */
@Component
public class TerminalOpenUseCheck {

    private static final String OPEN_USES = """
            SELECT EXISTS (SELECT 1 FROM sales_order o
                            WHERE o.tenant_id = :tenantId AND o.origin_terminal_id = :id
                              AND o.status NOT IN ('FULFILLED', 'CANCELLED', 'CLOSED'))
                OR EXISTS (SELECT 1 FROM order_line l
                             JOIN sales_order o2 ON o2.id = l.order_id
                            WHERE l.tenant_id = :tenantId AND l.destination_terminal_id = :id
                              AND o2.status NOT IN ('FULFILLED', 'CANCELLED', 'CLOSED')) AS orders,
                   EXISTS (SELECT 1 FROM load_unit u
                            WHERE u.tenant_id = :tenantId AND u.origin_terminal_id = :id
                              AND u.status NOT IN ('COMPLETED', 'CANCELLED')) AS loads,
                   EXISTS (SELECT 1 FROM trip t
                            WHERE t.tenant_id = :tenantId AND t.origin_terminal_id = :id
                              AND t.status NOT IN ('COMPLETED', 'CANCELLED'))
                OR EXISTS (SELECT 1 FROM trip t2
                            WHERE t2.tenant_id = :tenantId AND t2.destination_terminal_id = :id
                              AND t2.status NOT IN ('COMPLETED', 'CANCELLED')) AS trips
            """;

    private record Uses(boolean orders, boolean loads, boolean trips) {
    }

    private final JdbcClient jdbc;

    public TerminalOpenUseCheck(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The kinds of open work that name the terminal; empty when it is free to switch off. */
    public List<String> openUses(UUID tenantId, UUID terminalId) {
        Uses uses = jdbc.sql(OPEN_USES)
                .param("tenantId", tenantId)
                .param("id", terminalId)
                .query((rs, n) -> new Uses(rs.getBoolean("orders"), rs.getBoolean("loads"),
                        rs.getBoolean("trips")))
                .single();
        List<String> found = new ArrayList<>();
        if (uses.orders()) {
            found.add("open orders");
        }
        if (uses.loads()) {
            found.add("open loads");
        }
        if (uses.trips()) {
            found.add("open trips");
        }
        return found;
    }
}
