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
 */package com.lms.shared.idempotency;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import com.lms.shared.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Makes a retried write safe to send twice.
 *
 * <p>The failure this prevents is not hypothetical. A dispatcher on a patchy
 * connection posts a dispatch, the response is lost, the client retries, and
 * two gate-out events are recorded for one lorry. Every network in a yard is
 * that network.
 *
 * <p>Opt-in by header rather than mandatory. A caller that sends
 * {@code Idempotency-Key} is promising the key identifies one logical
 * operation; a caller that does not gets today's behaviour. Requiring it would
 * break every simple client for the sake of endpoints where a duplicate is
 * harmless anyway.
 *
 * <p>Ordered after {@link com.lms.identity.security.TenantContextFilter},
 * because a key is scoped to a tenant and there is no tenant until the token
 * has been read. Both orders are explicit: leaving them to default would make
 * the ordering an accident of bean names.
 */
@Component
@Order(IdempotencyFilter.ORDER)
public class IdempotencyFilter extends OncePerRequestFilter {

    /** After the tenant filter, which runs at {@code LOWEST_PRECEDENCE - 100}. */
    public static final int ORDER = Ordered.LOWEST_PRECEDENCE - 90;

    static final String HEADER = "Idempotency-Key";

    /**
     * The largest body a key may be claimed for.
     *
     * <p>Replaying a response means storing it, and hashing a request means
     * buffering it. A megabyte covers every write this API has -- the largest
     * is a 500-point telematics batch -- and refuses to buffer something
     * pathological into a 512 MB instance.
     */
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

    private final IdempotencyService idempotency;

    /**
     * Spring MVC's own exception resolver, which is what turns an exception
     * into the RFC 7807 document {@code GlobalExceptionHandler} defines.
     *
     * <p>Needed because this is a servlet filter. {@code @RestControllerAdvice}
     * only sees exceptions raised inside the dispatcher, so a key reused for a
     * different request -- refused here, before the dispatcher is reached --
     * went to the container as a 500 instead of the 409 the domain intended.
     * Resolving through the same bean the dispatcher uses means one error
     * contract rather than a second one written out by hand here.
     */
    private final HandlerExceptionResolver exceptionResolver;

    public IdempotencyFilter(IdempotencyService idempotency,
                             @Qualifier("handlerExceptionResolver")
                             HandlerExceptionResolver exceptionResolver) {
        this.idempotency = idempotency;
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (request.getHeader(HEADER) == null) {
            return true;
        }
        // Safe methods have nothing to deduplicate, and authentication has no
        // tenant scope yet -- a key could not be stored against one.
        return switch (request.getMethod()) {
            case "POST", "PUT", "PATCH", "DELETE" -> request.getRequestURI().startsWith("/api/v1/auth/");
            default -> true;
        };
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String key = request.getHeader(HEADER);

        if (TenantContext.current().isEmpty()) {
            // Unauthenticated, or a token with no tenant claim. Let the request
            // through: the security chain is about to refuse it anyway, and
            // failing here would answer the wrong question.
            chain.doFilter(request, response);
            return;
        }

        byte[] body = StreamUtils.copyToByteArray(request.getInputStream());
        if (body.length > MAX_BODY_BYTES) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                    "A request with an " + HEADER + " must be under 1 MB");
            return;
        }

        HttpServletRequest replayable = new BufferedBodyRequest(request, body);

        Optional<IdempotencyService.RecordedOutcome> recorded;
        try {
            recorded = idempotency.claim(key, request.getMethod(), request.getRequestURI(),
                    new String(body, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            exceptionResolver.resolveException(request, response, null, e);
            return;
        }

        if (recorded.isPresent()) {
            replay(recorded.get(), response);
            return;
        }

        ContentCachingResponseWrapper captured = new ContentCachingResponseWrapper(response);
        boolean completed = false;
        try {
            chain.doFilter(replayable, captured);

            // Only a success is worth replaying. Recording a 409 would mean a
            // client that fixed the problem and retried with the same key got
            // the old refusal back, for ever.
            if (captured.getStatus() < 400) {
                idempotency.complete(key, captured.getStatus(),
                        new String(captured.getContentAsByteArray(), StandardCharsets.UTF_8));
                completed = true;
            }
        } finally {
            if (!completed) {
                // Releasing is not optional. A claim left behind by a failure
                // poisons the key: the client retries, finds it claimed, and
                // can never succeed.
                safeRelease(key);
            }
            captured.copyBodyToResponse();
        }
    }

    private static void replay(IdempotencyService.RecordedOutcome outcome,
                               HttpServletResponse response) throws IOException {
        response.setStatus(outcome.httpStatus() == null ? 200 : outcome.httpStatus());
        response.setContentType("application/json");
        // Tells the caller this is the earlier answer rather than a fresh one,
        // which is the difference between "your retry worked" and "you created
        // a second one".
        response.setHeader("Idempotent-Replay", "true");
        if (outcome.responseBody() != null) {
            response.getWriter().write(outcome.responseBody());
        }
    }

    private void safeRelease(String key) {
        try {
            idempotency.release(key);
        } catch (RuntimeException e) {
            // The original failure is the one worth reporting. Losing it behind
            // a cleanup error would hide the cause and leave the caller with a
            // message about idempotency for a problem that had nothing to do
            // with it.
            log.warn("Could not release idempotency claim after a failed request: {}",
                    e.getMessage());
        }
    }

    /**
     * A request whose body can be read again.
     *
     * <p>The body has to be hashed before the handler runs and read by the
     * handler afterwards, and a servlet input stream is single-pass.
     */
    private static final class BufferedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private BufferedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream buffer = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return buffer.read();
                }

                @Override
                public boolean isFinished() {
                    return buffer.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException(
                            "This body is already buffered; there is nothing to wait for");
                }
            };
        }

        @Override
        public java.io.BufferedReader getReader() {
            return new java.io.BufferedReader(
                    new java.io.InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
