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
package com.lms.sourcing.query;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.lms.shared.query.Slice;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the allocation rules.
 *
 * <p>Listed in the order they are tried, which is the order that matters to
 * whoever is reading them: the first one that matches wins.
 */
@Service
@Transactional(readOnly = true)
public class AllocationRuleQueryService {

    /** Sorts as the rules are tried; the key doubles as the keyset cursor. */
    private static final String ORDER_KEY = "(lpad(r.priority::text, 6, '0') || ':' || r.id::text)";

    private static final String SELECT = """
            SELECT r.id, r.name, r.priority, r.active, r.origin_terminal_id, r.destination_terminal_id,
                   r.min_weight_kg, r.max_weight_kg, r.material_class, r.action,
                   r.vendor_partner_ids, r.created_at, r.updated_at
              FROM allocation_rule r
            """;

    private final JdbcClient jdbc;

    public AllocationRuleQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @PreAuthorize("hasAuthority('ALLOCATION_RULE_READ')")
    public Optional<AllocationRuleView> find(UUID id) {
        List<AllocationRuleView> rows = withVendors(jdbc.sql(SELECT + " WHERE r.id = :id")
                .param("id", id).query(this::row).list());
        return rows.stream().findFirst();
    }

    @PreAuthorize("hasAuthority('ALLOCATION_RULE_READ')")
    public Slice<AllocationRuleView> list(Boolean active, String cursor, Integer limit) {
        int pageSize = Slice.clampLimit(limit);
        List<AllocationRuleView> rows = jdbc.sql(SELECT + " WHERE (CAST(:active AS boolean) IS NULL OR r.active = CAST(:active AS boolean))"
                        + " AND (CAST(:cursor AS text) IS NULL OR " + ORDER_KEY + " > CAST(:cursor AS text))"
                        + " ORDER BY " + ORDER_KEY + " LIMIT :limit")
                .param("active", active)
                .param("cursor", cursor)
                .param("limit", pageSize + 1)
                .query(this::row).list();
        List<AllocationRuleView> named = withVendors(rows);
        return Slice.of(named, pageSize, r -> String.format("%06d:%s", r.priority(), r.id()));
    }

    /** One query for every vendor name on the page, rather than one per rule. */
    private List<AllocationRuleView> withVendors(List<AllocationRuleView> rows) {
        List<UUID> ids = rows.stream().flatMap(r -> r.vendors().stream())
                .map(AllocationRuleView.VendorRef::vendorPartnerId).distinct().toList();
        if (ids.isEmpty()) {
            return rows;
        }
        Map<UUID, String> names = jdbc.sql("SELECT id, legal_name FROM business_partner WHERE id IN (:ids)")
                .param("ids", ids)
                .query((rs, n) -> Map.entry(rs.getObject("id", UUID.class), rs.getString("legal_name")))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return rows.stream().map(r -> new AllocationRuleView(r.id(), r.name(), r.priority(), r.active(),
                r.originTerminalId(), r.destinationTerminalId(), r.minWeightKg(), r.maxWeightKg(),
                r.materialClass(), r.action(),
                r.vendors().stream().map(v -> new AllocationRuleView.VendorRef(v.vendorPartnerId(),
                        names.getOrDefault(v.vendorPartnerId(), null))).toList(),
                r.createdAt(), r.updatedAt())).toList();
    }

    private AllocationRuleView row(ResultSet rs, int n) throws SQLException {
        Array array = rs.getArray("vendor_partner_ids");
        List<AllocationRuleView.VendorRef> vendors = new ArrayList<>();
        for (Object o : (Object[]) array.getArray()) {
            vendors.add(new AllocationRuleView.VendorRef((UUID) o, null));
        }
        BigDecimal min = rs.getBigDecimal("min_weight_kg");
        BigDecimal max = rs.getBigDecimal("max_weight_kg");
        return new AllocationRuleView(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getInt("priority"), rs.getBoolean("active"),
                rs.getObject("origin_terminal_id", UUID.class),
                rs.getObject("destination_terminal_id", UUID.class), min, max,
                rs.getString("material_class"), rs.getString("action"), vendors,
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }
}
