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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.execution.command.domain.Trip;
import com.lms.planning.api.LoadLifecyclePort;
import com.lms.settings.api.EffectiveSettings;
import com.lms.settings.api.Resolved;
import com.lms.settings.api.ScopeType;
import com.lms.settings.api.SettingContext;
import com.lms.settings.api.SettingKey;
import com.lms.settings.api.SettingSource;
import com.lms.settings.api.Settings;
import org.springframework.stereotype.Component;

/**
 * Execution's settings, resolved for one trip: its unit, its vehicle and the
 * customers whose freight is aboard ({@link ExecutionSettingDefinitions}).
 *
 * <p>Joins the caller's transaction through the settings port, so a dispatch
 * still holds one connection (ADR 0006). A dispatch reads one statement per
 * customer aboard, or one for a load whose freight came from no order.
 */
@Component
class TripSettings {

    private final Settings settings;
    private final LoadLifecyclePort loads;

    TripSettings(Settings settings, LoadLifecyclePort loads) {
        this.settings = settings;
        this.loads = loads;
    }

    /**
     * What a dispatch must satisfy.
     *
     * @param requiredBy      each required document type, in name order, with
     *                        the profile that required it
     * @param weightTolerance the tightest tolerance any applicable profile allows
     * @param toleranceFrom   the profile that set it
     */
    record Checklist(Map<String, String> requiredBy, BigDecimal weightTolerance, String toleranceFrom) {
    }

    /**
     * The checklist in force on {@code day} (null for today, in the tenant's
     * time zone). Every customer aboard is asked; a document any of them
     * requires is required, and the smallest tolerance holds.
     */
    Checklist checklistFor(Trip trip, LocalDate day) {
        List<UUID> customers = new ArrayList<>(loads.customersAboard(trip.loadId()));
        if (customers.isEmpty()) {
            customers.add(null);
        }
        String mode = loads.freightModeOf(trip.loadId());
        Map<UUID, UUID> contracts = loads.contractFamiliesAboard(trip.loadId());
        Map<String, String> requiredBy = new LinkedHashMap<>();
        BigDecimal tolerance = null;
        String toleranceFrom = null;
        for (UUID customer : customers) {
            EffectiveSettings resolved = settings.resolveAll(
                    List.of(ExecutionSettingDefinitions.REQUIRED_DOCUMENTS, ExecutionSettingDefinitions.WEIGHT_TOLERANCE),
                    context(trip, customer).withFreightMode(mode).withContract(customer == null ? null : contracts.get(customer))
                            .asOf(day));
            Resolved<Map<String, Object>> documents = resolved.resolved(ExecutionSettingDefinitions.REQUIRED_DOCUMENTS);
            for (String type : ExecutionSettingDefinitions.required(documents.value())) {
                requiredBy.putIfAbsent(type, describe(documents.source()));
            }
            Resolved<BigDecimal> allowed = resolved.resolved(ExecutionSettingDefinitions.WEIGHT_TOLERANCE);
            if (tolerance == null || allowed.value().compareTo(tolerance) < 0) {
                tolerance = allowed.value();
                toleranceFrom = describe(allowed.source());
            }
        }
        Map<String, String> sorted = new LinkedHashMap<>();
        requiredBy.keySet().stream().sorted().forEach(type -> sorted.put(type, requiredBy.get(type)));
        return new Checklist(sorted, tolerance, toleranceFrom);
    }

    /** One tracking switch for the trip, read for the only customer aboard (none for a mixed load). */
    boolean tracking(SettingKey<Boolean> key, Trip trip) {
        List<UUID> customers = loads.customersAboard(trip.loadId());
        return settings.effective(key, context(trip, customers.size() == 1 ? customers.get(0) : null));
    }

    private static SettingContext context(Trip trip, UUID customer) {
        return SettingContext.ofOrgUnit(trip.orgUnitId()).withVehicle(trip.vehicleId()).withCustomer(customer);
    }

    /** Where a value came from, in words a dispatcher can act on. */
    static String describe(SettingSource source) {
        if (source.isSystemDefault()) {
            return "the system default";
        }
        String scope = switch (source.scopeType()) {
            case PLATFORM -> "platform";
            case ORG_DEFAULT -> "organisation default";
            case ORG_UNIT -> "org unit";
            case VEHICLE -> "vehicle";
            case CUSTOMER -> "customer";
            case VENDOR -> "vendor";
            case COMBINATION -> "combination";
            case CONTRACT -> "contract";
            case USER -> "user";
        };
        return scope + " profile \"" + source.profileName() + "\""
                + (source.scopeType() == ScopeType.COMBINATION && source.label() != null
                        ? " (" + source.label() + ")" : "")
                + (source.effectiveFrom() == null ? "" : " from " + source.effectiveFrom());
    }
}
