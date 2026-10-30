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
package com.lms.tracking.web;

import java.net.URI;

import com.lms.tracking.query.PublicTrackingQueryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one route that needs no credential: a tracking link's token is the
 * credential.
 *
 * <p>Every answer, the refusals included, is marked {@code no-store} and
 * {@code noindex}: a tokenised URL must not end up in a shared cache or a search
 * index. An unknown, expired and revoked token get the identical 404.
 */
@RestController
@RequestMapping("/api/v1/public/tracking")
public class PublicTrackingController {

    private static final String PROBLEM_BASE = "https://github.com/Aman-Mittal/lms/problems/";

    private final PublicTrackingQueryService tracking;
    private final ClientRateLimiter limiter;

    public PublicTrackingController(PublicTrackingQueryService tracking, ClientRateLimiter limiter) {
        this.tracking = tracking;
        this.limiter = limiter;
    }

    @GetMapping("/{token}")
    public ResponseEntity<?> track(@PathVariable String token, HttpServletRequest request) {
        String client = request.getRemoteAddr();
        if (!limiter.tryAcquire(client)) {
            return problem(HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "Too many requests; try again shortly")
                    .header("Retry-After", Long.toString(limiter.retryAfterSeconds(client)))
                    .build();
        }
        return tracking.view(token)
                .<ResponseEntity<?>>map(view -> hardened(ResponseEntity.ok()).body(view))
                .orElseGet(() -> problem(HttpStatus.NOT_FOUND, "tracking-link-not-found",
                        "This tracking link is not valid").build());
    }

    private static ResponseEntity.BodyBuilder hardened(ResponseEntity.BodyBuilder builder) {
        return builder.header("Cache-Control", "no-store").header("X-Robots-Tag", "noindex");
    }

    private static ProblemBody problem(HttpStatus status, String code, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setType(URI.create(PROBLEM_BASE + code));
        body.setTitle(status.getReasonPhrase());
        // Spring would fill this in with the request path, token and all, which would
        // make a refusal differ by the token asked about and echo it back.
        body.setInstance(URI.create("/api/v1/public/tracking"));
        body.setProperty("code", code);
        return new ProblemBody(hardened(ResponseEntity.status(status)), body);
    }

    /** A hardened response builder paired with its problem document. */
    private record ProblemBody(ResponseEntity.BodyBuilder builder, ProblemDetail body) {

        ProblemBody header(String name, String value) {
            builder.header(name, value);
            return this;
        }

        ResponseEntity<ProblemDetail> build() {
            return builder.contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON).body(body);
        }
    }
}
