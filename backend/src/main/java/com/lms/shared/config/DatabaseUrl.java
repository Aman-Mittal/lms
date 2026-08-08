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
package com.lms.shared.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;
import java.util.Optional;

/**
 * Normalises a platform-supplied database URL into the JDBC form Spring expects.
 *
 * <p>Render (like Heroku and Fly) injects {@code DATABASE_URL} in libpq form:
 * {@code postgresql://user:password@host:port/database}. Spring's
 * {@code spring.datasource.url} needs {@code jdbc:postgresql://host:port/database}
 * with the credentials supplied separately. Handing the libpq form straight to
 * Hikari fails at startup with a driver-not-found error that reads as though the
 * PostgreSQL driver were missing, which sends you looking in entirely the wrong
 * place.
 *
 * <p>This is a pure function over a string so it can be unit tested without a
 * Spring context. {@link #applyIfNeeded()} is the only part that touches global
 * state, and it is called explicitly from {@code main} rather than registered as
 * an {@code EnvironmentPostProcessor} -- one less piece of registration
 * machinery to keep working under GraalVM AOT.
 */
public record DatabaseUrl(String jdbcUrl, Optional<String> username, Optional<String> password) {

    private static final String ENV_VAR = "DATABASE_URL";

    /**
     * Reads {@code DATABASE_URL} from the environment and, if it is in libpq
     * form, publishes the normalised values as system properties. System
     * properties outrank {@code application.yaml}, so this wins over the
     * placeholder default without the YAML needing to know anything about it.
     *
     * <p>Does nothing when the variable is absent (local development) or already
     * in JDBC form.
     */
    public static void applyIfNeeded() {
        String raw = System.getenv(ENV_VAR);
        if (raw == null || raw.isBlank() || raw.startsWith("jdbc:")) {
            return;
        }
        DatabaseUrl parsed = parse(raw);
        System.setProperty("spring.datasource.url", parsed.jdbcUrl());
        parsed.username().ifPresent(u -> System.setProperty("spring.datasource.username", u));
        parsed.password().ifPresent(p -> System.setProperty("spring.datasource.password", p));
    }

    /**
     * Converts a libpq-style PostgreSQL URL to its JDBC equivalent.
     *
     * @throws IllegalArgumentException if the URL is not a well-formed
     *         {@code postgres://} or {@code postgresql://} URL. Failing loudly
     *         here is deliberate: a malformed database URL should stop startup,
     *         not silently fall back to a local default that will not exist in
     *         the deployed environment.
     */
    public static DatabaseUrl parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Database URL must not be blank");
        }
        if (raw.startsWith("jdbc:")) {
            return new DatabaseUrl(raw, Optional.empty(), Optional.empty());
        }

        URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Malformed database URL: " + redact(raw), e);
        }

        String scheme = uri.getScheme();
        if (!"postgres".equals(scheme) && !"postgresql".equals(scheme)) {
            throw new IllegalArgumentException(
                    "Unsupported database URL scheme '" + scheme + "'; expected postgres or postgresql");
        }
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("Database URL has no host: " + redact(raw));
        }

        StringBuilder jdbc = new StringBuilder("jdbc:postgresql://").append(uri.getHost());
        if (uri.getPort() != -1) {
            jdbc.append(':').append(uri.getPort());
        }
        String path = uri.getPath();
        jdbc.append(path == null || path.isEmpty() ? "/" : path);
        if (uri.getQuery() != null && !uri.getQuery().isBlank()) {
            jdbc.append('?').append(uri.getQuery());
        }

        Optional<String> username = Optional.empty();
        Optional<String> password = Optional.empty();
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null && !userInfo.isBlank()) {
            int separator = userInfo.indexOf(':');
            if (separator < 0) {
                username = Optional.of(decode(userInfo));
            } else {
                username = Optional.of(decode(userInfo.substring(0, separator)));
                password = Optional.of(decode(userInfo.substring(separator + 1)));
            }
        }

        return new DatabaseUrl(jdbc.toString(), username, password);
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    /** Strips credentials so a malformed URL can be reported without leaking a password. */
    private static String redact(String raw) {
        int at = raw.indexOf('@');
        int schemeEnd = raw.indexOf("//");
        if (at > 0 && schemeEnd > 0 && at > schemeEnd) {
            return raw.substring(0, schemeEnd + 2) + "***@" + raw.substring(at + 1);
        }
        return raw;
    }
}
