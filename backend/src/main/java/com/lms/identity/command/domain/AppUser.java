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
        int failedLoginAttempts,
        Instant lockedUntil,
        Instant lastFailedLoginAt,
        @Version Long version,
        Instant createdAt,
        Instant updatedAt) {

    public enum UserStatus {
        ACTIVE, SUSPENDED, LOCKED
    }

    public boolean canAuthenticate() {
        return status == UserStatus.ACTIVE;
    }

    /**
     * How many consecutive failures lock the account.
     *
     * <p>Five is enough for a person mistyping a password and far too few for
     * anybody working through a list.
     */
    public static final int MAX_FAILED_ATTEMPTS = 5;

    /**
     * How long a lockout lasts.
     *
     * <p>Fifteen minutes, not forever. A permanent lock turns an attack on an
     * account into a denial of service against its owner -- anybody who knows
     * an email address could lock its holder out indefinitely -- and needs an
     * administrator to undo, which on a free instance means nobody.
     */
    public static final java.time.Duration LOCKOUT = java.time.Duration.ofMinutes(15);

    /**
     * Consecutive failures older than this are forgotten.
     *
     * <p>Three wrong passwords in a minute is an attack; three across three
     * months is a person with a bad memory, and locking them out teaches them
     * the system is unreliable.
     */
    private static final java.time.Duration ATTEMPT_MEMORY = java.time.Duration.ofHours(1);

    public boolean isLockedAt(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    /** Counts a failure, locking the account once the threshold is reached. */
    public AppUser withFailedLogin(Instant now) {
        boolean stale = lastFailedLoginAt == null
                || lastFailedLoginAt.isBefore(now.minus(ATTEMPT_MEMORY));
        int attempts = (stale ? 0 : failedLoginAttempts) + 1;

        Instant lockUntil = attempts >= MAX_FAILED_ATTEMPTS ? now.plus(LOCKOUT) : null;

        return new AppUser(id, tenantId, orgUnitId, email, passwordHash, fullName,
                status, lastLoginAt, attempts, lockUntil, now, version, createdAt, Instant.now());
    }

    public AppUser withLastLoginAt(Instant at) {
        // A successful sign-in clears the counter. Anything else would let a
        // legitimate user accumulate their way into a lockout across months of
        // occasional typos.
        return new AppUser(id, tenantId, orgUnitId, email, passwordHash, fullName,
                status, at, 0, null, null, version, createdAt, Instant.now());
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
