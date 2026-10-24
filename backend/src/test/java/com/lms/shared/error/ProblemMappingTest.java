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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
    @DisplayName("a lost optimistic-lock race is 409 with a stable code and no entity id")
    void staleWriteIsConflict() throws Exception {
        mvc.perform(get("/boom/stale"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("concurrent-modification"))
                .andExpect(content().string(not(containsString("3f0c"))))
                .andExpect(content().string(not(containsString("business_partner"))));
    }

    @Test
    @DisplayName("a unique violation is 409 and leaks neither the constraint nor the SQL")
    void uniqueViolationIsConflict() throws Exception {
        mvc.perform(get("/boom/duplicate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("duplicate-resource"))
                .andExpect(content().string(not(containsString("business_partner"))))
                .andExpect(content().string(not(containsString("INSERT"))))
                .andExpect(content().string(not(containsString("constraint"))));
    }

    @Test
    @DisplayName("a unique violation is recognised by SQLSTATE when it arrives untranslated")
    void uniqueViolationBySqlState() throws Exception {
        mvc.perform(get("/boom/duplicate-by-state"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("duplicate-resource"));
    }

    @Test
    @DisplayName("any other integrity failure stays 500 and tells the client nothing")
    void otherIntegrityFailureIsOpaque() throws Exception {
        mvc.perform(get("/boom/foreign-key"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("internal-error"))
                .andExpect(content().string(not(containsString("fk_load_vehicle"))))
                .andExpect(content().string(not(containsString("DELETE"))));
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

    // ------------------------------------------------ what Spring MVC itself raises

    @Test
    @DisplayName("a body that is not JSON is 400 malformed-request and quotes nothing back")
    void malformedJson() throws Exception {
        mvc.perform(post("/in/body").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("malformed-request"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().string(not(containsString("not json"))))
                .andExpect(content().string(not(containsString("Jackson"))))
                .andExpect(content().string(not(containsString("com.lms"))));
    }

    @Test
    @DisplayName("an absent body is 400 malformed-request")
    void absentBody() throws Exception {
        mvc.perform(post("/in/body").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("malformed-request"));
    }

    @Test
    @DisplayName("a value of the wrong type names the field and nothing else")
    void wrongTypeNamesField() throws Exception {
        mvc.perform(post("/in/body").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"not-a-uuid\",\"name\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("malformed-request"))
                .andExpect(jsonPath("$.errors.id").exists())
                .andExpect(content().string(not(containsString("not-a-uuid"))));
    }

    @Test
    @DisplayName("a failed bean validation is still 400 validation-failed with every field")
    void beanValidation() throws Exception {
        mvc.perform(post("/in/body").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"00000000-0000-0000-0000-000000000001\",\"name\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation-failed"))
                .andExpect(jsonPath("$.errors.name").exists());
    }

    @Test
    @DisplayName("a missing required query parameter is 400 missing-parameter naming it")
    void missingParameter() throws Exception {
        mvc.perform(get("/in/query"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("missing-parameter"))
                .andExpect(jsonPath("$.errors.period").value("is required"));
    }

    @Test
    @DisplayName("a path id that is not a UUID is 400 invalid-parameter naming it")
    void pathTypeMismatch() throws Exception {
        mvc.perform(get("/in/path/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-parameter"))
                .andExpect(jsonPath("$.errors.id").exists())
                .andExpect(content().string(not(containsString("Exception"))))
                .andExpect(content().string(not(containsString("java.util"))));
    }

    @Test
    @DisplayName("a query value of the wrong type is 400 invalid-parameter")
    void queryTypeMismatch() throws Exception {
        mvc.perform(get("/in/limit").param("limit", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-parameter"))
                .andExpect(jsonPath("$.errors.limit").exists());
    }

    @Test
    @DisplayName("an out-of-range parameter is 400 validation-failed")
    void parameterConstraint() throws Exception {
        mvc.perform(get("/in/window").param("days", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation-failed"))
                .andExpect(jsonPath("$.errors.days").exists());
    }

    @Test
    @DisplayName("an unrouted path is 404 not-found")
    void noResource() throws Exception {
        mvc.perform(get("/boom/no-resource"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not-found"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    @DisplayName("a wrong method is 405, keeps its Allow header and has a stable code")
    void methodNotAllowed() throws Exception {
        mvc.perform(delete("/in/query"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Allow", containsString("GET")))
                .andExpect(jsonPath("$.code").value("method-not-allowed"));
    }

    @Test
    @DisplayName("an unsupported media type is 415 and an unacceptable one 406")
    void mediaTypes() throws Exception {
        mvc.perform(get("/boom/unsupported-media"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("unsupported-media-type"));
        mvc.perform(get("/boom/not-acceptable"))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.code").value("not-acceptable"));
    }

    @Test
    @DisplayName("any other framework-raised status is a problem with the platform type and a code")
    void otherErrorResponses() throws Exception {
        mvc.perform(get("/boom/gone"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("request-refused"))
                .andExpect(jsonPath("$.type").value(
                        "https://github.com/Aman-Mittal/lms/problems/request-refused"));
    }

    @Test
    @DisplayName("a framework 5xx is opaque: its own detail never reaches the caller")
    void frameworkServerErrorIsOpaque() throws Exception {
        mvc.perform(get("/boom/framework-5xx"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("service-unavailable"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                .andExpect(content().string(not(containsString("jdbc:"))));
    }

    @Test
    @DisplayName("the catch-all keeps genuinely unexpected failures at 500")
    void catchAllStillCatches() throws Exception {
        mvc.perform(get("/boom/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("internal-error"));
    }

    record Body(UUID id, @NotBlank String name) {
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

        @GetMapping("/boom/stale")
        void stale() {
            throw new org.springframework.dao.OptimisticLockingFailureException(
                    "Failed to update versioned entity with id '3f0c' (version 4) of business_partner");
        }

        @GetMapping("/boom/duplicate")
        void duplicate() {
            throw new org.springframework.dao.DuplicateKeyException(
                    "PreparedStatementCallback; SQL [INSERT INTO business_partner ...]; "
                            + "ERROR: duplicate key value violates unique constraint "
                            + "\"business_partner_code_unique\"");
        }

        @GetMapping("/boom/duplicate-by-state")
        void duplicateByState() {
            throw new org.springframework.dao.DataIntegrityViolationException("insert failed",
                    new java.sql.SQLException("unique_violation on business_partner_code_unique",
                            "23505"));
        }

        @GetMapping("/boom/foreign-key")
        void foreignKey() {
            throw new org.springframework.dao.DataIntegrityViolationException(
                    "DELETE FROM vehicle violates foreign key constraint fk_load_vehicle",
                    new java.sql.SQLException("fk_load_vehicle", "23503"));
        }

        @PostMapping("/in/body")
        void body(@Valid @RequestBody Body body) {
        }

        @GetMapping("/in/query")
        void query(@RequestParam java.time.LocalDate period) {
        }

        @GetMapping("/in/path/{id}")
        void path(@PathVariable UUID id) {
        }

        @GetMapping("/in/limit")
        void limit(@RequestParam(required = false) Integer limit) {
        }

        @GetMapping("/in/window")
        void window(@RequestParam @jakarta.validation.constraints.Min(0) int days) {
        }

        @GetMapping("/boom/no-resource")
        void noResource() throws Exception {
            throw new NoResourceFoundException(HttpMethod.GET, "/nothing", "/nothing");
        }

        @GetMapping("/boom/unsupported-media")
        void unsupportedMedia() throws Exception {
            throw new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN,
                    List.of(MediaType.APPLICATION_JSON));
        }

        @GetMapping("/boom/not-acceptable")
        void notAcceptable() throws Exception {
            throw new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON));
        }

        @GetMapping("/boom/gone")
        void gone() {
            throw new ErrorResponseException(HttpStatus.GONE);
        }

        @GetMapping("/boom/framework-5xx")
        void framework5xx() {
            throw new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE,
                    org.springframework.http.ProblemDetail.forStatusAndDetail(
                            HttpStatus.SERVICE_UNAVAILABLE, "pool at jdbc:postgresql://db"), null);
        }

        @GetMapping("/boom/unexpected")
        void unexpected() {
            throw new IllegalStateException("connection pool exhausted at jdbc:postgresql://db");
        }
    }
}
