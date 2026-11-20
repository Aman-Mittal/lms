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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.lms.identity.command.domain.Role;
import com.lms.shared.audit.AuditTrail;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Custom roles and what each role grants (issue 258).
 *
 * <p>Every tenant starts with Administrator, Planner, Dispatcher, Finance and
 * Viewer (V66). A tenant adds its own roles from the permission vocabulary and
 * edits what any role other than Administrator grants, under the version it
 * read. Administrator is fixed at "everything": editing or deleting it would be
 * the other way, besides removing the last holder, to lock everyone out of
 * administration ({@code administrator-role-fixed}). A role somebody holds
 * cannot be deleted ({@code role-in-use}); it is taken from its holders first.
 * Every change is audited.
 */
@Service
public class RoleAdminService {

    static final String ADMIN_ROLE_CODE = "ADMIN";
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");

    private final RoleRepository roles;
    private final JdbcClient jdbc;
    private final AuditTrail audit;

    public RoleAdminService(RoleRepository roles, JdbcClient jdbc, AuditTrail audit) {
        this.roles = roles;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional
    @PreAuthorize("hasAuthority('ROLE_MANAGE')")
    public UUID create(String code, String name, List<String> permissionCodes) {
        UUID tenantId = TenantContext.requireTenantId();
        String normalised = code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(normalised).matches()) {
            throw new IllegalArgumentException(
                    "A role code is 2 to 40 characters: capital letters, digits and underscores, starting with a letter");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A role name is required");
        }
        Long taken = jdbc.sql("SELECT count(*) FROM role WHERE tenant_id = :t AND code = :c")
                .param("t", tenantId).param("c", normalised).query(Long.class).single();
        if (taken != null && taken > 0) {
            throw new BusinessRuleViolationException("role-code-taken", "Role code " + normalised + " is already in use");
        }
        Set<String> wanted = known(permissionCodes);
        Role saved = roles.save(Role.create(UUID.randomUUID(), tenantId, normalised, name.strip()));
        grant(tenantId, saved.id(), wanted);
        audit.recordAtomically("ROLE_CREATED", "ROLE", saved.id(), null, snapshot(saved, wanted));
        return saved.id();
    }

    /** Renames the role and replaces what it grants. */
    @Transactional
    @PreAuthorize("hasAuthority('ROLE_MANAGE')")
    public void update(UUID id, long expectedVersion, String name, List<String> permissionCodes) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A role name is required");
        }
        UUID tenantId = TenantContext.requireTenantId();
        Role before = roles.findById(id).orElseThrow(() -> new ResourceNotFoundException("Role", id));
        if (before.version() == null || before.version() != expectedVersion) {
            throw new OptimisticLockingFailureException("Role " + before.code() + " changed since it was read");
        }
        requireNotAdministrator(before);
        Set<String> wanted = known(permissionCodes);
        Set<String> had = permissionsOf(id);
        jdbc.sql("DELETE FROM role_permission WHERE tenant_id = :t AND role_id = :r")
                .param("t", tenantId).param("r", id).update();
        grant(tenantId, id, wanted);
        // Saved even when only the grants changed, so the version moves and a
        // second editor holding the old one is refused.
        Role saved = roles.save(before.renamed(name.strip()));
        audit.recordAtomically("ROLE_UPDATED", "ROLE", id, snapshot(before, had), snapshot(saved, wanted));
    }

    @Transactional
    @PreAuthorize("hasAuthority('ROLE_MANAGE')")
    public void delete(UUID id) {
        Role before = roles.findById(id).orElseThrow(() -> new ResourceNotFoundException("Role", id));
        requireNotAdministrator(before);
        Long holders = jdbc.sql("SELECT count(*) FROM user_role WHERE role_id = :r")
                .param("r", id).query(Long.class).single();
        if (holders != null && holders > 0) {
            throw new BusinessRuleViolationException("role-in-use",
                    "Role " + before.code() + " is held by " + holders + " user" + (holders == 1 ? "" : "s")
                            + " and cannot be deleted");
        }
        Set<String> had = permissionsOf(id);
        roles.deleteById(id);
        audit.recordAtomically("ROLE_DELETED", "ROLE", id, snapshot(before, had), null);
    }

    private static void requireNotAdministrator(Role role) {
        if (ADMIN_ROLE_CODE.equals(role.code())) {
            throw new BusinessRuleViolationException("administrator-role-fixed",
                    "The Administrator role always grants everything and cannot be changed or deleted");
        }
    }

    /** The codes, each checked against the vocabulary. */
    private Set<String> known(List<String> permissionCodes) {
        Set<String> out = new LinkedHashSet<>();
        if (permissionCodes != null) {
            for (String code : permissionCodes) {
                if (code != null && !code.isBlank()) {
                    out.add(code.strip());
                }
            }
        }
        for (String code : out) {
            Boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM permission WHERE code = :c)")
                    .param("c", code).query(Boolean.class).single();
            if (!Boolean.TRUE.equals(exists)) {
                throw new BusinessRuleViolationException("unknown-permission", "There is no permission " + code);
            }
        }
        return out;
    }

    private void grant(UUID tenantId, UUID roleId, Set<String> codes) {
        for (String code : codes) {
            jdbc.sql("""
                            INSERT INTO role_permission (tenant_id, role_id, permission_id)
                            SELECT :t, :r, p.id FROM permission p WHERE p.code = :c
                            """)
                    .param("t", tenantId).param("r", roleId).param("c", code).update();
        }
    }

    private Set<String> permissionsOf(UUID roleId) {
        return new LinkedHashSet<>(jdbc.sql("""
                        SELECT p.code FROM role_permission rp JOIN permission p ON p.id = rp.permission_id
                         WHERE rp.role_id = :r ORDER BY p.code
                        """)
                .param("r", roleId).query(String.class).list());
    }

    private static Json snapshot(Role r, Set<String> permissions) {
        return Json.object("code", r.code(), "name", r.name(), "permissions", String.join(",", permissions));
    }
}
