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
package com.lms.identity.security;

import java.io.IOException;
import java.util.UUID;

import com.lms.shared.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the authenticated request's tenant and organisational scope to the
 * current thread.
 *
 * <p>Runs after authentication so the JWT has already been verified. The scope
 * it publishes is what {@code TenantAwareTransactionManager} writes onto the
 * database connection for row-level security, so this filter is the link
 * between the token and the isolation guarantee.
 *
 * <p>The {@code finally} block is not optional. Request threads are pooled (or
 * virtual, but the context is still thread-bound), and a scope left behind
 * would be inherited by whatever ran next -- a cross-tenant leak with no
 * visible cause.
 */
@Component
public class TenantContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantContextFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        boolean bound = false;
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
                Jwt jwt = jwtAuthentication.getToken();
                String tenant = jwt.getClaimAsString("tenant");
                String orgPath = jwt.getClaimAsString("org");

                if (tenant != null && !tenant.isBlank()) {
                    try {
                        TenantContext.set(UUID.fromString(tenant), orgPath);
                        bound = true;
                    } catch (IllegalArgumentException e) {
                        // A malformed tenant claim on an otherwise valid token
                        // means something is wrong with issuance. Leave the
                        // scope unbound; row-level security then returns no
                        // rows rather than guessing.
                        log.warn("Rejecting malformed tenant claim on an authenticated request");
                    }
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            if (bound) {
                TenantContext.clear();
            }
        }
    }
}
