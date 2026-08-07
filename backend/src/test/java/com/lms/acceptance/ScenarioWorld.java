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
package com.lms.acceptance;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import io.cucumber.spring.ScenarioScope;
import org.springframework.stereotype.Component;

/**
 * State shared between step-definition classes within one scenario.
 *
 * <p>{@link ScenarioScope} rather than a singleton: the application context is
 * built once for the whole suite, so a singleton would carry one scenario's
 * tenants into the next and make failures depend on execution order.
 *
 * <p>Tenant codes are salted per scenario because the PostgreSQL container is
 * shared for speed. Scenarios isolate themselves by operating on distinct
 * tenants rather than by truncating the database between each one, which also
 * means the suite exercises a database that already contains other tenants'
 * data — the realistic condition for testing isolation.
 */
@Component
@ScenarioScope
public class ScenarioWorld {

    private final String salt = UUID.randomUUID().toString().substring(0, 8);
    private final Map<String, UUID> tenants = new HashMap<>();
    private final Map<String, UUID> orgUnits = new HashMap<>();

    private String currentTenantCode;
    private String currentOrgPath;

    /** The scenario-unique code actually written to the database. */
    public String uniqueCode(String logicalCode) {
        return logicalCode + "-" + salt;
    }

    public boolean knowsTenant(String logicalCode) {
        return tenants.containsKey(logicalCode);
    }

    public void putTenant(String logicalCode, UUID id) {
        tenants.put(logicalCode, id);
        if (currentTenantCode == null) {
            currentTenantCode = logicalCode;
        }
    }

    public UUID tenantId(String logicalCode) {
        UUID id = tenants.get(logicalCode);
        if (id == null) {
            throw new IllegalStateException("Scenario has no tenant '" + logicalCode + "'");
        }
        return id;
    }

    public void putOrgUnit(String path, UUID id) {
        orgUnits.put(path, id);
        if (currentOrgPath == null) {
            currentOrgPath = path;
        }
    }

    public UUID orgUnitId(String path) {
        return orgUnits.get(path);
    }

    /** The organisational unit commands are issued against. */
    public UUID orgUnitId() {
        UUID id = orgUnits.get(currentOrgPath);
        if (id == null) {
            throw new IllegalStateException("Scenario has no current organisational unit");
        }
        return id;
    }

    /**
     * Identifiers of anything else a scenario created, keyed by the name the
     * Gherkin uses.
     *
     * <p>Shared here rather than in a step class because the order and planning
     * scenarios span both: a step that builds a load needs the identifier of a
     * terminal another step registered.
     */
    private final Map<String, UUID> refs = new HashMap<>();

    public void putRef(String key, UUID id) {
        refs.put(key, id);
    }

    public UUID ref(String key) {
        UUID id = refs.get(key);
        if (id == null) {
            throw new IllegalStateException("Scenario has nothing named '" + key + "'");
        }
        return id;
    }

    public void useScope(String tenantCode, String orgPath) {
        this.currentTenantCode = tenantCode;
        this.currentOrgPath = orgPath;
    }
}
