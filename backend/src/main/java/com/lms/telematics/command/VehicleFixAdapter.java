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
package com.lms.telematics.command;

import java.util.Optional;
import java.util.UUID;

import com.lms.shared.tenant.TenantContext;
import com.lms.telematics.api.VehicleFixPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers {@link VehicleFixPort} from the ping table.
 *
 * <p>One row from {@code gps_ping_vehicle_idx}, which already orders by
 * device time descending -- the same index the live map reads.
 */
@Component
public class VehicleFixAdapter implements VehicleFixPort {

    private final JdbcClient jdbc;

    public VehicleFixAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Fix> latestFix(UUID vehicleId) {
        return jdbc.sql("""
                        SELECT lat, lon, recorded_at
                          FROM gps_ping
                         WHERE tenant_id = :tenantId AND vehicle_id = :vehicleId
                         ORDER BY recorded_at DESC
                         LIMIT 1
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("vehicleId", vehicleId)
                // Mapped by hand rather than with query(Fix.class). Reflective
                // record mapping needs a native-image hint, and Spring AOT only
                // infers those for types a controller returns -- which Fix
                // never is, so it would work on the JVM and fail in the binary.
                .query((rs, row) -> new Fix(rs.getBigDecimal("lat"), rs.getBigDecimal("lon"),
                        rs.getTimestamp("recorded_at").toInstant()))
                .optional();
    }
}
