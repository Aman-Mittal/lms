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
package com.lms.settings.command;

import java.util.UUID;

import com.lms.settings.api.ScopeType;
import com.lms.shared.error.ResourceNotFoundException;
import org.springframework.aot.hint.annotation.Reflective;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The records a profile's scope names, looked up under row-level security.
 *
 * <p>A foreign key is checked as the table owner and the scope id is
 * polymorphic anyway, so neither the database nor the insert proves that a
 * customer, vendor or vehicle named in a request is the caller's tenant's. Reading
 * the row under the caller's scope does: another tenant's row reads as absent,
 * and the answer is the same 404 as for an id that never existed. Table names
 * come from this class, never from the request.
 *
 * <p>Also the bean {@code @settingScopes} that {@code @PreAuthorize} asks
 * which organisational unit a write concerns, so the decision point can
 * refuse a unit outside the caller's reach.
 */
@Component("settingScopes")
public class ScopeReferences {

    /** The platform operator's right: platform values, for every tenant. */
    public static final String PLATFORM_MANAGE = "PLATFORM_SETTINGS_MANAGE";
    static final String TENANT_MANAGE = "SETTINGS_MANAGE";

    private final JdbcClient jdbc;

    public ScopeReferences(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The right a write at this scope needs: the platform operator's for PLATFORM. */
    @Reflective
    public String authorityFor(ScopeType scopeType) {
        return scopeType == ScopeType.PLATFORM ? PLATFORM_MANAGE : TENANT_MANAGE;
    }

    /** As {@link #authorityFor}, for an existing profile; a missing one asks for the tenant right. */
    @Reflective
    @Transactional(readOnly = true)
    public String authorityForProfile(UUID profileId) {
        boolean platform = jdbc.sql("SELECT 1 FROM setting_profile WHERE id = :id AND scope_type = 'PLATFORM'")
                .param("id", profileId).query(Integer.class).optional().isPresent();
        return platform ? PLATFORM_MANAGE : TENANT_MANAGE;
    }

    /** Whether the caller is the platform operator. */
    public static boolean isPlatformOperator() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> PLATFORM_MANAGE.equals(a.getAuthority()));
    }

    /** The unit a new profile is for, or null when its scope is not a unit. */
    @Reflective
    public UUID unitOf(ScopeType scopeType, UUID scopeId) {
        return scopeType == ScopeType.ORG_UNIT ? scopeId : null;
    }

    /**
     * The unit an existing profile is for, or null when it is not a unit's (or
     * is not the tenant's). Read in a transaction of its own making, because
     * {@code @PreAuthorize} runs before the service's transaction opens and a
     * read with no transaction carries no tenant scope.
     */
    @Reflective
    @Transactional(readOnly = true)
    public UUID unitOfProfile(UUID profileId) {
        return jdbc.sql("SELECT scope_id FROM setting_profile WHERE id = :id AND scope_type = 'ORG_UNIT'")
                .param("id", profileId).query(UUID.class).optional().orElse(null);
    }

    /** @throws ResourceNotFoundException when the scope's record is not the tenant's */
    public void requireExists(ScopeType scopeType, UUID scopeId) {
        String sql = switch (scopeType) {
            case ORG_UNIT -> "SELECT 1 FROM org_unit WHERE id = :id";
            case CUSTOMER -> "SELECT 1 FROM business_partner WHERE id = :id AND partner_type = 'CUSTOMER'";
            case VENDOR -> "SELECT 1 FROM business_partner WHERE id = :id AND partner_type = 'VENDOR'";
            case VEHICLE -> "SELECT 1 FROM vehicle WHERE id = :id";
            case PLATFORM, ORG_DEFAULT, USER -> null;
        };
        if (sql == null) {
            return;
        }
        boolean exists = jdbc.sql(sql).param("id", scopeId).query(Integer.class).optional().isPresent();
        if (!exists) {
            throw new ResourceNotFoundException(label(scopeType) + " " + scopeId + " was not found");
        }
    }

    private static String label(ScopeType scopeType) {
        return switch (scopeType) {
            case ORG_UNIT -> "Organisational unit";
            case CUSTOMER -> "Customer";
            case VENDOR -> "Vendor";
            case VEHICLE -> "Vehicle";
            default -> scopeType.name();
        };
    }

    /**
     * The signed-in user, or null when the principal is not a user -- the
     * platform on an event, or a test principal.
     */
    public static UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            return null;
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
