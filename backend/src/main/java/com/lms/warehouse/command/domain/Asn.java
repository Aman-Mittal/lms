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
package com.lms.warehouse.command.domain;

import java.time.Instant;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * An advance shipping notice: what a supplier says is on its way.
 *
 * <p>Received exactly once. A second count of the same delivery would put the
 * same goods into stock twice, so a retry after the first receipt is refused
 * here and answered by the idempotency layer above.
 */
@Table("asn")
public record Asn(
        @Id UUID id,
        UUID tenantId,
        UUID warehouseId,
        String asnNo,
        UUID supplierPartnerId,
        Status status,
        boolean crossDock,
        String receivedBy,
        Instant receivedAt,
        @Version Long version,
        Instant createdAt) {

    public enum Status {
        EXPECTED,
        RECEIVED
    }

    public static Asn expect(UUID tenantId, UUID warehouseId, String asnNo,
                             UUID supplierPartnerId, Instant at) {
        if (asnNo == null || asnNo.isBlank()) {
            throw new BusinessRuleViolationException("asn-number-required",
                    "An ASN needs a number the supplier will quote");
        }
        return new Asn(UUID.randomUUID(), tenantId, warehouseId, asnNo.strip(),
                supplierPartnerId, Status.EXPECTED, false, null, null, null, at);
    }

    public Asn receive(boolean toCrossDock, String actor, Instant at) {
        if (status != Status.EXPECTED) {
            throw new BusinessRuleViolationException("asn-already-received",
                    "ASN " + asnNo + " was already received at " + receivedAt);
        }
        return new Asn(id, tenantId, warehouseId, asnNo, supplierPartnerId, Status.RECEIVED,
                toCrossDock, actor, at, version, createdAt);
    }
}
