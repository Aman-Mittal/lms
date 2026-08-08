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
 */package com.lms.masterdata.query;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One certificate, and how long it has left.
 *
 * <p>{@code daysToExpiry} is negative once lapsed rather than clamped at zero.
 * "Expired 40 days ago" and "expires today" are different operational
 * situations, and flattening both to nought loses the one that needs
 * escalating.
 */
public record ComplianceDocumentView(
        UUID id,
        String ownerType,
        UUID ownerId,
        String ownerLabel,
        String documentType,
        String documentNo,
        String issuingAuthority,
        LocalDate issuedOn,
        LocalDate expiresOn,
        Long daysToExpiry,
        boolean expired) {
}
