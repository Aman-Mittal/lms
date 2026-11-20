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

import com.lms.identity.command.domain.AppUser;
import com.lms.shared.audit.AuditTrail;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creating, editing, switching off and switching on users, and setting their
 * passwords (issue 258, "demo mode").
 *
 * <p>The administrator types the login id and the password; there is no invite
 * and no email. The password goes through the same {@link PasswordEncoder} that
 * sign-in verifies against, after {@link PasswordPolicy}, and appears in
 * neither an audit snapshot nor a log line. Nobody is ever deleted: a user is
 * switched off (suspended, with a reason) and their sessions are revoked.
 *
 * <p>Two guards protect the ability to administer at all. An administrator
 * cannot switch themselves off ({@code self-deactivation}), and the last active
 * holder of the Administrator role cannot lose it, by being switched off or by
 * an edit that drops the role ({@code last-administrator}). The count is taken
 * under a lock on the Administrator role row so two simultaneous edits cannot
 * each see "someone else is still there".
 *
 * <p>Edits take the version the caller read; a stale one is a 409
 * {@code concurrent-modification}. Everyone this service touches must be in the
 * caller's own organisational subtree, the same bound the user listing applies.
 */
@Service
public class UserAdminService {

    static final String ADMIN_ROLE_CODE = "ADMIN";

    private final AppUserRepository users;
    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final AuditTrail audit;

    public UserAdminService(AppUserRepository users, JdbcClient jdbc, PasswordEncoder passwordEncoder,
                            AuditTrail audit) {
        this.users = users;
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
    }

    /**
     * @param orgUnitId the unit to file the user under; the caller's own when null
     * @param roleCodes the roles to grant, by code
     */
    @Transactional
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public UUID create(UUID orgUnitId, String email, String password, String fullName,
                       List<String> roleCodes) {
        UUID tenantId = TenantContext.requireTenantId();
        String loginId = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        if (loginId.isEmpty() || fullName == null || fullName.isBlank()) {
            throw new IllegalArgumentException("A login id and a name are required");
        }
        PasswordPolicy.require(password, loginId);
        if (users.findByTenantAndEmail(tenantId, loginId).isPresent()) {
            throw new BusinessRuleViolationException("login-id-taken",
                    "Login id " + loginId + " is already in use");
        }
        UUID unit = resolveUnit(tenantId, orgUnitId);
        List<UUID> roleIds = roleIds(tenantId, roleCodes);

        UUID id = UUID.randomUUID();
        AppUser saved = users.save(AppUser.create(id, tenantId, unit, loginId,
                passwordEncoder.encode(password), fullName.strip()));
        grant(tenantId, id, roleIds);
        audit.recordAtomically("USER_CREATED", "APP_USER", id, null,
                snapshot(saved, codes(roleCodes), null));
        return saved.id();
    }

    /** Edits the name and replaces the roles. The login id never changes. */
    @Transactional
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public void update(UUID id, long expectedVersion, String fullName, List<String> roleCodes) {
        if (fullName == null || fullName.isBlank()) {
            throw new IllegalArgumentException("A name is required");
        }
        UUID tenantId = TenantContext.requireTenantId();
        AppUser before = managed(id);
        requireVersion(before.version(), expectedVersion, before.email());
        Set<String> wanted = codes(roleCodes);
        List<UUID> roleIds = roleIds(tenantId, roleCodes);
        Set<String> had = roleCodesOf(id);
        if (had.contains(ADMIN_ROLE_CODE) && !wanted.contains(ADMIN_ROLE_CODE)) {
            requireAnotherAdministrator(tenantId, id, "remove the Administrator role from");
        }
        jdbc.sql("DELETE FROM user_role WHERE tenant_id = :t AND user_id = :u")
                .param("t", tenantId).param("u", id).update();
        grant(tenantId, id, roleIds);
        AppUser saved = users.save(before.renamed(fullName.strip()));
        audit.recordAtomically("USER_UPDATED", "APP_USER", id, snapshot(before, had, null),
                snapshot(saved, wanted, null));
    }

    @Transactional
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public void deactivate(UUID id, Long expectedVersion, String reason) {
        requireReason(reason);
        UUID tenantId = TenantContext.requireTenantId();
        AppUser before = managed(id);
        requireVersionIfGiven(before.version(), expectedVersion, before.email());
        if (id.equals(currentActorId())) {
            throw new BusinessRuleViolationException("self-deactivation",
                    "You cannot deactivate your own account");
        }
        if (before.status() == AppUser.UserStatus.SUSPENDED) {
            throw new BusinessRuleViolationException("user-already-inactive",
                    "User " + before.email() + " is already inactive");
        }
        Set<String> roles = roleCodesOf(id);
        if (roles.contains(ADMIN_ROLE_CODE)) {
            requireAnotherAdministrator(tenantId, id, "deactivate");
        }
        AppUser saved = users.save(before.withStatus(AppUser.UserStatus.SUSPENDED));
        revokeSessions(tenantId, id);
        audit.recordAtomically("USER_DEACTIVATED", "APP_USER", id, snapshot(before, roles, null),
                snapshot(saved, roles, reason));
    }

    @Transactional
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public void reactivate(UUID id, Long expectedVersion, String reason) {
        requireReason(reason);
        AppUser before = managed(id);
        requireVersionIfGiven(before.version(), expectedVersion, before.email());
        if (before.status() == AppUser.UserStatus.ACTIVE) {
            throw new BusinessRuleViolationException("user-not-inactive",
                    "User " + before.email() + " is active, not inactive");
        }
        Set<String> roles = roleCodesOf(id);
        AppUser saved = users.save(before.withStatus(AppUser.UserStatus.ACTIVE));
        audit.recordAtomically("USER_REACTIVATED", "APP_USER", id, snapshot(before, roles, null),
                snapshot(saved, roles, reason));
    }

    /** Sets a new password and signs the user out everywhere. */
    @Transactional
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public void setPassword(UUID id, Long expectedVersion, String password) {
        UUID tenantId = TenantContext.requireTenantId();
        AppUser before = managed(id);
        requireVersionIfGiven(before.version(), expectedVersion, before.email());
        PasswordPolicy.require(password, before.email());
        AppUser saved = users.save(before.withPasswordHash(passwordEncoder.encode(password)));
        revokeSessions(tenantId, id);
        Set<String> roles = roleCodesOf(id);
        // The snapshots carry no hash: that a password was set, by whom and when, is the record.
        audit.recordAtomically("USER_PASSWORD_SET", "APP_USER", id, snapshot(before, roles, null),
                snapshot(saved, roles, null));
    }

    // ----------------------------------------------------------------- guards

    /**
     * Refuses unless some other active user holds the Administrator role.
     * Locks the role row first, so concurrent edits queue instead of each
     * counting the other as the survivor.
     */
    private void requireAnotherAdministrator(UUID tenantId, UUID except, String what) {
        jdbc.sql("SELECT id FROM role WHERE tenant_id = :t AND code = :c FOR UPDATE")
                .param("t", tenantId).param("c", ADMIN_ROLE_CODE).query(UUID.class).list();
        Long others = jdbc.sql("""
                        SELECT count(*)
                          FROM app_user u
                          JOIN user_role ur ON ur.user_id = u.id
                          JOIN role r ON r.id = ur.role_id
                         WHERE u.tenant_id = :t AND r.code = :c AND u.status = 'ACTIVE' AND u.id <> :id
                        """)
                .param("t", tenantId).param("c", ADMIN_ROLE_CODE).param("id", except)
                .query(Long.class).single();
        if (others == null || others == 0) {
            throw new BusinessRuleViolationException("last-administrator",
                    "You cannot " + what + " the last active Administrator");
        }
    }

    // ---------------------------------------------------------------- helpers

    /** The user, if they sit in the caller's organisational subtree. */
    private AppUser managed(UUID id) {
        AppUser user = users.findById(id).orElseThrow(() -> new ResourceNotFoundException("AppUser", id));
        String path = jdbc.sql("SELECT path FROM org_unit WHERE id = :id")
                .param("id", user.orgUnitId()).query(String.class).optional().orElse(null);
        if (path == null || !path.startsWith(TenantContext.requireOrgPath())) {
            throw new ResourceNotFoundException("AppUser", id);
        }
        return user;
    }

    private UUID resolveUnit(UUID tenantId, UUID requested) {
        String callerPath = TenantContext.requireOrgPath();
        if (requested == null) {
            return jdbc.sql("SELECT id FROM org_unit WHERE tenant_id = :t AND path = :p")
                    .param("t", tenantId).param("p", callerPath).query(UUID.class).optional()
                    .orElseThrow(() -> new ResourceNotFoundException("Organisational unit", tenantId));
        }
        String path = jdbc.sql("SELECT path FROM org_unit WHERE id = :id")
                .param("id", requested).query(String.class).optional().orElse(null);
        if (path == null || !path.startsWith(callerPath)) {
            throw new ResourceNotFoundException("Organisational unit", requested);
        }
        return requested;
    }

    private static Set<String> codes(List<String> roleCodes) {
        Set<String> out = new LinkedHashSet<>();
        if (roleCodes != null) {
            for (String code : roleCodes) {
                if (code != null && !code.isBlank()) {
                    out.add(code.strip());
                }
            }
        }
        return out;
    }

    private List<UUID> roleIds(UUID tenantId, List<String> roleCodes) {
        List<UUID> ids = new java.util.ArrayList<>();
        for (String code : codes(roleCodes)) {
            ids.add(jdbc.sql("SELECT id FROM role WHERE tenant_id = :t AND code = :c")
                    .param("t", tenantId).param("c", code).query(UUID.class).optional()
                    .orElseThrow(() -> new BusinessRuleViolationException("unknown-role",
                            "There is no role " + code)));
        }
        return ids;
    }

    private void grant(UUID tenantId, UUID userId, List<UUID> roleIds) {
        for (UUID roleId : roleIds) {
            jdbc.sql("INSERT INTO user_role (tenant_id, user_id, role_id) VALUES (:t, :u, :r)")
                    .param("t", tenantId).param("u", userId).param("r", roleId).update();
        }
    }

    private Set<String> roleCodesOf(UUID userId) {
        return new LinkedHashSet<>(jdbc.sql("""
                        SELECT r.code FROM user_role ur JOIN role r ON r.id = ur.role_id
                         WHERE ur.user_id = :u ORDER BY r.code
                        """)
                .param("u", userId).query(String.class).list());
    }

    private void revokeSessions(UUID tenantId, UUID userId) {
        jdbc.sql("""
                        UPDATE refresh_token SET revoked_at = :now
                         WHERE tenant_id = :t AND user_id = :u AND revoked_at IS NULL
                        """)
                .param("now", java.time.OffsetDateTime.now()).param("t", tenantId).param("u", userId)
                .update();
    }

    private static UUID currentActorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Json snapshot(AppUser u, Set<String> roles, String reason) {
        return Json.object("email", u.email(), "fullName", u.fullName(), "status", u.status().name(),
                "roles", String.join(",", roles), "reason", reason);
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required");
        }
    }

    private static void requireVersion(Long actual, long expected, String what) {
        if (actual == null || actual != expected) {
            throw new OptimisticLockingFailureException("User " + what + " changed since it was read");
        }
    }

    private static void requireVersionIfGiven(Long actual, Long expected, String what) {
        if (expected != null) {
            requireVersion(actual, expected, what);
        }
    }
}
