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

import java.util.Objects;
import java.util.UUID;

/**
 * The conditions of a COMBINATION profile: each dimension either names one
 * value or is left open (null). The profile applies to a context when every
 * dimension it names holds: the context's unit lies in {@code orgUnitPath}'s
 * subtree, and the customer, vendor, vehicle and freight mode are the ones
 * named.
 *
 * @param orgUnitPath the named unit's materialised path, as read under
 *                    row-level security; null with {@code orgUnitId}
 * @param priority    the last tie-breaker, higher wins
 */
public record SettingCombination(UUID orgUnitId, String orgUnitPath, UUID customerId, UUID vendorId,
                                 UUID vehicleId, String freightMode, int priority) {

    /** A combination names two dimensions at least; one would be that scope's own profile. */
    public static final int MIN_DIMENSIONS = 2;
    public static final int MIN_PRIORITY = -1000;
    public static final int MAX_PRIORITY = 1000;

    /** How many dimensions it names. More wins. */
    public int dimensions() {
        return count(orgUnitId) + count(customerId) + count(vendorId) + count(vehicleId) + count(freightMode);
    }

    /**
     * How deep its unit lies: the number of segments in the unit's path, 0
     * when no unit is named. Of two combinations matching one context, both
     * units lie on the context unit's line of ancestors, so the deeper one is
     * the more specific.
     */
    public int orgDepth() {
        if (orgUnitPath == null) {
            return 0;
        }
        return (int) orgUnitPath.chars().filter(c -> c == '/').count() - 1;
    }

    /**
     * Whether some context could satisfy both: no dimension where both name a
     * different value, and units (when both name one) on one line, one at or
     * beneath the other.
     */
    public boolean couldOverlap(SettingCombination other) {
        if (differ(customerId, other.customerId) || differ(vendorId, other.vendorId)
                || differ(vehicleId, other.vehicleId) || differ(freightMode, other.freightMode)) {
            return false;
        }
        if (orgUnitPath == null || other.orgUnitPath == null) {
            return true;
        }
        return orgUnitPath.startsWith(other.orgUnitPath) || other.orgUnitPath.startsWith(orgUnitPath);
    }

    /**
     * Whether the fold could not choose between the two for some context:
     * they could both apply, and neither has more dimensions, a deeper unit
     * or a higher priority. Two such combinations must not set the same key.
     */
    public boolean tiesWith(SettingCombination other) {
        return couldOverlap(other) && dimensions() == other.dimensions() && orgDepth() == other.orgDepth()
                && priority == other.priority;
    }

    private static boolean differ(Object a, Object b) {
        return a != null && b != null && !Objects.equals(a, b);
    }

    private static int count(Object dimension) {
        return dimension == null ? 0 : 1;
    }
}
