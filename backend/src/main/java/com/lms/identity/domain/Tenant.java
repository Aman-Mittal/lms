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
package com.lms.identity.domain;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/** A corporate entity: the top-level isolation container (vision document 2.1). */
@Table("tenant")
public record Tenant(
        @Id UUID id,
        String code,
        String name,
        TenantStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public enum TenantStatus {
        ACTIVE, SUSPENDED, CLOSED
    }

    public boolean isActive() {
        return status == TenantStatus.ACTIVE;
    }
}
