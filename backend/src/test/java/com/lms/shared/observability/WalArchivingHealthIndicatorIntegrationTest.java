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
package com.lms.shared.observability;

import java.time.Duration;
import java.util.function.Predicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The walArchiving indicator against real servers: one with archiving off (as
 * on a managed database) and one archiving through a stand-in command, which is
 * then made to fail. No application context: the indicator needs a connection
 * and nothing else.
 */
class WalArchivingHealthIndicatorIntegrationTest {

    private static final Duration RPO = Duration.ofMinutes(5);

    private static PostgreSQLContainer<?> postgres(String... command) {
        return new PostgreSQLContainer<>("postgres:17.7-alpine")
                .withDatabaseName("lms")
                .withUsername("lms")
                .withPassword("lms")
                .withCommand(command);
    }

    private static JdbcClient jdbc(PostgreSQLContainer<?> pg) {
        return JdbcClient.create(new SimpleDriverDataSource(
                new org.postgresql.Driver(), pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
    }

    private static Health awaitHealth(WalArchivingHealthIndicator indicator, Predicate<Health> until)
            throws InterruptedException {
        Health health = indicator.health();
        for (int i = 0; i < 100 && !until.test(health); i++) {
            Thread.sleep(200);
            health = indicator.health();
        }
        return health;
    }

    private static void archiveCommand(JdbcClient jdbc, String command) {
        jdbc.sql("ALTER SYSTEM SET archive_command = '" + command + "'").update();
        jdbc.sql("SELECT pg_reload_conf()").query(Boolean.class).single();
    }

    @Test
    @DisplayName("Archiving off reports UNKNOWN as provider-managed, not DOWN")
    void archivingOff() {
        try (PostgreSQLContainer<?> pg = postgres("postgres", "-c", "fsync=off")) {
            pg.start();
            Health health = new WalArchivingHealthIndicator(jdbc(pg), RPO).health();
            assertThat(health.getStatus().getCode()).isEqualTo("UNKNOWN");
            assertThat(health.getDetails()).containsEntry("archiveMode", "off");
            assertThat(health.getDetails().get("detail").toString()).contains("provider-managed");
        }
    }

    @Test
    @DisplayName("Archiving on: UP once a switched segment is archived, DOWN once the command starts failing")
    void archivingOn() throws InterruptedException {
        try (PostgreSQLContainer<?> pg = postgres(
                "postgres", "-c", "fsync=off", "-c", "wal_level=replica",
                "-c", "archive_mode=on")) {
            pg.start();
            JdbcClient jdbc = jdbc(pg);
            WalArchivingHealthIndicator indicator = new WalArchivingHealthIndicator(jdbc, RPO);

            // Set through ALTER SYSTEM, not the command line: a command-line
            // setting outranks postgresql.auto.conf, so it could not be changed
            // to a failing command below.
            archiveCommand(jdbc, "/bin/true");

            jdbc.sql("CREATE TABLE wal_probe (id int)").update();
            jdbc.sql("SELECT pg_switch_wal()").query(String.class).single();
            Health up = awaitHealth(indicator, h -> h.getDetails().containsKey("lastArchivedAt"));
            assertThat(up.getStatus().getCode()).isEqualTo("UP");
            assertThat(up.getDetails()).containsEntry("archiveMode", "on").containsEntry("failedCount", 0L);
            assertThat(up.getDetails().keySet()).doesNotContain("archiveCommand");

            archiveCommand(jdbc, "/bin/false");
            jdbc.sql("INSERT INTO wal_probe VALUES (1)").update();
            jdbc.sql("SELECT pg_switch_wal()").query(String.class).single();
            Health down = awaitHealth(indicator, h -> "DOWN".equals(h.getStatus().getCode()));
            assertThat(down.getStatus().getCode()).isEqualTo("DOWN");
            assertThat(down.getDetails().get("reason").toString()).contains("failed");
            assertThat((Long) down.getDetails().get("failedCount")).isPositive();
        }
    }
}
