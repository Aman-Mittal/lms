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

/**
 * One step in a ticket's life, kept forever.
 *
 * <p>The ticket says where things stand; this says how they got there, which
 * is what a review after an incident actually reads -- who sat on it, who
 * reopened it, and why.
 */
@Table("exception_activity")
public record ExceptionActivity(
        @Id UUID id,
        UUID tenantId,
        UUID exceptionId,
        String action,
        String fromStatus,
        String toStatus,
        String note,
        String actor,
        Instant occurredAt,
        @Version Long version) {

    public static ExceptionActivity of(UUID tenantId, UUID exceptionId, String action,
                                       ExceptionTicket.Status from, ExceptionTicket.Status to,
                                       String note, String actor, Instant at) {
        return new ExceptionActivity(UUID.randomUUID(), tenantId, exceptionId, action,
                from == null ? null : from.name(), to.name(), note, actor, at, null);
    }
}
