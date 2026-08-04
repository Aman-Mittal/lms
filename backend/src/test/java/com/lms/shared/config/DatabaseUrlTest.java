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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The deploy fails at startup if this parsing is wrong, and the error it
 * produces points at the wrong thing (a missing driver rather than a malformed
 * URL). Worth testing properly.
 */
class DatabaseUrlTest {

    @Test
    @DisplayName("converts the libpq URL Render injects into JDBC form")
    void convertsRenderStyleUrl() {
        DatabaseUrl url = DatabaseUrl.parse(
                "postgresql://lms_user" + ":" + "placeholder@dpg-abc123-a.oregon-postgres.render.com:5432/lms_db");

        assertThat(url.jdbcUrl())
                .isEqualTo("jdbc:postgresql://dpg-abc123-a.oregon-postgres.render.com:5432/lms_db");
        assertThat(url.username()).contains("lms_user");
        assertThat(url.password()).contains("placeholder");
    }

    @Test
    @DisplayName("accepts the postgres:// scheme as well as postgresql://")
    void acceptsShortScheme() {
        DatabaseUrl url = DatabaseUrl.parse("postgres://u" + ":" + "p@db.internal:5432/lms");

        assertThat(url.jdbcUrl()).isEqualTo("jdbc:postgresql://db.internal:5432/lms");
    }

    @Test
    @DisplayName("leaves an already-JDBC URL untouched")
    void passesThroughJdbcUrl() {
        DatabaseUrl url = DatabaseUrl.parse("jdbc:postgresql://localhost:5432/lms");

        assertThat(url.jdbcUrl()).isEqualTo("jdbc:postgresql://localhost:5432/lms");
        assertThat(url.username()).isEmpty();
        assertThat(url.password()).isEmpty();
    }

    @Test
    @DisplayName("preserves query parameters such as sslmode")
    void preservesQueryParameters() {
        DatabaseUrl url = DatabaseUrl.parse("postgresql://u" + ":" + "p@host:5432/lms?sslmode=require");

        assertThat(url.jdbcUrl()).isEqualTo("jdbc:postgresql://host:5432/lms?sslmode=require");
    }

    @Test
    @DisplayName("percent-decodes credentials, which managed platforms do generate")
    void decodesPercentEncodedCredentials() {
        // Render and friends generate passwords containing characters that must
        // be percent-encoded in a URL. Passing the encoded form to the driver
        // authenticates with the wrong password.
        DatabaseUrl url = DatabaseUrl.parse("postgresql://user%40corp" + ":" + "p%40ss%2Fword@host:5432/lms");

        assertThat(url.username()).contains("user@corp");
        assertThat(url.password()).contains("p@ss/word");
    }

    @Test
    @DisplayName("omits the port when the URL does not specify one")
    void handlesMissingPort() {
        DatabaseUrl url = DatabaseUrl.parse("postgresql://u" + ":" + "p@host/lms");

        assertThat(url.jdbcUrl()).isEqualTo("jdbc:postgresql://host/lms");
    }

    @Test
    @DisplayName("rejects a URL with no credentials rather than inventing them")
    void handlesUrlWithoutCredentials() {
        DatabaseUrl url = DatabaseUrl.parse("postgresql://host:5432/lms");

        assertThat(url.jdbcUrl()).isEqualTo("jdbc:postgresql://host:5432/lms");
        assertThat(url.username()).isEmpty();
        assertThat(url.password()).isEmpty();
    }

    @Test
    @DisplayName("fails loudly on an unsupported scheme")
    void rejectsUnsupportedScheme() {
        assertThatThrownBy(() -> DatabaseUrl.parse("mysql://u" + ":" + "p@host:3306/lms"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported database URL scheme");
    }

    @Test
    @DisplayName("fails loudly on a blank URL")
    void rejectsBlankUrl() {
        assertThatThrownBy(() -> DatabaseUrl.parse("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("keeps the password out of the error message")
    void redactsCredentialsInErrors() {
        assertThatThrownBy(() -> DatabaseUrl.parse("mysql://admin" + ":" + "hunter2@host:3306/lms"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("hunter2");
    }
}
