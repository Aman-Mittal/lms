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

/**
 * A JSON document destined for a Postgres {@code jsonb} column.
 *
 * <p>A distinct type, not a bare {@link String}, and that is the whole point.
 * Spring Data JDBC selects converters by type, so a {@code String -> PGobject}
 * writing converter would apply to <em>every</em> string property in the
 * application and try to send each one as jsonb. Wrapping the value confines
 * the conversion to the handful of columns that actually are jsonb.
 */
public record Json(String value) {

    public Json {
        if (value == null) {
            throw new IllegalArgumentException("JSON value must not be null; use a null Json reference instead");
        }
    }

    public static Json of(String value) {
        return value == null ? null : new Json(value);
    }

    /**
     * A flat JSON object from alternating keys and values.
     *
     * <p>Exists for the audit trail, whose before-and-after snapshots are the
     * only JSON this application composes by hand. A full serialiser would be
     * the obvious answer and the wrong one: an audit snapshot has to be
     * readable years later by somebody with no access to the class that wrote
     * it, so it is written from an explicit list of fields rather than from
     * whatever an aggregate happened to contain that release.
     *
     * <p>A null value becomes JSON {@code null}, which is the honest rendering
     * of "this field had no value" and distinct from the field being absent.
     *
     * @throws IllegalArgumentException if the arguments do not pair up
     */
    public static Json object(String... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("Keys and values must pair up");
        }
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < keysAndValues.length; i += 2) {
            if (i > 0) {
                json.append(',');
            }
            json.append(quote(keysAndValues[i])).append(':');
            if (keysAndValues[i + 1] == null) {
                json.append("null");
            } else {
                json.append(quote(keysAndValues[i + 1]));
            }
        }
        return new Json(json.append('}').toString());
    }

    /**
     * Escapes a string into a JSON string literal.
     *
     * <p>Control characters are escaped as six-character Unicode sequences
     * rather than dropped.
     * A vendor's dispute note is free text somebody typed, and a stray newline
     * in it must not be able to produce a document Postgres refuses -- which
     * would fail the mutation being audited rather than the audit of it.
     */
    private static String quote(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 2).append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\" + "u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    @Override
    public String toString() {
        return value;
    }
}
