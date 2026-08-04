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

import java.util.List;
import java.util.function.Function;

/**
 * One page of results, addressed by cursor rather than offset.
 *
 * <p>Keyset ("seek") pagination, not {@code OFFSET}. The difference matters on
 * a 0.1-CPU instance: {@code OFFSET n} makes the database walk and discard
 * {@code n} rows before returning anything, so page 500 costs roughly 500 times
 * page 1. A keyset predicate is a range scan on the same index that already
 * orders the query, so every page costs the same.
 *
 * <p>It also fixes the correctness problem offsets have: rows inserted or
 * deleted while a client is paging shift every subsequent offset, silently
 * skipping or duplicating results. A cursor anchored to a real row does not
 * drift.
 *
 * <p>The cursor is the last row's sort key, so the ordering column must be
 * unique and stable. Where the natural sort is not unique — a timestamp, a name
 * — it is paired with the primary key to break ties.
 *
 * <p>Consequence, stated plainly: there is no total count and no random access
 * to page N. Both would require the scan this design exists to avoid. Callers
 * that genuinely need a count should ask for one explicitly and accept its cost.
 */
public record Slice<T>(List<T> items, String nextCursor, boolean hasMore) {

    /** The largest page any caller may request, whatever they ask for. */
    public static final int MAX_LIMIT = 200;

    public static final int DEFAULT_LIMIT = 50;

    public Slice {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /**
     * Builds a slice from {@code limit + 1} rows.
     *
     * <p>Fetching one extra row is how "is there more?" is answered without a
     * second count query. The extra row is dropped before returning.
     */
    public static <T> Slice<T> of(List<T> rowsPlusOne, int limit, Function<T, String> cursorOf) {
        boolean hasMore = rowsPlusOne.size() > limit;
        List<T> items = hasMore ? rowsPlusOne.subList(0, limit) : rowsPlusOne;
        String nextCursor = hasMore && !items.isEmpty()
                ? cursorOf.apply(items.get(items.size() - 1))
                : null;
        return new Slice<>(items, nextCursor, hasMore);
    }

    /** Clamps a client-supplied limit into range. */
    public static int clampLimit(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requested, MAX_LIMIT);
    }

    public static <T> Slice<T> empty() {
        return new Slice<>(List.of(), null, false);
    }
}
