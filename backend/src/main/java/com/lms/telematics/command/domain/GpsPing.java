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
package com.lms.telematics.command.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.lms.shared.geo.LatLon;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One accepted position report.
 *
 * <p>The only entity in the platform without a {@code @Version} column, and the
 * only one that should be: a ping is written once and never updated, so there
 * is no insert-versus-update ambiguity for Spring Data to get wrong. It is
 * inserted through {@code PingWriter} in a batch rather than through a
 * repository, for the same reason -- twenty individual round trips per device
 * flush is not affordable on 0.1 of a CPU.
 */
@Table("gps_ping")
public record GpsPing(
        @Id UUID id,
        UUID tenantId,
        UUID vehicleId,
        UUID tripId,
        Instant recordedAt,
        BigDecimal lat,
        BigDecimal lon,
        BigDecimal speedKph,
        BigDecimal headingDeg,
        BigDecimal accuracyM,
        Boolean ignitionOn,
        String source,
        Instant createdAt) {

    public LatLon point() {
        return new LatLon(lat.doubleValue(), lon.doubleValue());
    }
}
