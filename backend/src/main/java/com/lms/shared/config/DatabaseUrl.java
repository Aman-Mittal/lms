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

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

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

    /** libpq spells these differently from the JDBC driver. */
    private static final Map<String, String> LIBPQ_TO_JDBC = Map.of(
            "channel_binding", "channelBinding",
            "connect_timeout", "connectTimeout",
            "application_name", "ApplicationName");

    /**
     * Parameters that belong to another client (Prisma's pooler flags) and
     * mean nothing to the JDBC driver.
     */
    private static final Set<String> NOT_FOR_JDBC = Set.of("pgbouncer", "connection_limit", "pool_timeout");

    private static final Pattern HOST = Pattern.compile("\\[[0-9A-Fa-f:.]+](:\\d+)?|[^:/@\\s\\[\\]]+(:\\d+)?");

    /** Ports the common poolers listen on: PgBouncer, and Supabase's transaction pooler. */
    private static final Set<String> POOLER_PORTS = Set.of("6432", "6543");

    /**
     * Converts a PostgreSQL connection string to its JDBC equivalent.
     *
     * <p>Accepted: a JDBC URL (returned as it is); a {@code postgres://} or
     * {@code postgresql://} URL, with any of user, password, several hosts,
     * an IPv6 host, a port and query parameters; and libpq's keyword/value form
     * ({@code host=... dbname=... user=...}). Parameters libpq spells
     * differently are renamed ({@code channel_binding} is the driver's
     * {@code channelBinding}) and ones meant for other clients are dropped.
     *
     * <p>A <em>pooled</em> endpoint -- a host with "pooler" in its name (Neon,
     * Supabase), port 6432 or 6543, or {@code pgbouncer=true} -- gets
     * {@code prepareThreshold=0}. Behind a transaction pooler a server-side
     * prepared statement is created on one backend and then run on another,
     * which fails at random under load. Nothing else in the application holds
     * session state: the tenant and the role are set per transaction.
     *
     * @throws IllegalArgumentException if the string is not understood.
     *         Failing loudly here is deliberate: a malformed database URL
     *         should stop startup, not silently fall back to a local default
     *         that will not exist in the deployed environment.
     */
    public static DatabaseUrl parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Database URL must not be blank");
        }
        String text = raw.trim();
        if (text.startsWith("jdbc:")) {
            return new DatabaseUrl(text, Optional.empty(), Optional.empty());
        }

        int schemeEnd = text.indexOf("://");
        if (schemeEnd < 0) {
            if (text.contains("=")) {
                return fromKeywordValue(text);
            }
            throw new IllegalArgumentException("Malformed database URL: " + redact(text));
        }
        String scheme = text.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
        if (!"postgres".equals(scheme) && !"postgresql".equals(scheme)) {
            throw new IllegalArgumentException(
                    "Unsupported database URL scheme '" + scheme + "'; expected postgres or postgresql");
        }
        return fromUrl(text, text.substring(schemeEnd + 3));
    }

    private static DatabaseUrl fromUrl(String whole, String rest) {
        int authorityEnd = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            if (rest.charAt(i) == '/' || rest.charAt(i) == '?') {
                authorityEnd = i;
                break;
            }
        }
        String authority = rest.substring(0, authorityEnd);
        String tail = rest.substring(authorityEnd);

        int at = authority.lastIndexOf('@');
        String userInfo = at < 0 ? null : authority.substring(0, at);
        String hosts = authority.substring(at + 1);
        if (hosts.isBlank()) {
            throw new IllegalArgumentException("Database URL has no host: " + redact(whole));
        }
        for (String host : hosts.split(",", -1)) {
            if (!HOST.matcher(host).matches()) {
                throw new IllegalArgumentException("Malformed database URL: " + redact(whole));
            }
        }

        int queryStart = tail.indexOf('?');
        String path = queryStart < 0 ? tail : tail.substring(0, queryStart);
        Map<String, String> params = new LinkedHashMap<>();
        if (queryStart >= 0) {
            for (String pair : tail.substring(queryStart + 1).split("&")) {
                if (pair.isBlank()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                params.put(decode(eq < 0 ? pair : pair.substring(0, eq)), eq < 0 ? "" : decode(pair.substring(eq + 1)));
            }
        }

        String user = null;
        String password = null;
        if (userInfo != null && !userInfo.isBlank()) {
            int separator = userInfo.indexOf(':');
            user = decode(separator < 0 ? userInfo : userInfo.substring(0, separator));
            password = separator < 0 ? null : decode(userInfo.substring(separator + 1));
        }
        return assemble(hosts, path.isEmpty() ? "/" : path, params, user, password);
    }

    /** libpq's {@code key=value key='quoted value'} form. */
    private static DatabaseUrl fromKeywordValue(String text) {
        Map<String, String> params = new LinkedHashMap<>();
        int i = 0;
        int n = text.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            int eq = text.indexOf('=', i);
            if (i >= n) {
                break;
            }
            if (eq < 0) {
                throw new IllegalArgumentException("Malformed database connection string");
            }
            String key = text.substring(i, eq).trim();
            i = eq + 1;
            StringBuilder value = new StringBuilder();
            if (i < n && text.charAt(i) == '\'') {
                i++;
                while (i < n && text.charAt(i) != '\'') {
                    if (text.charAt(i) == '\\' && i + 1 < n) {
                        i++;
                    }
                    value.append(text.charAt(i++));
                }
                i++;
            } else {
                while (i < n && !Character.isWhitespace(text.charAt(i))) {
                    value.append(text.charAt(i++));
                }
            }
            params.put(key, value.toString());
        }

        String hostList = params.remove("host");
        if (hostList == null || hostList.isBlank()) {
            throw new IllegalArgumentException("Database connection string has no host");
        }
        String[] hostNames = hostList.split(",");
        String[] ports = params.containsKey("port") ? params.remove("port").split(",") : new String[0];
        StringBuilder hosts = new StringBuilder();
        for (int h = 0; h < hostNames.length; h++) {
            if (h > 0) {
                hosts.append(',');
            }
            String name = hostNames[h].trim();
            hosts.append(name.contains(":") ? "[" + name + "]" : name);
            if (ports.length > 0) {
                hosts.append(':').append(ports[ports.length == 1 ? 0 : h].trim());
            }
        }
        String database = params.remove("dbname");
        return assemble(hosts.toString(), database == null ? "/" : "/" + encode(database), params,
                null, null);
    }

    /**
     * @param params the connection parameters, values decoded and keys as
     *               libpq spells them
     */
    private static DatabaseUrl assemble(String hosts, String path, Map<String, String> params,
            String user, String password) {
        // The URL's own credentials win; the parameters are the fallback.
        String queryUser = params.remove("user");
        String queryPassword = params.remove("password");
        if (user == null) {
            user = queryUser;
        }
        if (password == null) {
            password = queryPassword;
        }

        boolean pooled = "true".equalsIgnoreCase(params.get("pgbouncer")) || looksPooled(hosts);

        StringBuilder jdbc = new StringBuilder("jdbc:postgresql://").append(hosts).append(path);
        char separator = '?';
        boolean preparedStatementsSet = false;
        for (Map.Entry<String, String> param : params.entrySet()) {
            String key = param.getKey();
            if (NOT_FOR_JDBC.contains(key.toLowerCase(Locale.ROOT))) {
                continue;
            }
            key = LIBPQ_TO_JDBC.getOrDefault(key, key);
            preparedStatementsSet |= "prepareThreshold".equals(key);
            jdbc.append(separator).append(key).append('=').append(encode(param.getValue()));
            separator = '&';
        }
        if (pooled && !preparedStatementsSet) {
            jdbc.append(separator).append("prepareThreshold=0");
        }

        return new DatabaseUrl(jdbc.toString(), Optional.ofNullable(user).filter(u -> !u.isEmpty()),
                Optional.ofNullable(password).filter(p -> !p.isEmpty()));
    }

    private static boolean looksPooled(String hosts) {
        for (String host : hosts.split(",")) {
            if (host.toLowerCase(Locale.ROOT).contains("pooler")) {
                return true;
            }
            int colon = host.lastIndexOf(':');
            if (colon > 0 && host.indexOf(']') < colon && POOLER_PORTS.contains(host.substring(colon + 1))) {
                return true;
            }
        }
        return false;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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
