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

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates exceptions into RFC 7807 problem documents, as vision document
 * 4.1 requires.
 *
 * <p>The distinction that matters here is 400 versus 409. A validation failure
 * means the request was malformed and the client should fix it. A
 * {@link BusinessRuleViolationException} means the request was perfectly
 * well-formed and the domain refused it -- an expired vehicle document blocking
 * dispatch, a load exceeding vehicle capacity, an overlapping terminal. Those
 * are not client mistakes, and reporting them as 400 tells the caller to go
 * looking in the wrong place.
 *
 * <p>Every problem carries a stable {@code code} so clients branch on an
 * identifier rather than parsing prose.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String BASE_TYPE = "https://github.com/Aman-Mittal/lms/problems/";

    @ExceptionHandler(AuthenticationFailedException.class)
    public ProblemDetail onAuthenticationFailed(AuthenticationFailedException e) {
        // Logged without the attempted identity: the message is deliberately
        // vague to prevent account enumeration, and logging the specifics would
        // reintroduce exactly what the vagueness is protecting.
        log.debug("Authentication attempt rejected");
        return problem(HttpStatus.UNAUTHORIZED, "authentication-failed", e.getMessage());
    }

    @ExceptionHandler(BusinessRuleViolationException.class)
    public ProblemDetail onBusinessRuleViolation(BusinessRuleViolationException e) {
        log.info("Business rule refused the operation: {} -- {}", e.code(), e.getMessage());
        return problem(HttpStatus.CONFLICT, e.code(), e.getMessage());
    }

    /**
     * A caller who is signed in but not permitted.
     *
     * <p>Without this the catch-all below claims it: Spring Security's
     * translation filter only sees an access denial that propagates out of the
     * filter chain, and one thrown by {@code @PreAuthorize} inside a handler
     * never gets that far. The result was a 500 with a stack trace logged at
     * error level for what is a routine, expected answer -- which both told the
     * client the wrong thing and buried real failures in noise.
     *
     * <p>The detail is deliberately generic. Naming the missing permission
     * would tell an attacker which one to go looking for, and the caller cannot
     * grant it to themselves anyway.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail onAccessDenied(AccessDeniedException e) {
        log.info("Refused an operation the caller lacks permission for");
        return problem(HttpStatus.FORBIDDEN, "access-denied",
                "You do not have permission to perform this operation");
    }

    /**
     * No credentials at all, on a path that needs them.
     *
     * <p>401 rather than 403, and the difference is actionable: 401 means
     * present a token, 403 means the token you presented is not enough. A
     * client told 403 when it simply never authenticated will refresh nothing
     * and retry forever.
     */
    @ExceptionHandler(AuthenticationCredentialsNotFoundException.class)
    public ProblemDetail onMissingCredentials(AuthenticationCredentialsNotFoundException e) {
        return problem(HttpStatus.UNAUTHORIZED, "authentication-required",
                "This operation requires authentication");
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail onNotFound(ResourceNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "not-found", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail onValidationFailure(MethodArgumentNotValidException e) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation-failed",
                "The request body failed validation");

        Map<String, String> errors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(error -> errors.put(error.getField(), error.getDefaultMessage()));
        problem.setProperty("errors", errors);

        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail onIllegalArgument(IllegalArgumentException e) {
        // Domain invariants -- an out-of-range coordinate, an unsafe org slug --
        // are enforced by throwing from constructors and factory methods.
        return problem(HttpStatus.BAD_REQUEST, "invalid-argument", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail onUnexpected(Exception e) {
        // The only handler that logs at error level with a stack trace, and the
        // only one that withholds its detail: an unexpected failure may carry
        // internals that must not reach the client.
        log.error("Unhandled exception", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error",
                "An unexpected error occurred");
    }

    private ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(BASE_TYPE + code));
        problem.setProperty("code", code);
        return problem;
    }
}
