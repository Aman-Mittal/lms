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
package com.lms.identity.command;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Which tables a demo reset clears, and in what order.
 *
 * <p>The list is not written down: it is every table with a {@code tenant_id}
 * column, read from the catalog at the moment of the reset, so a table added
 * by a later migration is cleared without anybody remembering to add it. What
 * is written down is the opposite -- {@link #KEPT}, the tables a reset leaves
 * alone, each with its reason -- and DemoResetCoverageIntegrationTest fails if
 * an entry there names a table that does not exist.
 *
 * <p>Order comes from the foreign keys: a table is cleared before every table
 * it references, so no delete is refused for a row still pointed at. A
 * self-reference (an org unit's parent) is cleared in one statement and needs
 * no order. Two things make a plan impossible, and both fail loudly rather
 * than leave rows behind: a cycle between tables, and a table with no
 * {@code tenant_id} that references a tenant table -- its rows could not be
 * cleared, and would block the parent's.
 */
final class DemoResetPlan {

    /** Tables a reset keeps, with the reason. Empty today: everything a tenant owns is demo data. */
    static final Map<String, String> KEPT = Map.of();

    /** The tenant row itself is never cleared; the reset keeps its id so tokens still name it. */
    static final String TENANT_TABLE = "tenant";

    /** A foreign key from {@code child} to {@code parent}. */
    record Reference(String child, String parent) {
    }

    private DemoResetPlan() {
    }

    /**
     * The tables to clear, each before the tables it references.
     *
     * @param tenantTables every base table with a {@code tenant_id} column
     * @param references   every foreign key in the schema
     * @throws IllegalStateException when no safe order exists
     */
    static List<String> deletionOrder(Collection<String> tenantTables, Collection<Reference> references) {
        Set<String> tables = new TreeSet<>(tenantTables);
        tables.removeAll(KEPT.keySet());

        List<String> unclearable = new ArrayList<>();
        // parent -> children still to clear before it
        Map<String, Set<String>> waitingOn = new TreeMap<>();
        for (String table : tables) {
            waitingOn.put(table, new TreeSet<>());
        }
        for (Reference reference : references) {
            if (reference.child().equals(reference.parent()) || TENANT_TABLE.equals(reference.parent())) {
                continue;
            }
            boolean childCleared = tables.contains(reference.child());
            boolean parentCleared = tables.contains(reference.parent());
            if (parentCleared && !childCleared && !KEPT.containsKey(reference.child())) {
                unclearable.add(reference.child() + " -> " + reference.parent());
            }
            if (parentCleared && childCleared) {
                waitingOn.get(reference.parent()).add(reference.child());
            }
        }
        if (!unclearable.isEmpty()) {
            throw new IllegalStateException("Tables without a tenant_id reference tenant data, so a reset "
                    + "could not clear them: " + unclearable);
        }

        List<String> order = new ArrayList<>();
        Deque<String> ready = new ArrayDeque<>();
        waitingOn.forEach((table, children) -> {
            if (children.isEmpty()) {
                ready.add(table);
            }
        });
        while (!ready.isEmpty()) {
            String cleared = ready.poll();
            order.add(cleared);
            waitingOn.remove(cleared);
            for (var entry : waitingOn.entrySet()) {
                if (entry.getValue().remove(cleared) && entry.getValue().isEmpty()) {
                    ready.add(entry.getKey());
                }
            }
        }
        if (!waitingOn.isEmpty()) {
            throw new IllegalStateException("Foreign keys form a cycle, so no table can be cleared first: "
                    + waitingOn.keySet());
        }
        return order;
    }
}
