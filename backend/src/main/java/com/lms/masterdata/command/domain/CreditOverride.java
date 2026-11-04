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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A one-time pass through a customer's hard credit limit for one subject.
 *
 * <p>Granted before the refused action is tried again, by someone holding the
 * override right; used up by the first action it lets through, and lapsing
 * unused after {@link #VALIDITY}. It covers exposure up to {@code upTo} and no
 * further, so a pass given for one figure cannot carry a larger one.
 */
@Table("credit_override")
public record CreditOverride(
        @Id UUID id,
        UUID tenantId,
        UUID partnerId,
        SubjectType subjectType,
        String subjectKey,
        BigDecimal upTo,
        String reason,
        String grantedBy,
        Instant grantedAt,
        Instant expiresAt,
        Instant usedAt,
        BigDecimal usedExposure,
        @Version Long version) {

    /** How long an unused override stays good. */
    public static final Duration VALIDITY = Duration.ofHours(24);

    public enum SubjectType {
        ORDER, CLIENT_INVOICE
    }

    public static CreditOverride grant(UUID id, UUID tenantId, UUID partnerId,
                                       SubjectType subjectType, String subjectKey,
                                       BigDecimal upTo, String reason, String grantedBy,
                                       Instant at) {
        if (upTo == null || upTo.signum() < 0) {
            throw new IllegalArgumentException("An override must say up to what exposure it covers");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("An override needs a reason");
        }
        return new CreditOverride(id, tenantId, partnerId, subjectType, subjectKey, upTo,
                reason.strip(), grantedBy, at, at.plus(VALIDITY), null, null, null);
    }

    /** Whether this override lets {@code exposure} through at {@code now}. */
    public boolean covers(BigDecimal exposure, Instant now) {
        return usedAt == null && now.isBefore(expiresAt) && exposure.compareTo(upTo) <= 0;
    }

    public CreditOverride use(BigDecimal exposure, Instant at) {
        return new CreditOverride(id, tenantId, partnerId, subjectType, subjectKey, upTo, reason,
                grantedBy, grantedAt, expiresAt, at, exposure, version);
    }
}
