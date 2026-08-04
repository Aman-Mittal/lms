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

import java.util.Optional;
import java.util.UUID;

import com.lms.identity.domain.Tenant;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface TenantRepository extends CrudRepository<Tenant, UUID> {

    /**
     * Resolves a tenant code to its id without a tenant scope in effect.
     *
     * <p>The login bootstrapping case: the caller knows a code but no scope
     * exists yet, and the {@code tenant} table is protected by row-level
     * security, so an ordinary query would correctly return nothing.
     *
     * <p>This delegates to a {@code SECURITY DEFINER} function that returns an
     * id and nothing else -- the single, deliberate escape hatch described in
     * DOCS/adr/0005. Do not add more of these; establish the scope first and
     * query normally.
     */
    @Query("SELECT resolve_tenant_by_code(:code)")
    Optional<UUID> resolveIdByCode(@Param("code") String code);
}
