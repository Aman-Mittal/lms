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
package com.lms.identity;

import com.lms.identity.command.AuthService;
import com.lms.identity.command.DemoTenantSeeder;
import com.lms.shared.tenant.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A fresh deployment with the demo switch on can be signed into.
 *
 * <p>Against real Postgres, because the seeder's whole difficulty is row-level
 * security: it has to create a tenant before any tenant scope can exist, and a
 * version that only worked as a superuser would pass against H2 and fail on
 * Render.
 */
@SpringBootTest
class DemoTenantSeederTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.7-alpine")
            .withDatabaseName("lms").withUsername("lms").withPassword("lms");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("lms.demo.enabled", () -> "true");
        registry.add("lms.demo.tenant-code", () -> "demo");
        registry.add("lms.demo.admin-email", () -> "Admin@Demo.Local");
        registry.add("lms.demo.admin-password", () -> "correct horse battery staple");
    }

    @Autowired
    private AuthService auth;
    @Autowired
    private DemoTenantSeeder seeder;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("the seeded administrator can sign in and holds every permission")
    void administratorCanSignIn() {
        AuthService.AuthResult signedIn = auth.login("demo", "admin@demo.local",
                "correct horse battery staple", null);

        assertThat(signedIn.accessToken()).isNotBlank();
        assertThat(signedIn.permissions())
                .as("the demo administrator should reach every module")
                .containsExactlyInAnyOrderElementsOf(
                        jdbc.sql("SELECT code FROM permission").query(String.class).list());
    }

    @Test
    @DisplayName("seeding again on the next start changes nothing")
    void seedingIsIdempotent() throws Exception {
        seeder.run(new DefaultApplicationArguments());
        seeder.run(new DefaultApplicationArguments());

        Integer tenants = jdbc.sql("SELECT count(*) FROM tenant WHERE code = 'demo'")
                .query(Integer.class).single();
        assertThat(tenants).isEqualTo(1);

        java.util.UUID tenantId = jdbc.sql("SELECT resolve_tenant_by_code('demo')")
                .query(java.util.UUID.class).single();
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        Integer users = TenantContext.callWith(tenantId, null, () -> transactions.execute(status ->
                jdbc.sql("SELECT count(*) FROM app_user").query(Integer.class).single()));
        assertThat(users).isEqualTo(1);
    }
}
