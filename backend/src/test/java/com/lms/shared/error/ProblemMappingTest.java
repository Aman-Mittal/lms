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
 */package com.lms.shared.error;

import com.lms.shared.error.ProblemMappingTest.Thrower;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What each kind of failure looks like on the wire.
 *
 * <p>Every rule in the platform is enforced by throwing, so the mapping from
 * exception to status code is the entire error contract of vision document
 * 4.1 -- and it is invisible from the service tests, which see the exception
 * and never the response.
 *
 * <p>Built standalone rather than with a full application context. The
 * question is what {@link GlobalExceptionHandler} does with an exception, and
 * a Spring Boot context would answer it through a filter chain that has its
 * own opinions about several of these.
 */
class ProblemMappingTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Thrower())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    @DisplayName("a permitted caller without the permission gets 403, not 500")
    void accessDeniedIsForbidden() throws Exception {
        // The regression this class was written for. With no handler for it,
        // the catch-all claimed the exception, answered 500, and logged a stack
        // trace at error level for a routine denial.
        mvc.perform(get("/boom/denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("access-denied"))
                // The missing permission is not named: it would tell an
                // attacker what to go looking for, and the caller cannot grant
                // it to themselves in any case.
                .andExpect(jsonPath("$.detail").value(
                        "You do not have permission to perform this operation"));
    }

    @Test
    @DisplayName("no credentials at all gets 401, which is a different instruction")
    void missingCredentialsIsUnauthorized() throws Exception {
        // 401 says present a token; 403 says the one you presented is not
        // enough. A client told 403 when it never authenticated will refresh
        // nothing and retry forever.
        mvc.perform(get("/boom/anonymous"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("authentication-required"));
    }

    @Test
    @DisplayName("a refused domain rule is 409, not 400")
    void businessRuleIsConflict() throws Exception {
        // The distinction the handler exists for. A 400 tells the caller their
        // request was malformed and to go and fix it; an expired insurance
        // certificate blocking dispatch is a perfectly well-formed request that
        // the domain refused, and sends them looking in the wrong place.
        mvc.perform(get("/boom/rule"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("dispatch-blocked"))
                .andExpect(jsonPath("$.detail").value("Trip TRIP-1 cannot be dispatched"));
    }

    @Test
    @DisplayName("a missing resource is 404 and says what was missing")
    void notFound() throws Exception {
        mvc.perform(get("/boom/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not-found"));
    }

    @Test
    @DisplayName("a broken invariant is 400")
    void illegalArgumentIsBadRequest() throws Exception {
        mvc.perform(get("/boom/invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-argument"));
    }

    @Test
    @DisplayName("an unexpected failure is 500 and tells the client nothing")
    void unexpectedIsOpaque() throws Exception {
        // The only handler that withholds its detail. An unexpected failure may
        // carry internals -- a SQL fragment, a file path -- that must not reach
        // a caller.
        mvc.perform(get("/boom/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("internal-error"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"));
    }

    @Test
    @DisplayName("every problem carries a stable type URI clients can branch on")
    void problemsAreTyped() throws Exception {
        mvc.perform(get("/boom/rule"))
                .andExpect(jsonPath("$.type").value(
                        "https://github.com/Aman-Mittal/lms/problems/dispatch-blocked"));
    }

    /** One route per failure mode; nothing here has any behaviour of its own. */
    @RestController
    static class Thrower {

        @GetMapping("/boom/denied")
        void denied() {
            throw new AccessDeniedException("Access Denied");
        }

        @GetMapping("/boom/anonymous")
        void anonymous() {
            throw new AuthenticationCredentialsNotFoundException("no authentication");
        }

        @GetMapping("/boom/rule")
        void rule() {
            throw new BusinessRuleViolationException("dispatch-blocked",
                    "Trip TRIP-1 cannot be dispatched");
        }

        @GetMapping("/boom/missing")
        void missing() {
            throw new ResourceNotFoundException("Trip",
                    java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"));
        }

        @GetMapping("/boom/invalid")
        void invalid() {
            throw new IllegalArgumentException("Latitude 91 is out of range");
        }

        @GetMapping("/boom/unexpected")
        void unexpected() {
            throw new IllegalStateException("connection pool exhausted at jdbc:postgresql://db");
        }
    }
}
