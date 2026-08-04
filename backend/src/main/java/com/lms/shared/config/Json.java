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

    @Override
    public String toString() {
        return value;
    }
}
