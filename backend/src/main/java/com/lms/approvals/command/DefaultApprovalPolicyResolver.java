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
package com.lms.approvals.command;

import java.sql.Array;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import com.lms.approvals.api.ApprovalPolicy;
import com.lms.approvals.api.ApprovalPolicyResolver;
import com.lms.shared.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The code default, overlaid with the tenant's own values from
 * {@code approval_policy_override} column by column: a null column keeps the
 * default. A mandatory flow stays required whatever the row says.
 *
 * <p>This is the seam profile settings will replace (see
 * {@link ApprovalPolicyResolver}). Until then the override table is the
 * per-tenant switch, set through {@code PUT /api/v1/approvals/policies/{type}}.
 *
 * <p>One indexed read (the table's primary key) per resolution, in the
 * caller's transaction.
 */
@Service
public class DefaultApprovalPolicyResolver implements ApprovalPolicyResolver {

    private final JdbcClient jdbc;

    public DefaultApprovalPolicyResolver(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ApprovalPolicy resolve(String subjectType) {
        ApprovalPolicy base = ApprovalPolicyDefaults.of(subjectType)
                .orElseThrow(() -> new IllegalArgumentException("No approval policy for " + subjectType));
        return jdbc.sql("""
                        SELECT required, decide_permissions, ordered, allow_self_approval, due_minutes, on_expiry
                          FROM approval_policy_override
                         WHERE tenant_id = :tenantId AND subject_type = :subjectType
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("subjectType", subjectType)
                .query((rs, row) -> {
                    Boolean required = (Boolean) rs.getObject("required");
                    List<String> steps = strings(rs.getArray("decide_permissions"));
                    Boolean ordered = (Boolean) rs.getObject("ordered");
                    Boolean self = (Boolean) rs.getObject("allow_self_approval");
                    Integer due = (Integer) rs.getObject("due_minutes");
                    String expiry = rs.getString("on_expiry");
                    return new ApprovalPolicy(subjectType,
                            required == null ? base.required() : required,
                            base.mandatory(),
                            steps == null ? base.decidePermissions() : steps,
                            ordered == null ? base.ordered() : ordered,
                            self == null ? base.allowSelfApproval() : self,
                            due == null ? base.due() : Duration.ofMinutes(due),
                            expiry == null ? base.onExpiry() : ApprovalPolicy.Expiry.valueOf(expiry));
                })
                .optional()
                .orElse(base);
    }

    private static List<String> strings(Array array) throws SQLException {
        if (array == null) {
            return null;
        }
        return Arrays.asList((String[]) array.getArray());
    }
}
