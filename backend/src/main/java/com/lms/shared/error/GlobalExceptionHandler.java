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
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.MismatchedInputException;

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
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so that every error Spring
 * MVC itself raises -- an unreadable body, a path id that is not a UUID, a
 * missing query parameter, an unrouted path, a wrong method, an unsupported
 * media type -- is answered with its own 4xx problem document. Without it the
 * catch-all below claimed all of them and answered 500 "internal-error": a
 * client mistake reported as a server fault, a stack trace logged at error level
 * for each, and no way for the caller to tell what to fix. The catch-all is now
 * for failures nobody anticipated.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

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
     * A write that lost a race for the same aggregate.
     *
     * <p>Every aggregate carries {@code @Version}, so a save of a stale copy
     * matches no row and Spring Data raises this. It is the expected outcome of
     * two callers editing one thing at once, not a server fault: 409, and the
     * remedy is to re-read and decide again. The detail is fixed text because
     * the exception's own message names the entity id and version.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail onConcurrentModification(OptimisticLockingFailureException e) {
        log.info("A write lost an optimistic-lock race: {}", e.getClass().getSimpleName());
        return problem(HttpStatus.CONFLICT, "concurrent-modification",
                "The resource was changed by someone else at the same time; re-read it and try again");
    }

    /**
     * A constraint the database enforced.
     *
     * <p>A unique violation is the other half of a lost race: the domain checks
     * "is this number taken" and then inserts, which narrows the window without
     * closing it, and the unique index is what actually decides. The loser gets
     * the same 409 it would have got had it arrived a moment later and been
     * refused by the check. Any other integrity failure -- a foreign key, a
     * check, a not-null -- is a defect in the request path, not something the
     * caller can act on, and is handled as unexpected. In neither case does the
     * response carry the constraint name or SQL, which describe the schema.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail onDataIntegrity(DataIntegrityViolationException e) {
        if (isUniqueViolation(e)) {
            log.info("A write lost a unique-key race: {}", e.getClass().getSimpleName());
            return problem(HttpStatus.CONFLICT, "duplicate-resource",
                    "A resource with the same unique identifier already exists");
        }
        return onUnexpected(e);
    }

    /** PostgreSQL's {@code unique_violation}, wherever in the cause chain it surfaced. */
    private static boolean isUniqueViolation(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof DuplicateKeyException) {
                return true;
            }
            if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
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

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation-failed",
                "The request body failed validation");

        Map<String, String> errors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(error -> errors.put(error.getField(), error.getDefaultMessage()));
        problem.setProperty("errors", errors);

        return respond(problem, headers);
    }

    /**
     * A parameter-level constraint ({@code @Min}, {@code @Max}) that failed.
     * Same code as a body validation failure: the request was well formed and a
     * value was out of range.
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException e, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation-failed",
                "The request failed validation");
        Map<String, String> errors = new LinkedHashMap<>();
        e.getParameterValidationResults().forEach(result -> {
            String name = result.getMethodParameter().getParameterName();
            result.getResolvableErrors().forEach(error -> errors.put(
                    name != null ? name : "parameter", error.getDefaultMessage()));
        });
        problem.setProperty("errors", errors);
        return respond(problem, headers);
    }

    /**
     * A body that is absent, is not JSON, or does not fit the shape asked for:
     * a wrong type, an unknown enum constant, a malformed id. The detail is
     * fixed and the offending field -- only its name -- goes in {@code errors}.
     * Jackson's own message is withheld: it names classes, and quotes the
     * input back.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        log.debug("Unreadable request body: {}", e.getClass().getSimpleName());
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "malformed-request",
                "The request body is missing, is not valid JSON, or does not match the expected shape");
        for (Throwable cause = e; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof MismatchedInputException mismatch) {
                String field = fieldPath(mismatch);
                if (!field.isEmpty()) {
                    problem.setProperty("errors", Map.of(field, "has the wrong type or an unrecognised value"));
                }
                break;
            }
        }
        return respond(problem, headers);
    }

    /** Dotted path of the field Jackson stopped at: names and array indexes only. */
    private static String fieldPath(JacksonException e) {
        StringBuilder path = new StringBuilder();
        e.getPath().forEach(ref -> {
            if (ref.getPropertyName() != null) {
                path.append(path.length() > 0 ? "." : "").append(ref.getPropertyName());
            } else if (ref.getIndex() >= 0) {
                path.append('[').append(ref.getIndex()).append(']');
            }
        });
        return path.toString();
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException e, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "missing-parameter",
                "A required query parameter is missing");
        problem.setProperty("errors", Map.of(e.getParameterName(), "is required"));
        return respond(problem, headers);
    }

    /** A path variable or query parameter that is not the type the operation declares. */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException e, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "invalid-parameter",
                "A path or query parameter has the wrong type");
        String name = e instanceof org.springframework.web.method.annotation.MethodArgumentTypeMismatchException m
                ? m.getName() : e.getPropertyName();
        problem.setProperty("errors", Map.of(Objects.requireNonNullElse(name, "parameter"),
                "is not a valid " + expectedType(e)));
        return respond(problem, headers);
    }

    private static String expectedType(TypeMismatchException e) {
        Class<?> type = e.getRequiredType();
        if (type == null) {
            return "value";
        }
        if (type.isEnum()) {
            return "value; expected one of " + java.util.Arrays.toString(type.getEnumConstants());
        }
        if (type == java.util.UUID.class) {
            return "UUID";
        }
        return type.getSimpleName().toLowerCase(java.util.Locale.ROOT);
    }

    /** An unrouted path, an unexposed actuator endpoint, a trailing slash nobody serves. */
    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return respond(problem(HttpStatus.NOT_FOUND, "not-found", "No such resource"), headers);
    }

    /**
     * Everything else Spring MVC raises -- 405 with its {@code Allow} header, 406,
     * 415, a missing path variable, a failed conversion on the way out. Spring
     * has already chosen the status and built the document; this gives it the
     * platform's type URI and a stable code, and logs the 5xx ones, which the
     * framework's handler does not.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (status.is5xxServerError()) {
            log.error("Unhandled framework failure", e);
        }
        if (body == null && e instanceof ErrorResponse errorResponse) {
            // Spring builds the document inside the super call; build it here
            // instead so that it can be given the platform's type and code.
            body = errorResponse.updateAndGetBody(getMessageSource(), LocaleContextHolder.getLocale());
        }
        if (body instanceof ProblemDetail problem
                && (problem.getProperties() == null || !problem.getProperties().containsKey("code"))) {
            String code = codeFor(status);
            problem.setType(URI.create(BASE_TYPE + code));
            problem.setProperty("code", code);
            if (status.is5xxServerError()) {
                problem.setDetail("An unexpected error occurred");
            }
        }
        return super.handleExceptionInternal(e, body, headers, status, request);
    }

    private static String codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "bad-request";
            case 404 -> "not-found";
            case 405 -> "method-not-allowed";
            case 406 -> "not-acceptable";
            case 409 -> "conflict";
            case 413 -> "payload-too-large";
            case 415 -> "unsupported-media-type";
            case 503 -> "service-unavailable";
            default -> status.is5xxServerError() ? "internal-error" : "request-refused";
        };
    }

    private ResponseEntity<Object> respond(ProblemDetail problem, HttpHeaders headers) {
        return ResponseEntity.status(problem.getStatus()).headers(headers).body(problem);
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
