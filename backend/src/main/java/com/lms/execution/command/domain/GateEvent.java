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
 * A vehicle entering or leaving a terminal (vision document 3.6.2).
 *
 * <p>Accumulates rather than being corrected in place. The gate log is the
 * evidence in a detention dispute, and evidence that can be edited is not
 * evidence.
 */
@Table("gate_event")
public record GateEvent(
        @Id UUID id,
        UUID tenantId,
        UUID tripId,
        UUID terminalId,
        EventType eventType,
        Instant occurredAt,
        String remarks,
        @Version Long version,
        Instant createdAt) {

    public enum EventType {
        GATE_IN, GATE_OUT
    }

    public static GateEvent record(UUID id, UUID tenantId, UUID tripId, UUID terminalId,
                                   EventType eventType, Instant occurredAt, String remarks) {
        return new GateEvent(id, tenantId, tripId, terminalId, eventType,
                occurredAt == null ? Instant.now() : occurredAt, remarks, null, Instant.now());
    }
}
