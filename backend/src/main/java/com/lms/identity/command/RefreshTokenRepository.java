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
package com.lms.identity.command;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Refresh token storage.
 *
 * <p>Extends the bare {@link Repository} marker rather than {@code CrudRepository}:
 * refresh tokens are written once and revoked, never generally updated, and a
 * full CRUD surface would invite exactly the operations that should not exist.
 *
 * <p>Note that {@link #findActiveByHash} carries no tenant predicate. It is
 * reached before a tenant scope exists, during token refresh; the hash is a
 * 256-bit unguessable value that identifies the tenant rather than presupposing
 * it. This is the one place that is intentionally tenant-agnostic, and the
 * tenant is established from the result before anything else is touched.
 */
public interface RefreshTokenRepository extends Repository<RefreshTokenRepository.StoredToken, UUID> {

    record StoredToken(
            UUID id,
            UUID tenantId,
            UUID userId,
            String tokenHash,
            String deviceFingerprint,
            Instant expiresAt,
            Instant revokedAt) {
    }

    @Query("""
            SELECT id, tenant_id, user_id, token_hash, device_fingerprint, expires_at, revoked_at
              FROM refresh_token
             WHERE token_hash = :hash
               AND revoked_at IS NULL
               AND expires_at > :now
            """)
    Optional<StoredToken> findActiveByHash(@Param("hash") String hash, @Param("now") Instant now);

    @Modifying
    @Query("""
            INSERT INTO refresh_token (id, tenant_id, user_id, token_hash, device_fingerprint, expires_at)
            VALUES (:id, :tenantId, :userId, :hash, :deviceFingerprint, :expiresAt)
            """)
    void store(@Param("id") UUID id,
               @Param("tenantId") UUID tenantId,
               @Param("userId") UUID userId,
               @Param("hash") String hash,
               @Param("deviceFingerprint") String deviceFingerprint,
               @Param("expiresAt") Instant expiresAt);

    @Modifying
    @Query("UPDATE refresh_token SET revoked_at = :at WHERE id = :id AND revoked_at IS NULL")
    void revoke(@Param("id") UUID id, @Param("at") Instant at);

    /** Housekeeping: the 1 GB database cannot accumulate dead tokens forever. */
    @Modifying
    @Query("DELETE FROM refresh_token WHERE expires_at < :before")
    int deleteExpiredBefore(@Param("before") Instant before);
}
