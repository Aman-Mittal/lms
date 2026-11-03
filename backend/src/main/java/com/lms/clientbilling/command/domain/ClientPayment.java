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
package com.lms.clientbilling.command.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Money received from a client.
 *
 * <p>Recorded against the client, not against an invoice: a statement's
 * balance is the client's whole position. The database refuses an update or a
 * delete, and a second receipt with the same remittance reference.
 */
@Table("client_payment")
public record ClientPayment(
        @Id UUID id,
        UUID tenantId,
        UUID clientPartnerId,
        String paymentNo,
        String currency,
        BigDecimal amount,
        LocalDate receivedOn,
        String reference,
        String recordedBy,
        Instant recordedAt,
        @Version Long version,
        Instant createdAt) {

    public static ClientPayment record(UUID id, UUID tenantId, UUID clientPartnerId,
                                       String paymentNo, String currency, BigDecimal amount,
                                       LocalDate receivedOn, String reference, String actor,
                                       Instant at) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("A payment must be for more than nothing");
        }
        if (amount.scale() > 2) {
            throw new IllegalArgumentException("A payment is in whole cents: " + amount);
        }
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException(
                    "A payment needs the client's remittance reference: it is how the same receipt is recognised twice");
        }
        if (receivedOn == null) {
            throw new IllegalArgumentException("A payment needs the date the money arrived");
        }
        return new ClientPayment(id, tenantId, clientPartnerId, paymentNo, currency, amount,
                receivedOn, reference.strip(), actor, at, null, at);
    }
}
