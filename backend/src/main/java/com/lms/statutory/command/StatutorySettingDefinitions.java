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
package com.lms.statutory.command;

import java.util.List;
import java.util.Map;

import com.lms.settings.api.ScopeType;
import com.lms.settings.api.SettingContext;
import com.lms.settings.api.SettingContributor;
import com.lms.settings.api.SettingDefinition;
import com.lms.settings.api.SettingKey;
import com.lms.settings.api.SettingType;
import org.springframework.stereotype.Component;

/**
 * The e-way bill compliance keys (issue #231). Every one may be set for the
 * tenant and for an organisational unit (a branch has its own registration and
 * credentials), and is resolved for the consignment's own unit. The defaults
 * leave the feature off, so a tenant that sets nothing sees no change.
 */
@Component
public class StatutorySettingDefinitions implements SettingContributor {

    /** The adapter code: NONE switches the feature off. */
    public static final SettingKey<String> ADAPTER = SettingKey.enumeration("statutory.ewayBill.adapter");
    /** Whether dispatch is refused while a consignment on the trip has no live, unexpired bill. */
    public static final SettingKey<Boolean> REQUIRED_AT_DISPATCH =
            SettingKey.bool("statutory.ewayBill.requiredAtDispatch");
    /** Hours after generation within which a bill may still be cancelled. */
    public static final SettingKey<Integer> CANCEL_WINDOW_HOURS =
            SettingKey.integer("statutory.ewayBill.cancelWindowHours");
    /** Hours before expiry from which the sweep flags a live bill as expiring soon. */
    public static final SettingKey<Integer> EXPIRING_SOON_HOURS =
            SettingKey.integer("statutory.ewayBill.expiringSoonHours");
    /** Seconds an adapter call may take before it is abandoned. */
    public static final SettingKey<Integer> ADAPTER_TIMEOUT_SECONDS =
            SettingKey.integer("statutory.ewayBill.adapterTimeoutSeconds");
    /** Kilometres a bill is valid for per day of validity. */
    public static final SettingKey<Integer> KM_PER_DAY = SettingKey.integer("statutory.ewayBill.kmPerDay");
    /** The distance assumed when a request names none. */
    public static final SettingKey<Integer> DEFAULT_DISTANCE_KM =
            SettingKey.integer("statutory.ewayBill.defaultDistanceKm");
    /** Hours an extension adds to the validity. */
    public static final SettingKey<Integer> EXTENSION_HOURS = SettingKey.integer("statutory.ewayBill.extensionHours");
    /** The unit's registration ({@code gstin}) and a reference to its credentials ({@code credentialRef}). */
    public static final SettingKey<Map<String, Object>> PROFILE = SettingKey.json("statutory.ewayBill.profile");

    /** The adapter values the setting allows; the registry holds the beans for them. */
    public static final String NONE = "NONE";
    public static final String STUB = "STUB";

    private static final ScopeType[] UNIT = {ScopeType.ORG_UNIT};

    @Override
    public List<SettingDefinition> definitions() {
        return List.of(
                SettingDefinition.of(ADAPTER, "E-way bill adapter",
                                "Which adapter raises and changes e-way bills for the unit: NONE (the default)"
                                        + " switches the feature off, STUB is a deterministic stand-in for tests"
                                        + " and the demo.")
                        .defaultValue(NONE).values(NONE, STUB).alsoAt(UNIT).build(),
                SettingDefinition.of(REQUIRED_AT_DISPATCH, "E-way bill required to dispatch",
                                "Whether a trip is refused dispatch while any consignment on it lacks an active,"
                                        + " unexpired e-way bill. Off by default; the EWAY_BILL trip document is"
                                        + " a separate rule of the dispatch checklist.")
                        .defaultValue(false).alsoAt(UNIT).build(),
                SettingDefinition.of(CANCEL_WINDOW_HOURS, "E-way bill cancellation window (hours)",
                                "How long after generation a bill may still be cancelled.")
                        .defaultValue(24).range(1, 720).alsoAt(UNIT).build(),
                SettingDefinition.of(EXPIRING_SOON_HOURS, "E-way bill expiring-soon threshold (hours)",
                                "How many hours before a bill lapses the expiry sweep flags it as expiring soon.")
                        .defaultValue(8).range(1, 168).alsoAt(UNIT).build(),
                SettingDefinition.of(ADAPTER_TIMEOUT_SECONDS, "E-way bill adapter timeout (seconds)",
                                "How long a call to the adapter may take before it is abandoned and recorded"
                                        + " as failed.")
                        .defaultValue(10).range(1, 120).alsoAt(UNIT).build(),
                SettingDefinition.of(KM_PER_DAY, "E-way bill kilometres per day of validity",
                                "The distance a bill is valid for per day: validity is the journey's distance"
                                        + " over this, rounded up to whole days.")
                        .defaultValue(200).range(1, 2_000).alsoAt(UNIT).build(),
                SettingDefinition.of(DEFAULT_DISTANCE_KM, "E-way bill default distance (km)",
                                "The distance used to compute a bill's validity when the request names none.")
                        .defaultValue(100).range(1, 5_000).alsoAt(UNIT).build(),
                SettingDefinition.of(EXTENSION_HOURS, "E-way bill extension (hours)",
                                "How far one extension moves a bill's validity.")
                        .defaultValue(24).range(1, 168).alsoAt(UNIT).build(),
                SettingDefinition.of(PROFILE, "E-way bill registration",
                                "The unit's registration (gstin) and a reference to its credentials"
                                        + " (credentialRef, such as secret-ref:eway/branch-1). The reference is"
                                        + " resolved by the adapter's deployment; the secret itself is never"
                                        + " stored here.")
                        .defaultValue(Map.of())
                        .property("gstin", SettingType.STRING, false)
                        .property("credentialRef", SettingType.STRING, false)
                        .alsoAt(UNIT).build());
    }

    /** Every key a call reads, for one resolution. */
    static List<SettingKey<?>> all() {
        return List.of(ADAPTER, REQUIRED_AT_DISPATCH, CANCEL_WINDOW_HOURS, EXPIRING_SOON_HOURS,
                ADAPTER_TIMEOUT_SECONDS, KM_PER_DAY, DEFAULT_DISTANCE_KM, EXTENSION_HOURS, PROFILE);
    }

    static SettingContext contextOf(java.util.UUID orgUnitId) {
        return SettingContext.ofOrgUnit(orgUnitId);
    }
}
