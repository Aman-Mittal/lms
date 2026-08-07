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
package com.lms.identity;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * HTTP security for the platform.
 *
 * <p>Note the DSL: Spring Security 7 removed the non-lambda configuration
 * methods, so every customiser here takes a lambda. The older chained form will
 * not compile.
 */
@Configuration
// Turns @PreAuthorize on the command services into an actual check. Without
// it the annotations are documentation: the permission vocabulary was seeded
// in V2 and carried in the `perms` claim from the first commit, and nothing
// ever read it -- so any authenticated user of a tenant could dispatch trips,
// blacklist partners and award loads.
@EnableMethodSecurity
public class SecurityConfig {

    private final List<String> allowedOrigins;

    public SecurityConfig(@Value("${lms.cors.allowed-origins}") String allowedOrigins) {
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // CodeQL reports this as "Disabled Spring CSRF protection", high
                // severity, and suggests replacing it with `.csrf(csrf -> {})`.
                // That suggestion is wrong here and would break the API.
                //
                // CSRF is an attack that works because a browser attaches
                // credentials to a cross-site request automatically. Nothing in
                // this API is attached automatically:
                //
                //   - the only credential is `Authorization: Bearer`, which a
                //     browser never sends on its own;
                //   - SessionCreationPolicy.STATELESS means there is no session
                //     cookie, and no session to hold a CSRF token in;
                //   - the refresh token travels in the JSON body of
                //     POST /auth/refresh, not in a cookie -- AuthController has
                //     no cookie anywhere;
                //   - httpBasic and formLogin are disabled below, so there is no
                //     other ambient credential;
                //   - CORS sets allowCredentials(false).
                //
                // Enabling the default protection would install a
                // HttpSessionCsrfTokenRepository with no session to use, so
                // every POST, PUT and DELETE would answer 403 and the console
                // would have no way to obtain a token. It would not add
                // security; it would remove the API.
                //
                // The assumption this rests on -- that no credential is ever
                // carried in a cookie -- is enforced by
                // .github/scripts/check-conventions.sh, so that introducing
                // cookie authentication later fails the build here rather than
                // silently making this comment untrue.
                .csrf(csrf -> csrf.disable()) // codeql[java/spring-disabled-csrf-protection]
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Obtaining a token cannot require a token. These two
                        // were missing, which meant /auth/login answered 401 to
                        // everybody and nobody could ever sign in -- invisible
                        // to a test suite that drives the services directly.
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/refresh").permitAll()
                        // Render polls readiness to decide when the instance is
                        // live, and does so unauthenticated.
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // The OpenAPI contract is public: the console on GitHub
                        // Pages generates its client from it.
                        .requestMatchers(HttpMethod.GET, "/openapi.yaml").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable());

        return http.build();
    }

    /**
     * Turns the {@code perms} claim into Spring Security authorities.
     *
     * <p>No {@code SCOPE_} or {@code ROLE_} prefix. The claim already holds
     * this platform's own permission codes -- ORDER_CREATE, TRIP_EXECUTE -- and
     * a prefix would mean every {@code @PreAuthorize} carried a piece of
     * framework trivia that has nothing to do with the rule being expressed.
     */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("perms");
        authorities.setAuthorityPrefix("");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // Explicit origins rather than a wildcard: the console is served from a
        // known GitHub Pages origin.
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Correlation-Id"));
        config.setExposedHeaders(List.of("X-Correlation-Id"));
        // False. Tokens travel in the Authorization header, not in a cookie,
        // so nothing here needs credentialed requests -- and allowing them
        // widens what a compromised or mis-typed allowed origin could do.
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        // Cost 10. On a 0.1 CPU instance a higher factor turns login into a
        // multi-second operation; this is the ceiling that stays usable there.
        return new BCryptPasswordEncoder(10);
    }
}
