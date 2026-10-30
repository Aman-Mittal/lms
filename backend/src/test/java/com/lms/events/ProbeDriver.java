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
import java.util.UUID;

import com.lms.acceptance.TestFixtures;
import com.lms.events.command.EventRetrier;
import com.lms.shared.tenant.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishing probes and stepping the retrier, shared by the tests that drive
 * the dead-letter machinery through a real registry. The test database
 * connection is a superuser outside transactions, so these reads see every
 * tenant -- which is what an assertion about the registry needs.
 */
public final class ProbeDriver {

    private final ApplicationEventPublisher publisher;
    private final PlatformTransactionManager transactionManager;
    private final JdbcClient jdbc;
    private final EventRetrier retrier;
    private Instant clock = Instant.now();

    public ProbeDriver(ApplicationEventPublisher publisher, PlatformTransactionManager transactionManager,
                       JdbcClient jdbc, EventRetrier retrier) {
        this.publisher = publisher;
        this.transactionManager = transactionManager;
        this.jdbc = jdbc;
        this.retrier = retrier;
    }

    public static UUID newTenant(TestFixtures fixtures, String prefix) {
        String salt = UUID.randomUUID().toString().substring(0, 8);
        return fixtures.createTenant(prefix + "-" + salt, prefix + " " + salt);
    }

    /** Publishes in a committed transaction, so the listener runs after commit as in production. */
    public void publish(UUID tenantId, String key, int failTimes) {
        ProbeListener.failTimes(key, failTimes);
        TenantContext.runWith(tenantId, null, () -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> publisher.publishEvent(new ProbeEvent(tenantId, key))));
    }

    /** One retrier pass, a minute later than the last, so no backoff is ever still running. */
    public EventRetrier.Pass step() {
        clock = clock.plusSeconds(60);
        return retrier.runOnce(clock);
    }

    /** Steps until the probe is parked or completed, at most {@code passes} times. */
    public void stepUntilSettled(String key, int passes) {
        for (int i = 0; i < passes && publicationId(key) != null && !completed(key); i++) {
            step();
        }
    }

    public UUID publicationId(String key) {
        return jdbc.sql("""
                        SELECT id FROM event_publication
                         WHERE listener_id LIKE '%ProbeListener%' AND serialized_event LIKE '%' || :key || '%'
                        """)
                .param("key", key).query(UUID.class).optional().orElse(null);
    }

    public boolean completed(String key) {
        return jdbc.sql("""
                        SELECT completion_date IS NOT NULL FROM event_publication
                         WHERE listener_id LIKE '%ProbeListener%' AND serialized_event LIKE '%' || :key || '%'
                        """)
                .param("key", key).query(Boolean.class).optional().orElse(false);
    }

    public UUID parkedId(String key) {
        return jdbc.sql("""
                        SELECT id FROM event_dead_letter
                         WHERE listener_id LIKE '%ProbeListener%' AND serialized_event LIKE '%' || :key || '%'
                        """)
                .param("key", key).query(UUID.class).optional().orElse(null);
    }
}
