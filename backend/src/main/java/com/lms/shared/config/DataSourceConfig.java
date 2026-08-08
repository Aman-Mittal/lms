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

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Builds the connection pool directly from the {@link Environment} instead of
 * relying on Spring Boot's {@code spring.datasource} auto-configuration.
 *
 * <p>Two problems are solved here, and neither is optional.
 *
 * <h2>1. AOT processing cannot bind {@code @ConfigurationProperties}</h2>
 *
 * <p>Spring Boot's AOT step instantiates the bean graph at image-build time, but
 * {@code @ConfigurationProperties} binding is performed by a bean post-processor
 * that does not run in that phase. Boot's {@code DataSourceProperties} is
 * therefore constructed with every field null, and the native build dies with
 * "Failed to determine a suitable driver class" -- an error that names the
 * driver while the real problem is that no property was bound at all. Passing
 * the values as system properties or environment variables does not help,
 * because the values are present and the binding is what is missing.
 *
 * <p>Reading the {@code Environment} directly sidesteps the binding step
 * entirely: it works identically during AOT and at runtime.
 *
 * <h2>2. Platform-supplied database URLs are not JDBC URLs</h2>
 *
 * <p>Render injects {@code DATABASE_URL} in libpq form. {@link DatabaseUrl}
 * converts it, and doing that here -- rather than by mutating system properties
 * before the context starts -- keeps the conversion on the one code path that
 * actually consumes the value.
 *
 * <p>The pool is created with the no-argument constructor and configured with
 * setters. That matters: {@code new HikariDataSource(HikariConfig)} eagerly
 * opens the pool, which would attempt a real connection during the native build.
 * The no-argument form defers pool creation to the first {@code getConnection}.
 */
@Configuration(proxyBeanMethods = false)
public class DataSourceConfig {

    /**
     * Used only when no URL is configured at all, which in practice means AOT
     * processing. It is never connected to -- the pool is lazy, and the real
     * URL is always present at runtime.
     */
    private static final String BUILD_TIME_PLACEHOLDER_URL = "jdbc:postgresql://localhost:5432/lms";

    @Bean
    @ConditionalOnMissingBean(DataSource.class)
    DataSource dataSource(Environment environment) {
        String rawUrl = firstNonBlank(
                environment.getProperty("spring.datasource.url"),
                environment.getProperty("DATABASE_URL"));

        String username = firstNonBlank(
                environment.getProperty("spring.datasource.username"),
                environment.getProperty("DATABASE_USERNAME"));
        String password = firstNonBlank(
                environment.getProperty("spring.datasource.password"),
                environment.getProperty("DATABASE_PASSWORD"));

        String jdbcUrl = BUILD_TIME_PLACEHOLDER_URL;
        if (rawUrl != null) {
            DatabaseUrl parsed = DatabaseUrl.parse(rawUrl);
            jdbcUrl = parsed.jdbcUrl();
            // Credentials embedded in the URL lose to explicitly configured
            // ones, so an operator can override a platform-supplied value.
            if (username == null) {
                username = parsed.username().orElse(null);
            }
            if (password == null) {
                password = parsed.password().orElse(null);
            }
        }

        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(jdbcUrl);
        dataSource.setDriverClassName("org.postgresql.Driver");
        if (username != null) {
            dataSource.setUsername(username);
        }
        if (password != null) {
            dataSource.setPassword(password);
        }

        // Sized for Render's free tier: 0.1 CPU against a small Postgres. A
        // larger pool buys contention, not throughput.
        dataSource.setMaximumPoolSize(environment.getProperty("DB_POOL_MAX", Integer.class, 5));
        dataSource.setMinimumIdle(environment.getProperty("DB_POOL_MIN", Integer.class, 1));
        dataSource.setConnectionTimeout(10_000);
        dataSource.setIdleTimeout(300_000);
        dataSource.setMaxLifetime(900_000);
        dataSource.setPoolName("lms-pool");

        return dataSource;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
