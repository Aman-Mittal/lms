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
package com.lms.masterdata.command.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A driver (vision document 3.2.2), with licence validity and hours of service.
 */
@Table("driver")
public record Driver(
        @Id UUID id,
        UUID tenantId,
        UUID orgUnitId,
        UUID employerPartnerId,
        String fullName,
        String phone,
        String licenceNo,
        String licenceClass,
        String licenceAuthority,
        LocalDate licenceExpiresOn,
        boolean hazmatEndorsed,
        DriverStatus status,
        int hosMinutesToday,
        Instant hosResetAt,
        @Version Long version,
        Instant createdAt,
        Instant updatedAt) {

    public enum DriverStatus {
        AVAILABLE, ON_TRIP, REST, INACTIVE
    }

    /**
     * Daily driving limit in minutes (11 hours).
     *
     * <p>A single platform-wide figure. Real hours-of-service rules vary by
     * jurisdiction and are considerably more intricate than one daily cap; this
     * is an honest simplification for the MVP, not a claim of compliance with
     * any particular regime.
     */
    public static final int MAX_DAILY_DRIVING_MINUTES = 11 * 60;

    public static Driver register(UUID id, UUID tenantId, UUID orgUnitId, UUID employerPartnerId,
                                  String fullName, String phone, String licenceNo, String licenceClass,
                                  String licenceAuthority, LocalDate licenceExpiresOn,
                                  boolean hazmatEndorsed) {
        return new Driver(id, tenantId, orgUnitId, employerPartnerId, fullName, phone, licenceNo,
                licenceClass, licenceAuthority, licenceExpiresOn, hazmatEndorsed,
                DriverStatus.AVAILABLE, 0, null, null, Instant.now(), Instant.now());
    }

    public boolean exceedsHoursOfService() {
        return hosMinutesToday >= MAX_DAILY_DRIVING_MINUTES;
    }

    public boolean hasValidLicenceOn(LocalDate on) {
        // A null expiry means the licence does not carry one, which reads as
        // valid; treating it as expired would ground the driver permanently.
        return licenceExpiresOn == null || !licenceExpiresOn.isBefore(on);
    }

    public Driver withHoursOfService(int minutes) {
        return new Driver(id, tenantId, orgUnitId, employerPartnerId, fullName, phone, licenceNo,
                licenceClass, licenceAuthority, licenceExpiresOn, hazmatEndorsed, status,
                minutes, hosResetAt, version, createdAt, Instant.now());
    }
}
