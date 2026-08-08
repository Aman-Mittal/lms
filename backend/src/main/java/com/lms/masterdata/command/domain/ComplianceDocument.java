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
 * A statutory document belonging to a partner, vehicle, driver or trip
 * (vision document 3.2.1 and 3.2.2).
 *
 * <p>One table for all four owners because the question asked of it is always
 * the same — is this still valid on the day of dispatch — and three or four
 * near-identical tables would drift apart the first time one of them gained a
 * field.
 */
@Table("compliance_document")
public record ComplianceDocument(
        @Id UUID id,
        UUID tenantId,
        OwnerType ownerType,
        UUID ownerId,
        String documentType,
        String documentNo,
        String issuingAuthority,
        LocalDate issuedOn,
        LocalDate expiresOn,
        String fileRef,
        /* Same reason as every other aggregate root: without it Spring Data
         * JDBC treats the pre-assigned @Id as an existing row and the insert
         * silently becomes an UPDATE that matches nothing. */
        @Version Long version,
        Instant createdAt) {

    public enum OwnerType {
        PARTNER, VEHICLE, DRIVER, TRIP
    }

    /**
     * Whether the document has lapsed as at {@code on}.
     *
     * <p>A null expiry means the document does not expire — a permanent
     * registration, for instance — and must read as valid. Treating null as
     * expired would block dispatch for every vehicle whose paperwork simply has
     * no end date, which is the more common case, not the exception.
     *
     * <p>Expiry is inclusive of the stated day: a certificate expiring today is
     * still valid today.
     */
    public boolean isExpiredOn(LocalDate on) {
        return expiresOn != null && expiresOn.isBefore(on);
    }

    public boolean isValidOn(LocalDate on) {
        return !isExpiredOn(on);
    }
}
