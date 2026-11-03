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
package com.lms.execution.command;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.lms.planning.api.LoadInUseGuard;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refuses to let a load's award or freight be withdrawn while a trip is run
 * against it.
 *
 * <p>Any trip that is not CANCELLED counts, dispatched or not. A raised trip
 * has a vendor on it, usually a vehicle and a driver, and sometimes a gate-in
 * and a weighbridge reading; taking the load back from under it would leave
 * all of that pointing at an award that no longer exists. A cancelled trip
 * releases the load.
 *
 * <p>One indexed read for however many loads are asked about
 * ({@code trip_load_unique} leads with the tenant).
 */
@Component
public class LoadInUseGuardAdapter implements LoadInUseGuard {

    private final JdbcClient jdbc;

    public LoadInUseGuardAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public void requireNotInUse(Collection<UUID> loadIds) {
        if (loadIds.isEmpty()) {
            return;
        }
        List<String> live = jdbc.sql("""
                        SELECT trip_no || ' (' || status || ')' FROM trip
                         WHERE tenant_id = :tenantId AND load_id IN (:loadIds)
                           AND status <> 'CANCELLED'
                         ORDER BY trip_no
                         LIMIT 1
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("loadIds", loadIds)
                .query(String.class)
                .list();
        if (!live.isEmpty()) {
            throw new BusinessRuleViolationException("load-has-active-trip",
                    "Trip " + live.get(0) + " is raised against the load; its award and its "
                            + "freight can only be withdrawn once that trip is cancelled");
        }
    }
}
