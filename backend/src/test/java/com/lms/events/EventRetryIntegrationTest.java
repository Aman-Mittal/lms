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
package com.lms.events;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.lms.acceptance.TestFixtures;
import com.lms.events.command.EventRetrier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retries and parking, through Spring Modulith's real publication registry and
 * a listener that fails on command. The retrier is driven by hand, with a
 * stepped clock, so nothing here waits on a timer.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventRetryIntegrationTest {

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
        registry.add("lms.test.probe-listener", () -> "true");
        registry.add("lms.events.retry.enabled", () -> "false");
        registry.add("lms.events.retry.max-attempts", () -> "3");
        registry.add("lms.events.retry.initial-backoff", () -> "PT1S");
        registry.add("lms.events.retry.grace-period", () -> "PT1S");
    }

    @Autowired
    private ApplicationEventPublisher publisher;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private EventRetrier retrier;
    @Autowired
    private TestFixtures fixtures;

    private ProbeDriver probes;
    private UUID tenant;

    @BeforeAll
    void setUp() {
        probes = new ProbeDriver(publisher, transactionManager, jdbc, retrier);
        tenant = ProbeDriver.newTenant(fixtures, "retry");
    }

    private Map<String, Object> failureOf(UUID publicationId) {
        return jdbc.sql("SELECT failures, last_error, tenant_id FROM event_failure WHERE publication_id = :id")
                .param("id", publicationId).query().singleRow();
    }

    @Test
    @DisplayName("a failing listener's failure is recorded against its publication, with the error")
    void failureIsRecorded() {
        String key = "recorded-" + UUID.randomUUID();
        probes.publish(tenant, key, 1);

        UUID publication = probes.publicationId(key);
        assertThat(publication).as("the registry holds the incomplete publication").isNotNull();
        Map<String, Object> failure = failureOf(publication);
        assertThat(failure.get("failures")).isEqualTo(1);
        assertThat((String) failure.get("last_error")).contains("IllegalStateException").contains(key);
        assertThat(failure.get("tenant_id")).isEqualTo(tenant);
    }

    @Test
    @DisplayName("a listener that recovers is retried until it completes, and nothing is parked")
    void retriedUntilItSucceeds() {
        String key = "recovers-" + UUID.randomUUID();
        probes.publish(tenant, key, 2);
        assertThat(ProbeListener.calls(key)).isEqualTo(1);

        EventRetrier.Pass first = probes.step();
        assertThat(first.resubmitted()).isPositive();
        assertThat(ProbeListener.calls(key)).as("one retry, which fails again").isEqualTo(2);
        probes.step();

        assertThat(ProbeListener.successes(key)).as("the third call succeeds").isEqualTo(1);
        assertThat(probes.completed(key)).as("the registry marks it complete").isTrue();
        assertThat(probes.parkedId(key)).as("nothing parked").isNull();
        UUID publication = probes.publicationId(key);
        assertThat(jdbc.sql("SELECT count(*) FROM event_failure WHERE publication_id = :id")
                .param("id", publication).query(Integer.class).single())
                .as("a retry that worked leaves no failure record behind")
                .isZero();
    }

    @Test
    @DisplayName("a listener that always fails is parked after the last attempt and leaves the registry")
    void parkedAfterTheLastAttempt() {
        String key = "always-" + UUID.randomUUID();
        probes.publish(tenant, key, ProbeListener.ALWAYS);
        UUID publication = probes.publicationId(key);

        probes.step();
        probes.step();
        assertThat(ProbeListener.calls(key)).as("first call and two retries").isEqualTo(3);
        assertThat(probes.parkedId(key)).as("not parked before the attempts are used up").isNull();

        EventRetrier.Pass parking = probes.step();
        assertThat(parking.parked()).isPositive();

        Map<String, Object> parked = jdbc.sql("""
                        SELECT id, tenant_id, status, attempts, last_error FROM event_dead_letter WHERE id = :id
                        """).param("id", publication).query().singleRow();
        assertThat(parked.get("tenant_id")).isEqualTo(tenant);
        assertThat(parked.get("status")).isEqualTo("PARKED");
        assertThat(parked.get("attempts")).isEqualTo(3);
        assertThat((String) parked.get("last_error")).contains("was told to fail");

        assertThat(probes.publicationId(key))
                .as("gone from the registry, so a restart cannot republish it").isNull();
        assertThat(jdbc.sql("SELECT count(*) FROM event_failure WHERE publication_id = :id")
                .param("id", publication).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM audit_log WHERE action = 'EVENT_PARKED' AND resource_id = :id
                        """).param("id", publication).query(Integer.class).single()).isEqualTo(1);

        probes.step();
        probes.step();
        assertThat(ProbeListener.calls(key)).as("a parked event is never retried").isEqualTo(3);
    }

    @Test
    @DisplayName("a publication with no recorded failure is left alone for its grace period")
    void graceForInFlight() {
        String key = "grace-" + UUID.randomUUID();
        probes.publish(tenant, key, 0);
        // A crash between commit and listener: the publication is incomplete and nothing failed.
        jdbc.sql("""
                        UPDATE event_publication SET completion_date = NULL, status = 'PUBLISHED'
                         WHERE listener_id LIKE '%ProbeListener%' AND serialized_event LIKE '%' || :key || '%'
                        """).param("key", key).update();
        int before = ProbeListener.calls(key);

        retrier.runOnce(Instant.now().minusSeconds(3600));
        assertThat(ProbeListener.calls(key)).as("inside the grace period: not touched").isEqualTo(before);

        probes.step();
        assertThat(ProbeListener.calls(key)).as("after it: resubmitted").isEqualTo(before + 1);
        assertThat(probes.completed(key)).isTrue();
    }
}
