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
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.lms.settings.api.ScopeType;
import com.lms.settings.api.SettingCategory;
import com.lms.settings.api.SettingContributor;
import com.lms.settings.api.SettingDefinition;
import com.lms.settings.api.SettingKey;
import com.lms.settings.api.SettingType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Trip execution's settings: the dispatch checklist (the "loading profile")
 * and whether geofence crossings move a trip by themselves.
 *
 * <p>Every key may be set for the tenant, an organisational unit, a vehicle
 * and a customer, and is resolved for the trip's own unit and vehicle and the
 * customer of the freight aboard. A load carrying several customers' freight
 * is dispatched against every one of their checklists: the documents any of
 * them requires, and the tightest weight tolerance. The tracking switches are
 * read for the only customer aboard, or without one for a mixed load.
 *
 * <p>The required documents are a compliance rule (an e-way bill is required
 * by regulation, and the rule changes by notification), so every value is
 * effective-dated and a dispatch is judged by the rule in force on its own
 * date. The system default is what the platform always demanded: a
 * consignment note and an e-way bill.
 */
@Component
public class ExecutionSettingDefinitions implements SettingContributor {

    /**
     * The document types a dispatch checklist may name: what a gate clerk
     * attaches to a trip. A type outside this list may still be attached; it
     * just cannot be made a condition of leaving.
     */
    public static final Set<String> DOCUMENT_TYPES = Set.of("CONSIGNMENT_NOTE", "EWAY_BILL",
            "COMMERCIAL_INVOICE", "DELIVERY_CHALLAN", "PACKING_LIST", "INSURANCE_CERTIFICATE",
            "MATERIAL_SAFETY_DATA_SHEET", "WEIGHBRIDGE_SLIP", "LOADING_CHECKLIST");

    public static final SettingKey<Map<String, Object>> REQUIRED_DOCUMENTS =
            SettingKey.json("execution.dispatch.requiredDocuments");
    public static final SettingKey<BigDecimal> WEIGHT_TOLERANCE =
            SettingKey.decimal("execution.dispatch.weightTolerancePct");
    public static final SettingKey<Boolean> AUTO_DEPARTURE = SettingKey.bool("execution.tracking.autoDeparture");
    public static final SettingKey<Boolean> AUTO_ARRIVAL = SettingKey.bool("execution.tracking.autoArrival");
    public static final SettingKey<Boolean> AUTO_COMPLETION =
            SettingKey.bool("execution.tracking.autoCompleteOnExit");

    private static final ScopeType[] TRIP_SCOPES = {ScopeType.ORG_UNIT, ScopeType.VEHICLE, ScopeType.CUSTOMER};

    private final BigDecimal defaultWeightTolerance;

    public ExecutionSettingDefinitions(@Value("${lms.execution.weight-tolerance-pct:5}")
                                       BigDecimal defaultWeightTolerance) {
        this.defaultWeightTolerance = defaultWeightTolerance;
    }

    @Override
    public List<SettingDefinition> definitions() {
        SettingDefinition.Builder documents = SettingDefinition.of(REQUIRED_DOCUMENTS, "Documents required to dispatch",
                        "The trip documents that must be attached before a trip may leave, as"
                                + " {\"EWAY_BILL\": true, ...}; a type set false or left out is not"
                                + " required. Dated: a dispatch is judged by the rule in force on its day.")
                .defaultValue(Map.of("CONSIGNMENT_NOTE", true, "EWAY_BILL", true))
                .category(SettingCategory.COMPLIANCE).effectiveDated().alsoAt(TRIP_SCOPES)
                .alsoAt(ScopeType.COMBINATION, ScopeType.CONTRACT);
        DOCUMENT_TYPES.stream().sorted().forEach(type -> documents.property(type, SettingType.BOOLEAN, false));
        return List.of(documents.build(),
                SettingDefinition.of(WEIGHT_TOLERANCE, "Dispatch weight tolerance (%)",
                                "How far the weighed payload may differ from the planned weight before"
                                        + " dispatch is refused.")
                        .defaultValue(defaultWeightTolerance).range(0, 50).scale(2).alsoAt(TRIP_SCOPES)
                        .alsoAt(ScopeType.COMBINATION, ScopeType.CONTRACT).build(),
                SettingDefinition.of(AUTO_DEPARTURE, "Depart on leaving the origin",
                                "Whether a dispatched trip goes in transit by itself when its vehicle"
                                        + " leaves the origin's geofence; off leaves it for a person.")
                        .defaultValue(true).alsoAt(TRIP_SCOPES).build(),
                SettingDefinition.of(AUTO_ARRIVAL, "Arrive on entering the destination",
                                "Whether a trip in transit arrives by itself when its vehicle enters the"
                                        + " destination's geofence; off leaves it for a person.")
                        .defaultValue(true).alsoAt(TRIP_SCOPES).build(),
                SettingDefinition.of(AUTO_COMPLETION, "Complete on leaving the destination",
                                "Whether an arrived trip completes by itself when its vehicle leaves the"
                                        + " destination's geofence; off (the default) waits for a person to"
                                        + " complete it.")
                        .defaultValue(false).alsoAt(TRIP_SCOPES).build());
    }

    /** The document types a resolved checklist requires, in name order. */
    static List<String> required(Map<String, Object> checklist) {
        return checklist.entrySet().stream().filter(e -> Boolean.TRUE.equals(e.getValue()))
                .map(Map.Entry::getKey).sorted().toList();
    }
}
