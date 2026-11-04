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
package com.lms.finance.command;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.lms.acceptance.TenantSeeds;
import com.lms.acceptance.TenantSeeds.Seeded;
import com.lms.shared.tenant.TenantContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
 * A trip's completion and its consignment's clean proof decide the trip's
 * proof hold in separate after-commit transactions, in either order and
 * possibly at once. Whatever the order, a trip whose proofs are all clean must
 * end with no open hold.
 *
 * <p>The database is reached directly (as the owner) to reset state and to
 * record a confirmed proof, standing in for the delivery desk.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProofHoldRaceIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.7-alpine")
            .withDatabaseName("lms").withUsername("lms").withPassword("lms");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private TenantSeeds seeds;
    @Autowired
    private SettlementHolds holds;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private Seeded a;
    private UUID trip;
    private UUID consignment;

    @BeforeAll
    void seed() {
        a = seeds.seed(0.0);
        trip = a.id("trip");
        consignment = a.id("consignment");
    }

    @BeforeEach
    void unprovedAndUnheld() {
        jdbc.sql("DELETE FROM delivery_proof WHERE tenant_id = :t").param("t", a.tenantId()).update();
        jdbc.sql("DELETE FROM settlement_hold WHERE tenant_id = :t").param("t", a.tenantId()).update();
    }

    private void proveClean() {
        jdbc.sql("""
                        INSERT INTO delivery_proof (id, tenant_id, consignment_id, status, otp_hash, otp_issued_at,
                                                    otp_expires_at, recipient_name, delivered_at)
                        VALUES (gen_random_uuid(), :t, :c, 'CONFIRMED', 'x', now(), now() + interval '1 hour',
                                'Asha Rao', now())
                        """).param("t", a.tenantId()).param("c", consignment).update();
    }

    private long openHolds() {
        return jdbc.sql("""
                        SELECT count(*) FROM settlement_hold
                         WHERE tenant_id = :t AND trip_id = :trip AND released_at IS NULL
                        """).param("t", a.tenantId()).param("trip", trip).query(Long.class).single();
    }

    private <T> T inTenant(java.util.function.Supplier<T> action) {
        return TenantContext.callWith(a.tenantId(), a.orgPath(), action);
    }

    @Test
    @DisplayName("a proof committed before the completion decides leaves nothing to hold")
    void proofFirst() {
        proveClean();
        assertThat(inTenant(() -> holds.holdForProofs(trip, Instant.now()))).isFalse();
        assertThat(openHolds()).isZero();
    }

    @Test
    @DisplayName("a completion that held the trip is released by the clean proof that follows")
    void completionFirst() {
        assertThat(inTenant(() -> holds.holdForProofs(trip, Instant.now()))).isTrue();
        proveClean();
        List<UUID> released = inTenant(() -> holds.releaseWhereProved(consignment, Instant.now()));
        assertThat(released).containsExactly(trip);
        assertThat(openHolds()).isZero();
    }

    @Test
    @DisplayName("a proof re-evaluated while the completion is still deciding waits for it and then releases its hold")
    void interleaved() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        // The completion: it has taken the trip, read no proof, and placed its
        // hold -- but has not committed yet.
        CompletableFuture<Void> completion = CompletableFuture.runAsync(() ->
                TenantContext.runWith(a.tenantId(), a.orgPath(), () ->
                        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                            holds.lockTrip(trip);
                            jdbc.sql("""
                                            INSERT INTO settlement_hold (tenant_id, exception_id, trip_id, ticket_no,
                                                                         placed_at, reason)
                                            VALUES (:t, :hold, :trip, 'PROOF-OF-DELIVERY', now(), 'PROOF_OF_DELIVERY')
                                            """)
                                    .param("t", a.tenantId())
                                    .param("hold", SettlementHolds.proofHoldId(trip))
                                    .param("trip", trip)
                                    .update();
                            locked.countDown();
                            try {
                                assertThat(commit.await(30, TimeUnit.SECONDS)).isTrue();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(e);
                            }
                        })));
        assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();

        // The proof commits now, and its re-evaluation starts at once.
        proveClean();
        CompletableFuture<List<UUID>> reEvaluation = CompletableFuture.supplyAsync(() ->
                inTenant(() -> holds.releaseWhereProved(consignment, Instant.now())));

        Thread.sleep(500);
        assertThat(reEvaluation).as("the re-evaluation waits for the completion").isNotDone();

        commit.countDown();
        completion.get(30, TimeUnit.SECONDS);
        assertThat(reEvaluation.get(30, TimeUnit.SECONDS)).containsExactly(trip);
        assertThat(openHolds()).as("no hold outlives the clean proof").isZero();
    }
}
