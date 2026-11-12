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
package com.lms.execution.command.domain;

import java.util.List;
import java.util.Locale;

/**
 * How a vehicle's registration number is recognised at the gate: upper case,
 * with no blanks, so "mh 12 ab 1234" and "MH12AB1234" are one vehicle. A
 * visitor's lorry has no master-data record, and this is the whole of its
 * identity.
 */
public final class Registrations {

    private Registrations() {
    }

    /** The recognised form, or null for a blank. */
    public static String normalise(String registrationNo) {
        if (registrationNo == null) {
            return null;
        }
        String squeezed = registrationNo.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        return squeezed.isEmpty() ? null : squeezed;
    }

    /** Seal numbers as stored: trimmed, blanks dropped, in the order given. */
    public static String[] seals(List<String> sealNos) {
        if (sealNos == null) {
            return new String[0];
        }
        return sealNos.stream().filter(s -> s != null && !s.isBlank()).map(String::strip)
                .toArray(String[]::new);
    }
}
