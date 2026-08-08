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
package com.lms.acceptance;

import com.lms.LmsApplication;
import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Boots the application once for the whole Cucumber suite, against a real
 * PostgreSQL container.
 *
 * <p>Real Postgres, never H2. H2 has no row-level security, and RLS is the
 * tenant-isolation guarantee most worth testing (DOCS/adr/0005) -- a suite that
 * cannot exercise it would pass while the property it is meant to protect was
 * broken.
 *
 * <p>Properties are published with {@link DynamicPropertySource} rather than
 * Testcontainers' {@code @ServiceConnection}. That is deliberate:
 * {@code @ServiceConnection} supplies a {@code JdbcConnectionDetails} bean,
 * which only helps if Spring Boot builds the DataSource. This application
 * builds its own from the {@code Environment} (see {@code DataSourceConfig},
 * and DOCS/adr on AOT), so the connection details bean would be ignored and the
 * tests would silently try to reach localhost.
 */
@CucumberContextConfiguration
@SpringBootTest(classes = LmsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class AcceptanceTestContext {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.7-alpine")
            .withDatabaseName("lms")
            .withUsername("lms")
            .withPassword("lms");

    static {
        // Started once and shared by every scenario. Reuse across the suite
        // keeps the run fast; scenarios isolate themselves by using distinct
        // tenants rather than by recreating the database.
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
