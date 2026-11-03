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

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 401 and 403 raised by the security filter chain, as problem documents.
 *
 * <p>{@link GlobalExceptionHandler} cannot see these: the filter chain runs
 * before the dispatcher, so a missing or rejected token never reaches a
 * controller advice. Left alone, Spring Security answers 401 with an empty
 * body and only a {@code WWW-Authenticate} header, while the contract declares
 * {@code application/problem+json} for every refusal.
 *
 * <p>The challenge header is kept, since clients and gateways act on it. What
 * the caller is told about <em>why</em> is deliberately thin: a token that is
 * missing is {@code authentication-required}; one that was presented and
 * rejected is {@code invalid-token}, whether it was forged, unsigned,
 * malformed or from another issuer, and the response never says which. The
 * single exception is expiry, which the caller needs in order to know to
 * refresh and which reveals nothing about the key or the claims.
 */
@Component
public class ProblemSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemSecurityHandlers.class);
    private static final String BASE_TYPE = "https://github.com/Aman-Mittal/lms/problems/";

    private final JsonMapper json;

    public ProblemSecurityHandlers(JsonMapper json) {
        this.json = json;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException e) throws IOException {
        String challenge = "Bearer";
        String code = "authentication-required";
        String detail = "This operation requires authentication";
        if (e instanceof OAuth2AuthenticationException) {
            code = "invalid-token";
            detail = "The access token was not accepted";
            challenge = "Bearer error=\"invalid_token\"";
            if (expired(e)) {
                detail = "The access token has expired";
                challenge = "Bearer error=\"invalid_token\", error_description=\"The access token expired\"";
            }
        }
        log.debug("Refused an unauthenticated request: {}", code);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge);
        write(request, response, HttpStatus.UNAUTHORIZED, code, detail);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException e) throws IOException {
        write(request, response, HttpStatus.FORBIDDEN, "access-denied",
                "You do not have permission to perform this operation");
    }

    private static boolean expired(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains("Jwt expired at")) {
                return true;
            }
        }
        return false;
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
                       String code, String detail) throws IOException {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", URI.create(BASE_TYPE + code).toString());
        problem.put("title", status.getReasonPhrase());
        problem.put("status", status.value());
        problem.put("detail", detail);
        problem.put("instance", request.getRequestURI());
        problem.put("code", code);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(json.writeValueAsString(problem));
    }
}
