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
package com.lms.shared.observability;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes a correlation id for every request and puts it on the logging
 * context (vision document 4.4).
 *
 * <p>Runs first — ahead of security and the tenant filter — so that an
 * authentication failure is still traceable. A request that is rejected is
 * exactly the one someone will want to find in the logs.
 *
 * <p>An inbound {@code X-Correlation-Id} is honoured so a caller can stitch its
 * own trace to ours, but it is validated first: the value ends up in log output
 * and in the audit trail, and an unvalidated header there invites log injection
 * and forged audit entries. Anything that is not a plain, bounded token is
 * replaced with a fresh id rather than rejected — observability should not be
 * able to fail a request.
 *
 * <p>The id is echoed back on the response so the caller can record it against
 * the operation it just performed.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    /** Conservative: alphanumerics, dash and underscore, bounded length. */
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    /**
     * The correlation id of the request being handled, for code that is not on
     * the logging path — the audit log records it against every mutation.
     */
    public static String current() {
        return CURRENT.get();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = resolve(request.getHeader(HEADER));

        MDC.put(MDC_KEY, correlationId);
        CURRENT.set(correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // Both are thread-bound and threads are reused. A value left behind
            // would attach one request's identity to the next.
            MDC.remove(MDC_KEY);
            CURRENT.remove();
        }
    }

    private static String resolve(String inbound) {
        if (inbound != null && SAFE_ID.matcher(inbound).matches()) {
            return inbound;
        }
        return UUID.randomUUID().toString();
    }
}
