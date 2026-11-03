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
 *
 * <p>No trigram index serves these searches, and adding one would not help.
 * Every tenant table is under row-level security, and Postgres applies a
 * query's own conditions only after the policy's unless they are leakproof.
 * {@code LIKE} and {@code lower()} are not, so a {@code pg_trgm} index on the
 * searched column is never chosen: the search reads the tenant's rows through
 * a tenant_id index and filters them, with or without one. Measured at 20,000
 * partners in one tenant: 34 ms either way, against 5 ms once the two
 * functions are marked leakproof -- which needs a superuser the hosted
 * database does not grant, and would loosen the policy for every query.
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
