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
package com.lms.settings.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.lms.settings.api.ScopeType;

/**
 * Where a value came from, in words: the scope with its level ("Country:
 * India", "Zone: North", "Branch: Gurgaon"), or a combination's conditions
 * ("Branch Gurgaon + Customer Acme").
 *
 * <p>The organisational level words mirror {@code identity}'s unit types,
 * which this module reads by table name rather than importing.
 */
public final class ScopeLabels {

    private static final Map<String, String> LEVELS = Map.of(
            "HQ", "Head office", "LOB", "Line of business", "COUNTRY", "Country", "ZONE", "Zone",
            "REGION", "Region", "BRANCH", "Branch", "HUB", "Hub");

    /** Rank of each unit type: HQ 0 down to HUB 5; REGION ranks as a zone. */
    private static final Map<String, Integer> RANKS = Map.of(
            "HQ", 0, "LOB", 1, "COUNTRY", 2, "ZONE", 3, "REGION", 3, "BRANCH", 4, "HUB", 5);

    private ScopeLabels() {
    }

    /** "Branch", "Country"; the type itself for one this module does not know. */
    public static String level(String unitType) {
        return unitType == null ? "Org unit" : LEVELS.getOrDefault(unitType, unitType);
    }

    /** The unit type's rank, or -1 for one this module does not know. */
    public static int rank(String unitType) {
        return unitType == null ? -1 : RANKS.getOrDefault(unitType, -1);
    }

    /** What a combination profile's conditions name, each in words. */
    public record Parts(String unitType, String unitName, String customer, String vendor, String vehicle,
                        String freightMode) {
    }

    /** "Branch Gurgaon + Customer Acme + Mode LTL"; empty when nothing is named. */
    public static String combination(Parts p) {
        List<String> out = new ArrayList<>();
        if (p.unitName() != null) {
            out.add(level(p.unitType()) + " " + p.unitName());
        }
        if (p.customer() != null) {
            out.add("Customer " + p.customer());
        }
        if (p.vendor() != null) {
            out.add("Vendor " + p.vendor());
        }
        if (p.vehicle() != null) {
            out.add("Vehicle " + p.vehicle());
        }
        if (p.freightMode() != null) {
            out.add("Mode " + p.freightMode());
        }
        return String.join(" + ", out);
    }

    /**
     * The provenance of a profile's value.
     *
     * @param unitType  an ORG_UNIT profile's unit type
     * @param unitName  an ORG_UNIT profile's unit name
     * @param combined  a COMBINATION profile's conditions, from {@link #combination}
     */
    public static String source(ScopeType scopeType, String profileName, String unitType, String unitName,
                                String country, String state, String combined) {
        return switch (scopeType) {
            case PLATFORM -> country == null ? "Platform"
                    : "Platform: " + country + (state == null ? "" : "-" + state);
            case ORG_DEFAULT -> "Organisation default";
            case ORG_UNIT -> level(unitType) + ": " + (unitName == null ? profileName : unitName);
            case VEHICLE -> "Vehicle: " + profileName;
            case CUSTOMER -> "Customer: " + profileName;
            case VENDOR -> "Vendor: " + profileName;
            case COMBINATION -> combined == null || combined.isEmpty() ? "Combination: " + profileName : combined;
            case USER -> "You";
        };
    }
}
