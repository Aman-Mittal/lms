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

import java.util.UUID;

import com.lms.shared.tenant.TenantContext;
import org.springframework.aot.hint.annotation.Reflective;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The bean {@code @tariffScope} that {@code @PreAuthorize} asks which
 * organisational unit a rate-card write concerns when the record has none of
 * its own. The fuel surcharge index is the tenant's, one per month for every
 * lane, so changing it is a write at the root of the organisation: only a
 * caller whose reach covers the whole tenant may make it.
 */
@Component("tariffScope")
public class TariffScope {

    private final JdbcClient jdbc;

    public TariffScope(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The tenant's root unit, or null when it has none (and the decision is
     * left to permissions). Read in a transaction of its own making, because
     * {@code @PreAuthorize} runs before the service's transaction opens and a
     * read with no transaction carries no tenant scope.
     */
    @Reflective
    @Transactional(readOnly = true)
    public UUID rootUnit() {
        return jdbc.sql("""
                        SELECT id FROM org_unit
                         WHERE tenant_id = :tenantId AND parent_id IS NULL
                         ORDER BY path
                         LIMIT 1
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .query(UUID.class)
                .optional()
                .orElse(null);
    }
}
