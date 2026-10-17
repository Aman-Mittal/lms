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

import java.util.Optional;
import java.util.UUID;

import com.lms.identity.command.domain.AccessPolicy;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface AccessPolicyRepository extends CrudRepository<AccessPolicy, UUID> {

    @Query("SELECT * FROM access_policy WHERE tenant_id = :tenantId AND code = :code")
    Optional<AccessPolicy> findByCode(@Param("tenantId") UUID tenantId, @Param("code") String code);

    /**
     * Whether {@code code} is in the permission vocabulary.
     *
     * <p>A policy on an action that does not exist would be consulted by
     * nothing, ever, and look to its author as if it were working.
     */
    @Query("SELECT EXISTS (SELECT 1 FROM permission WHERE code = :code)")
    boolean isPermissionCode(@Param("code") String code);
}
