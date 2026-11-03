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
package com.lms.controltower.command.domain;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/** A tenant's tolerances for the ETA and temperature detectors. */
@Table("detector_setting")
public record DetectorSetting(
        @Id UUID id,
        UUID tenantId,
        int etaSlipMinutes,
        int temperatureExcursionMinutes,
        @Version Long version,
        Instant createdAt) {

    public static DetectorSetting of(UUID tenantId, int etaSlipMinutes,
                                     int temperatureExcursionMinutes) {
        return new DetectorSetting(UUID.randomUUID(), tenantId, etaSlipMinutes,
                temperatureExcursionMinutes, null, Instant.now());
    }

    public DetectorSetting with(int etaSlipMinutes, int temperatureExcursionMinutes) {
        return new DetectorSetting(id, tenantId, etaSlipMinutes, temperatureExcursionMinutes,
                version, createdAt);
    }
}
