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
package com.lms.finance.command;

import java.util.Optional;
import java.util.UUID;

import com.lms.shared.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Finance's register of which rating zone each terminal is in, and which
 * delivery points are out of the delivery area.
 *
 * <p>A keyed table with no identity of its own -- a terminal is in one zone --
 * so it is written with an upsert rather than modelled as an aggregate, in the
 * way {@link SettlementHolds} is. The upsert is also what makes re-zoning a
 * terminal safe to retry.
 *
 * <p>Owned here rather than on the terminal in master data. Zones are a
 * pricing construct: two rate cards could zone the same city differently, and
 * a terminal's zone has no meaning to anything but rating.
 */
@Component
public class LtlZones {

    private final JdbcClient jdbc;

    public LtlZones(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Places a terminal in a zone, replacing any earlier placement. */
    public void assign(UUID terminalId, String zoneCode, boolean outOfDeliveryArea) {
        jdbc.sql("""
                        INSERT INTO ltl_zone_terminal (tenant_id, terminal_id, zone_code,
                                                       out_of_delivery_area, updated_at)
                        VALUES (:tenantId, :terminalId, :zoneCode, :oda, now())
                        ON CONFLICT (tenant_id, terminal_id)
                        DO UPDATE SET zone_code = EXCLUDED.zone_code,
                                      out_of_delivery_area = EXCLUDED.out_of_delivery_area,
                                      updated_at = now()
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("terminalId", terminalId)
                .param("zoneCode", zoneCode)
                .param("oda", outOfDeliveryArea)
                .update();
    }

    /** Served by the primary key. Empty when the terminal has not been zoned. */
    public Optional<Placement> placementOf(UUID terminalId) {
        if (terminalId == null) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        SELECT zone_code, out_of_delivery_area FROM ltl_zone_terminal
                         WHERE tenant_id = :tenantId AND terminal_id = :terminalId
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("terminalId", terminalId)
                .query(Placement.class)
                .optional();
    }

    public record Placement(String zoneCode, boolean outOfDeliveryArea) {
    }
}
