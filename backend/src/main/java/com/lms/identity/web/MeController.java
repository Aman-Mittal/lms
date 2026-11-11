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
package com.lms.identity.web;

import java.util.List;
import java.util.UUID;

import com.lms.identity.query.MeView;
import com.lms.identity.query.UserQueryService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who the caller is: the one identity read every signed-in caller may make,
 * about themself only. Their preferences are {@code /api/v1/settings/me}.
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final UserQueryService users;

    public MeController(UserQueryService users) {
        this.users = users;
    }

    @GetMapping
    public MeView me() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        List<String> permissions = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).toList();
        String email = null;
        String name = null;
        String subject = authentication.getName();
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            // The token's own grant, not the authorities: Spring Security adds
            // factor authorities (FACTOR_BEARER) that are not permissions.
            List<String> perms = jwt.getClaimAsStringList("perms");
            permissions = perms == null ? List.of() : perms;
            email = jwt.getClaimAsString("email");
            name = jwt.getClaimAsString("name");
            subject = jwt.getSubject();
        }
        return users.me(userId(subject), email, name, permissions);
    }

    private static UUID userId(String subject) {
        try {
            return subject == null ? null : UUID.fromString(subject);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
