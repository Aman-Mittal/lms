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
package com.lms.shared.error;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the security filter chain's refusals look like on the wire.
 *
 * <p>These run before the dispatcher, so {@link GlobalExceptionHandler} never
 * sees them; this class is the only thing that turns them into the problem
 * documents the contract declares, and the only place that decides how much a
 * rejected caller is told.
 */
class ProblemSecurityHandlersTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final ProblemSecurityHandlers handlers = new ProblemSecurityHandlers(json);

    private MockHttpServletResponse unauthorized(org.springframework.security.core.AuthenticationException e)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/partners");
        MockHttpServletResponse response = new MockHttpServletResponse();
        handlers.commence(request, response, e);
        return response;
    }

    @Test
    @DisplayName("no credentials is 401 authentication-required with a bare Bearer challenge")
    void noCredentials() throws Exception {
        MockHttpServletResponse response = unauthorized(new InsufficientAuthenticationException("no"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        JsonNode problem = json.readTree(response.getContentAsString());
        assertThat(problem.get("code").asString()).isEqualTo("authentication-required");
        assertThat(problem.get("status").asInt()).isEqualTo(401);
        assertThat(problem.get("instance").asString()).isEqualTo("/api/v1/partners");
        assertThat(problem.get("type").asString()).endsWith("/authentication-required");
    }

    @Test
    @DisplayName("a rejected token is one generic code whatever the reason, and the reason is not echoed")
    void rejectedToken() throws Exception {
        for (String reason : new String[] {
                "An error occurred while attempting to decode the Jwt: Signed JWT rejected: Invalid signature",
                "An error occurred while attempting to decode the Jwt: Unsupported algorithm of none",
                "An error occurred while attempting to decode the Jwt: Malformed token",
                "Jwt issued by someone else: iss=evil"}) {
            MockHttpServletResponse response = unauthorized(new InvalidBearerTokenException(reason));

            assertThat(response.getStatus()).isEqualTo(401);
            JsonNode problem = json.readTree(response.getContentAsString());
            assertThat(problem.get("code").asString()).isEqualTo("invalid-token");
            assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer error=\"invalid_token\"");
            assertThat(response.getContentAsString()).doesNotContain("signature").doesNotContain("algorithm")
                    .doesNotContain("evil").doesNotContain("Jwt").doesNotContain("Malformed");
        }
    }

    @Test
    @DisplayName("an expired token is told so, and only that, so that a client knows to refresh")
    void expiredToken() throws Exception {
        MockHttpServletResponse response = unauthorized(new InvalidBearerTokenException(
                "An error occurred while attempting to decode the Jwt: Jwt expired at 2020-01-01T00:00:00Z"));

        assertThat(response.getHeader("WWW-Authenticate")).contains("invalid_token").contains("expired");
        JsonNode problem = json.readTree(response.getContentAsString());
        assertThat(problem.get("code").asString()).isEqualTo("invalid-token");
        assertThat(problem.get("detail").asString()).containsIgnoringCase("expired");
        assertThat(response.getContentAsString()).doesNotContain("2020").doesNotContain("decode");
    }

    @Test
    @DisplayName("any other authentication failure is the same 401 as no credentials")
    void otherFailure() throws Exception {
        MockHttpServletResponse response = unauthorized(new BadCredentialsException("secret internals"));

        assertThat(json.readTree(response.getContentAsString()).get("code").asString())
                .isEqualTo("authentication-required");
        assertThat(response.getContentAsString()).doesNotContain("secret internals");
    }

    @Test
    @DisplayName("access denied by the filter chain is a 403 problem that names no permission")
    void accessDenied() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/partners");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handlers.handle(request, response, new AccessDeniedException("Missing PARTNER_READ"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        JsonNode problem = json.readTree(response.getContentAsString());
        assertThat(problem.get("code").asString()).isEqualTo("access-denied");
        assertThat(problem.get("detail").asString())
                .isEqualTo("You do not have permission to perform this operation");
        assertThat(response.getContentAsString()).doesNotContain("PARTNER_READ");
    }
}
