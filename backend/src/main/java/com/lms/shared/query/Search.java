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
 * The text a picker types, turned into a case-insensitive {@code ILIKE}
 * pattern that matches it anywhere in a value.
 *
 * <p>The wildcard characters are escaped, so a code such as {@code BIN_1}
 * matches itself and not {@code BINX1}: a person searching for a code means
 * that code, not a pattern.
 */
public final class Search {

    /** Longer than any code or name a picker searches; anything past it is noise. */
    public static final int MAX_LENGTH = 100;

    private Search() {
    }

    /**
     * The pattern for {@code q}, or null when there is nothing to search for.
     * A null pattern lets a query keep its {@code CAST(:q AS text) IS NULL}
     * branch and read every row, exactly as before the parameter existed.
     */
    public static String containsPattern(String q) {
        if (q == null) {
            return null;
        }
        String trimmed = q.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_LENGTH) {
            trimmed = trimmed.substring(0, MAX_LENGTH);
        }
        String escaped = trimmed.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
