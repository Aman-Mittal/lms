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
package com.lms.execution.command.domain;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A statutory document for this journey: the consignment note, the e-way bill,
 * the delivery challan.
 *
 * <p>Distinct from {@code ComplianceDocument}, which is about the asset -- a
 * vehicle's insurance, a driver's licence. Both are checked at dispatch and
 * neither substitutes for the other: a perfectly insured lorry with no e-way
 * bill is still not going anywhere.
 *
 * <p>A reference, not a file. Object storage is a dependency the free tier
 * cannot carry, and the rule being enforced -- is it accounted for -- is
 * answered by a reference.
 */
@Table("trip_document")
public record TripDocument(
        @Id UUID id,
        UUID tenantId,
        UUID tripId,
        String documentType,
        String documentRef,
        Instant issuedAt,
        @Version Long version,
        Instant createdAt) {

    public static TripDocument attach(UUID id, UUID tenantId, UUID tripId,
                                      String documentType, String documentRef) {
        if (documentType == null || documentType.isBlank()) {
            throw new IllegalArgumentException("A document must have a type");
        }
        if (documentRef == null || documentRef.isBlank()) {
            // A document recorded without a reference is a tick in a box. The
            // whole point of the dispatch check is that somebody can produce
            // the paperwork at a checkpoint.
            throw new IllegalArgumentException(
                    "A document must carry a reference that can be produced on demand");
        }
        return new TripDocument(id, tenantId, tripId, documentType, documentRef,
                Instant.now(), null, Instant.now());
    }
}
