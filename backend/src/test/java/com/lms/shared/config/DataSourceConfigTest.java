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

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which database user the pool connects as. The pool is created lazily, so none
 * of these opens a connection.
 */
class DataSourceConfigTest {

    private static HikariDataSource poolFor(MockEnvironment environment) {
        return (HikariDataSource) new DataSourceConfig().dataSource(environment);
    }

    @Test
    @DisplayName("The user and password in a platform-supplied DATABASE_URL are used")
    void urlCredentialsAreUsed() {
        // application.yaml resolves an unset DATABASE_USERNAME to an empty string.
        HikariDataSource pool = poolFor(new MockEnvironment()
                .withProperty("spring.datasource.url", "postgresql://neondb_owner:s3cret@host.example/neondb?sslmode=require")
                .withProperty("spring.datasource.username", "")
                .withProperty("spring.datasource.password", ""));

        assertThat(pool.getJdbcUrl()).isEqualTo("jdbc:postgresql://host.example/neondb?sslmode=require");
        assertThat(pool.getUsername()).isEqualTo("neondb_owner");
        assertThat(pool.getPassword()).isEqualTo("s3cret");
    }

    @Test
    @DisplayName("An explicit DATABASE_USERNAME beats the user in the URL")
    void explicitUsernameWins() {
        HikariDataSource pool = poolFor(new MockEnvironment()
                .withProperty("spring.datasource.url", "postgresql://neondb_owner:s3cret@host.example/neondb")
                .withProperty("spring.datasource.username", "operator"));

        assertThat(pool.getUsername()).isEqualTo("operator");
    }

    @Test
    @DisplayName("With no user anywhere the pool connects as lms, as local development expects")
    void fallsBackToLms() {
        HikariDataSource pool = poolFor(new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/lms")
                .withProperty("spring.datasource.username", ""));

        assertThat(pool.getUsername()).isEqualTo("lms");
    }
}
