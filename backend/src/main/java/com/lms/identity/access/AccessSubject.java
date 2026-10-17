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
package com.lms.identity.access;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.lms.shared.tenant.TenantContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Who is calling, as the attributes a policy can read.
 *
 * <p>Built from the verified access token, never from the database: the token
 * is what the user was issued, it is already in memory, and reading the user
 * row on every decision would cost a query for something that cannot have
 * changed within the request. A changed attribute takes effect on the next
 * refresh, which re-reads the user.
 *
 * <p>The organisational path comes from the bound tenant scope rather than the
 * token directly. The tenant filter put it there from the same claim, and
 * in-process callers (tests, listeners) that bind a scope without a token get
 * a consistent answer.
 */
public record AccessSubject(String userId, Set<String> permissions, String orgPath,
                            SubjectAttributes attributes) {

    public AccessSubject {
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        attributes = attributes == null ? SubjectAttributes.NONE : attributes;
    }

    /** The subject of the current thread's request. Never null. */
    public static AccessSubject current() {
        String orgPath = TenantContext.current().map(TenantContext.Scope::orgPath).orElse(null);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return new AccessSubject(null, Set.of(), orgPath, SubjectAttributes.NONE);
        }

        Set<String> permissions = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (authentication.getPrincipal() instanceof Jwt jwt) {
            return new AccessSubject(jwt.getSubject(), permissions, orgPath, fromClaims(jwt));
        }
        return new AccessSubject(authentication.getName(), permissions, orgPath,
                SubjectAttributes.NONE);
    }

    /**
     * The partner a portal user acts for, or null for the tenant's own staff.
     *
     * <p>What a read side narrows a listing by. Named for the question rather
     * than the field so a caller cannot mistake "null" for "no partner may be
     * seen".
     */
    public UUID partnerScope() {
        return attributes.partnerId();
    }

    /** The subject as {@code subject.*} attributes for a policy condition. */
    public Map<String, Object> asAttributes() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("userId", userId);
        map.put("permissions", List.copyOf(permissions));
        map.put("orgPath", orgPath);
        map.put("region", attributes.region());
        map.put("freightModes", attributes.freightModes());
        map.put("partnerId", attributes.partnerId());
        map.put("approvalLimit", attributes.approvalLimit());
        return map;
    }

    private static SubjectAttributes fromClaims(Jwt jwt) {
        String region = jwt.getClaimAsString(SubjectAttributes.CLAIM_REGION);

        List<String> modes = null;
        Object rawModes = jwt.getClaims().get(SubjectAttributes.CLAIM_MODES);
        if (rawModes instanceof Collection<?> collection) {
            modes = collection.stream().map(String::valueOf).toList();
        }

        UUID partner = null;
        String rawPartner = jwt.getClaimAsString(SubjectAttributes.CLAIM_PARTNER);
        if (rawPartner != null && !rawPartner.isBlank()) {
            partner = UUID.fromString(rawPartner);
        }

        BigDecimal limit = null;
        Object rawLimit = jwt.getClaims().get(SubjectAttributes.CLAIM_APPROVAL_LIMIT);
        if (rawLimit != null && !String.valueOf(rawLimit).isBlank()) {
            limit = new BigDecimal(String.valueOf(rawLimit));
        }
        return new SubjectAttributes(region, modes, partner, limit);
    }
}
