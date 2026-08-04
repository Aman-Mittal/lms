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
package com.lms.identity.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.identity.domain.AppUser;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends CrudRepository<AppUser, UUID> {

    /**
     * The tenant predicate here is layer two of DOCS/adr/0005, not the security
     * boundary -- row-level security would filter the row anyway. It is kept
     * because it makes the intent legible at the call site and keeps the query
     * correct if RLS is ever misconfigured.
     */
    @Query("SELECT * FROM app_user WHERE tenant_id = :tenantId AND lower(email) = lower(:email)")
    Optional<AppUser> findByTenantAndEmail(@Param("tenantId") UUID tenantId, @Param("email") String email);

    /**
     * Users visible to a caller at {@code orgPath}: strictly that unit and
     * everything beneath it (vision document 2.1). Sibling subtrees are
     * excluded.
     */
    @Query("""
            SELECT u.* FROM app_user u
              JOIN org_unit o ON o.id = u.org_unit_id
             WHERE u.tenant_id = :tenantId
               AND o.path LIKE :orgPath || '%'
             ORDER BY u.full_name
            """)
    List<AppUser> findVisibleFrom(@Param("tenantId") UUID tenantId, @Param("orgPath") String orgPath);

    /** Permission codes granted to a user through all of their roles. */
    @Query("""
            SELECT DISTINCT p.code FROM permission p
              JOIN role_permission rp ON rp.permission_id = p.id
              JOIN user_role ur ON ur.role_id = rp.role_id
             WHERE ur.user_id = :userId
            """)
    List<String> findPermissionCodes(@Param("userId") UUID userId);
}
