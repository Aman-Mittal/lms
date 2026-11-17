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
package com.lms.shared.query;

import java.util.Locale;

/**
 * Which records of an active-or-inactive register a list shows: the active
 * ones unless the caller asks for more.
 *
 * <p>{@code status} is ACTIVE, INACTIVE or ALL. A register that already took an
 * {@code active} flag keeps taking it; the status wins when both are given.
 * With neither the list is the active records, so a record switched off
 * leaves the pickers and the default screens without anybody deleting it.
 */
public final class ActiveFilter {

    private ActiveFilter() {
    }

    /**
     * @return TRUE for the active records, FALSE for the inactive ones, null for every record
     * @throws IllegalArgumentException for a status that is none of ACTIVE, INACTIVE or ALL
     */
    public static Boolean of(String status, Boolean active) {
        if (status == null || status.isBlank()) {
            return active == null ? Boolean.TRUE : active;
        }
        return switch (status.strip().toUpperCase(Locale.ROOT)) {
            case "ACTIVE" -> Boolean.TRUE;
            case "INACTIVE" -> Boolean.FALSE;
            case "ALL" -> null;
            default -> throw new IllegalArgumentException(
                    "status must be ACTIVE, INACTIVE or ALL, not " + status);
        };
    }

    /** As {@link #of(String, Boolean)} for a register with no {@code active} flag of its own. */
    public static Boolean of(String status) {
        return of(status, null);
    }
}
