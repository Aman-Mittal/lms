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
package com.lms.sourcing.command;

import java.util.List;
import java.util.UUID;

import com.lms.sourcing.command.domain.AllocationRule;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface AllocationRuleRepository extends CrudRepository<AllocationRule, UUID> {

    /** The rules that can fire, in the order they are tried. Served by {@code allocation_rule_order_idx}. */
    @Query("""
            SELECT * FROM allocation_rule
             WHERE tenant_id = :tenantId AND active
             ORDER BY priority, created_at, id
            """)
    List<AllocationRule> findActive(@Param("tenantId") UUID tenantId);
}
