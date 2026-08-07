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
package com.lms.identity.command.domain;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/** A person who can authenticate, scoped to one tenant and one organisational unit. */
@Table("app_user")
public record AppUser(
        @Id UUID id,
        UUID tenantId,
        UUID orgUnitId,
        String email,
        String passwordHash,
        String fullName,
        UserStatus status,
        Instant lastLoginAt,
        @Version Long version,
        Instant createdAt,
        Instant updatedAt) {

    public enum UserStatus {
        ACTIVE, SUSPENDED, LOCKED
    }

    public boolean canAuthenticate() {
        return status == UserStatus.ACTIVE;
    }

    public AppUser withLastLoginAt(Instant at) {
        return new AppUser(id, tenantId, orgUnitId, email, passwordHash, fullName,
                status, at, version, createdAt, Instant.now());
    }

    /**
     * Keeps the password hash out of logs and error messages. Spring Data JDBC
     * does not need {@code toString}, and a record's default one would print
     * every component.
     */
    @Override
    public String toString() {
        return "AppUser[id=" + id + ", tenantId=" + tenantId + ", email=" + email
                + ", status=" + status + "]";
    }
}
