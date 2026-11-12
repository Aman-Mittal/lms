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

import java.util.UUID;

import com.lms.masterdata.api.TerminalOrgUnitPort;
import com.lms.shared.tenant.TenantContext;
import org.springframework.aot.hint.annotation.Reflective;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The bean {@code @gateSites} that {@code @PreAuthorize} asks which
 * organisational unit a gate action concerns, so the access policies are
 * applied to the unit the vehicle enters rather than to nothing.
 *
 * <p>A site is a unit, or one of its terminals; naming only the terminal means
 * the terminal's own unit. Read in transactions of their own making, because
 * {@code @PreAuthorize} runs before the service's transaction opens.
 */
@Component("gateSites")
public class GateSites {

    private final JdbcClient jdbc;
    private final TerminalOrgUnitPort terminals;

    public GateSites(JdbcClient jdbc, TerminalOrgUnitPort terminals) {
        this.jdbc = jdbc;
        this.terminals = terminals;
    }

    /** The unit a site request is about, or null when it names none (the service then refuses it). */
    @Reflective
    @Transactional(readOnly = true)
    public UUID unitOf(UUID orgUnitId, UUID terminalId) {
        if (terminalId != null) {
            return terminals.orgUnitOf(terminalId).orElse(orgUnitId);
        }
        return orgUnitId;
    }

    @Reflective
    @Transactional(readOnly = true)
    public UUID unitOfVisit(UUID visitId) {
        return unitOf("gate_visit", visitId);
    }

    @Reflective
    @Transactional(readOnly = true)
    public UUID unitOfPass(UUID passId) {
        return unitOf("gate_pass", passId);
    }

    private UUID unitOf(String table, UUID id) {
        return jdbc.sql("SELECT org_unit_id FROM " + table + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", TenantContext.requireTenantId())
                .param("id", id)
                .query(UUID.class)
                .optional()
                .orElse(null);
    }
}
