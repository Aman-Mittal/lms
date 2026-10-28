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
package com.lms.web;

import java.util.List;
import java.util.UUID;

import com.lms.acceptance.TestFixtures;
import com.lms.shared.idempotency.IdempotencyService;
import com.lms.shared.tenant.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scheduled prune of stored idempotency keys, run against real Postgres.
 *
 * <p>It once bound a raw {@code Instant}, which the PostgreSQL driver cannot
 * type, so on a real database every sweep failed and nothing was ever deleted.
 */
@SpringBootTest
class IdempotencyPruneTest {

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
    }

    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private IdempotencyService idempotency;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("Pruning removes completed keys past retention and keeps everything else")
    void prunesOnlyOldCompletedKeys() {
        UUID tenantId = fixtures.createTenant("idem-" + UUID.randomUUID().toString().substring(0, 8),
                "Idempotency Prune");
        insert(tenantId, "old-completed", "COMPLETED", 72);
        insert(tenantId, "old-in-progress", "IN_PROGRESS", 72);
        insert(tenantId, "recent-completed", "COMPLETED", 1);

        idempotency.pruneExpired();

        assertThat(keysOf(tenantId)).containsExactlyInAnyOrder("old-in-progress", "recent-completed");
    }

    private void insert(UUID tenantId, String key, String state, int ageHours) {
        inTenant(tenantId, () -> jdbc.sql("""
                        INSERT INTO idempotency_key (tenant_id, key, request_hash, state, created_at)
                        VALUES (:t, :k, 'hash', :s, now() - make_interval(hours => :h))
                        """)
                .param("t", tenantId).param("k", key).param("s", state).param("h", ageHours)
                .update());
    }

    private List<String> keysOf(UUID tenantId) {
        return inTenant(tenantId, () -> jdbc.sql("SELECT key FROM idempotency_key WHERE tenant_id = :t")
                .param("t", tenantId).query(String.class).list());
    }

    /** Scope first, transaction second: the transaction manager reads the scope as it starts. */
    private <T> T inTenant(UUID tenantId, java.util.function.Supplier<T> action) {
        return TenantContext.callWith(tenantId, null,
                () -> new TransactionTemplate(transactionManager).execute(status -> action.get()));
    }
}
