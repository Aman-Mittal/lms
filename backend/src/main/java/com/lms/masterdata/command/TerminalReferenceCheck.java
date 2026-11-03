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
package com.lms.masterdata.command;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Answers "does anything still refer to this terminal", for every table that
 * does.
 *
 * <p>The list of referencing columns is read from the database's own foreign
 * keys, not written down here: a table added next year with a key to terminal
 * is covered the day its migration runs, where a hand-kept list would silently
 * let that table's rows be orphaned -- or, rather, let the delete fall through
 * to the database's RESTRICT and a bare 500. The check is the friendly front
 * for that backstop, not a replacement for it.
 *
 * <p>Keys declared {@code ON DELETE CASCADE} are not references: they hang off
 * the terminal (the trips currently inside its geofence, its rating zone) and
 * go with it.
 *
 * <p>One statement however many tables there are, and each branch is an
 * {@code EXISTS} on {@code (tenant_id, column)}. V34 added an index behind every
 * such column that lacked one, and {@code TerminalReferencePlanTest} fails the
 * build if a new reference has none, so the check costs one index probe per
 * table whatever the size of the tables.
 */
@Component
public class TerminalReferenceCheck {

    private static final String REFERENCES_TO_TERMINAL = """
            SELECT cl.relname AS table_name,
                   att.attname AS column_name,
                   EXISTS (SELECT 1 FROM pg_attribute t
                            WHERE t.attrelid = c.conrelid AND t.attname = 'tenant_id'
                              AND NOT t.attisdropped) AS has_tenant
              FROM pg_constraint c
              JOIN pg_class cl ON cl.oid = c.conrelid
              JOIN pg_namespace ns ON ns.oid = cl.relnamespace AND ns.nspname = current_schema()
              JOIN pg_attribute att ON att.attrelid = c.conrelid AND att.attnum = c.conkey[1]
             WHERE c.contype = 'f'
               AND c.confrelid = to_regclass('terminal')
               AND array_length(c.conkey, 1) = 1
               AND c.confdeltype <> 'c'
             ORDER BY cl.relname, att.attname
            """;

    /** What a person calls the records in each table; anything unlisted is named from its table. */
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("trip", "trips"),
            Map.entry("load_unit", "loads"),
            Map.entry("consignment", "consignments"),
            Map.entry("sales_order", "orders"),
            Map.entry("order_line", "orders"),
            Map.entry("gate_event", "gate events"),
            Map.entry("weighbridge_reading", "weighbridge readings"),
            Map.entry("geofence_event", "geofence events"),
            Map.entry("itinerary_leg", "itineraries"),
            Map.entry("milk_run_route", "milk-run routes"),
            Map.entry("milk_run_route_stop", "milk-run routes"),
            Map.entry("circuit_run", "milk-run circuits"),
            Map.entry("circuit_run_stop", "milk-run circuits"),
            Map.entry("rail_tariff", "rail rate cards"),
            Map.entry("tariff", "rate cards"),
            Map.entry("routing_guide", "routing guides"),
            Map.entry("asn", "warehouse receipts"),
            Map.entry("outbound_order", "warehouse orders"),
            Map.entry("pick_wave", "warehouse waves"),
            Map.entry("handling_unit", "handling units"),
            Map.entry("stock_balance", "warehouse stock"),
            Map.entry("stock_movement", "warehouse stock"),
            Map.entry("warehouse_bin", "warehouse bins"));

    private final JdbcClient jdbc;
    private volatile List<Reference> references;

    public TerminalReferenceCheck(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Reference(String table, String column, boolean hasTenant) {
    }

    /**
     * What still uses the terminal, as short human labels, empty when nothing
     * does. Reads under the caller's tenant scope, so another tenant's rows
     * are invisible here exactly as they are everywhere else.
     */
    public List<String> usedBy(UUID tenantId, UUID terminalId) {
        Set<String> labels = new LinkedHashSet<>();
        jdbc.sql(existenceQuery())
                .param("tenantId", tenantId)
                .param("terminalId", terminalId)
                .query(String.class)
                .list()
                .forEach(table -> labels.add(LABELS.getOrDefault(table, table.replace('_', ' '))));
        return List.copyOf(labels);
    }

    /**
     * The one statement behind {@link #usedBy}, with {@code :tenantId} and
     * {@code :terminalId}: one {@code EXISTS} per referencing column. Public for
     * the plan test, which has to see exactly what runs.
     */
    public String existenceQuery() {
        List<String> branches = new ArrayList<>();
        for (Reference reference : references()) {
            String table = quote(reference.table());
            branches.add("SELECT '" + reference.table() + "' AS used_by WHERE EXISTS (SELECT 1 FROM "
                    + table + " WHERE "
                    + (reference.hasTenant() ? "tenant_id = :tenantId AND " : "")
                    + quote(reference.column()) + " = :terminalId)");
        }
        if (branches.isEmpty()) {
            return "SELECT CAST(NULL AS text) AS used_by WHERE false";
        }
        return String.join("\nUNION ALL\n", branches);
    }

    private List<Reference> references() {
        List<Reference> known = references;
        if (known == null) {
            known = jdbc.sql(REFERENCES_TO_TERMINAL)
                    .query((rs, row) -> new Reference(rs.getString("table_name"),
                            rs.getString("column_name"), rs.getBoolean("has_tenant")))
                    .list();
            // The schema changes only with a migration, which restarts the
            // service, so reading it once is enough.
            references = known;
        }
        return known;
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
