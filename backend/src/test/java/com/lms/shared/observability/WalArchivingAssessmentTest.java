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
import java.time.Instant;

import com.lms.shared.observability.WalArchivingHealthIndicator.ArchiverSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;

import static org.assertj.core.api.Assertions.assertThat;

/** The walArchiving decision with fixed inputs and a fixed clock. */
class WalArchivingAssessmentTest {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final Duration RPO = Duration.ofMinutes(5);
    private static final long SEGMENT = 16L * 1024 * 1024;
    /** Segment 0/3 on timeline 1; it ends at LSN 0/4000000. */
    private static final String SEGMENT_3 = "000000010000000000000003";

    private static ArchiverSnapshot on(Instant archived, Instant failed, String currentLsn) {
        return new ArchiverSnapshot("on", archived == null ? null : SEGMENT_3, archived, failed,
                failed == null ? 0 : 1, currentLsn, SEGMENT);
    }

    private static String status(Health health) {
        return health.getStatus().getCode();
    }

    @Test
    @DisplayName("Archiving off is UNKNOWN and says it is provider-managed, never DOWN")
    void off() {
        Health health = WalArchivingHealthIndicator.assess(
                new ArchiverSnapshot("off", null, null, null, 0, "0/1000000", SEGMENT), NOW, RPO);
        assertThat(status(health)).isEqualTo("UNKNOWN");
        assertThat(health.getDetails().get("detail").toString()).contains("provider-managed");
    }

    @Test
    @DisplayName("A recent archive is UP")
    void recent() {
        Health health = WalArchivingHealthIndicator.assess(on(NOW.minusSeconds(30), null, "0/4001000"), NOW, RPO);
        assertThat(status(health)).isEqualTo("UP");
        assertThat(health.getDetails()).containsEntry("secondsSinceLastArchive", 30L);
        assertThat(health.getDetails()).containsEntry("pendingWalBytes", 0x1000L);
    }

    @Test
    @DisplayName("An old archive with WAL written since is DOWN")
    void staleWithPendingWal() {
        Health health = WalArchivingHealthIndicator.assess(on(NOW.minusSeconds(301), null, "0/4001000"), NOW, RPO);
        assertThat(status(health)).isEqualTo("DOWN");
        assertThat(health.getDetails().get("reason").toString()).contains("RPO");
    }

    @Test
    @DisplayName("An old archive on an idle database is UP: nothing is waiting to be archived")
    void staleButIdle() {
        Health health = WalArchivingHealthIndicator.assess(on(NOW.minus(Duration.ofHours(6)), null, "0/4000000"), NOW, RPO);
        assertThat(status(health)).isEqualTo("UP");
        assertThat(health.getDetails()).containsEntry("pendingWalBytes", 0L);
    }

    @Test
    @DisplayName("Exactly at the RPO is still UP; a second past it is not")
    void boundary() {
        assertThat(status(WalArchivingHealthIndicator.assess(on(NOW.minus(RPO), null, "0/4001000"), NOW, RPO)))
                .isEqualTo("UP");
        assertThat(status(WalArchivingHealthIndicator.assess(
                        on(NOW.minus(RPO).minusSeconds(1), null, "0/4001000"), NOW, RPO)))
                .isEqualTo("DOWN");
    }

    @Test
    @DisplayName("A failure after the last success is DOWN even when the success is recent")
    void failureAfterSuccess() {
        Health health = WalArchivingHealthIndicator.assess(
                on(NOW.minusSeconds(20), NOW.minusSeconds(10), "0/4000000"), NOW, RPO);
        assertThat(status(health)).isEqualTo("DOWN");
        assertThat(health.getDetails().get("reason").toString()).contains("failed");
    }

    @Test
    @DisplayName("A failure that was followed by a success is history, not an outage")
    void failureBeforeSuccess() {
        Health health = WalArchivingHealthIndicator.assess(
                on(NOW.minusSeconds(10), NOW.minusSeconds(20), "0/4000000"), NOW, RPO);
        assertThat(status(health)).isEqualTo("UP");
    }

    @Test
    @DisplayName("Never archived and never failed is UNKNOWN; never archived and failing is DOWN")
    void neverArchived() {
        assertThat(status(WalArchivingHealthIndicator.assess(on(null, null, "0/1000"), NOW, RPO)))
                .isEqualTo("UNKNOWN");
        assertThat(status(WalArchivingHealthIndicator.assess(on(null, NOW, "0/1000"), NOW, RPO)))
                .isEqualTo("DOWN");
    }

    @Test
    @DisplayName("Pending WAL is measured across the 4 GiB log boundary and refused for non-segment files")
    void pendingAcrossLogBoundary() {
        // Segment FF of log 0 ends exactly where log 1 begins.
        ArchiverSnapshot s = new ArchiverSnapshot("on", "0000000100000000000000FF", NOW, null, 0, "1/200", SEGMENT);
        assertThat(s.pendingWalBytes()).isEqualTo(0x200L);
        ArchiverSnapshot history = new ArchiverSnapshot("on", "00000002.history", NOW, null, 0, "1/200", SEGMENT);
        assertThat(history.pendingWalBytes()).isNull();
    }

    @Test
    @DisplayName("Details carry no command, path or repository location")
    void noSecretsInDetails() {
        Health health = WalArchivingHealthIndicator.assess(on(NOW.minusSeconds(30), null, "0/4001000"), NOW, RPO);
        assertThat(health.getDetails().keySet())
                .containsOnly("archiveMode", "rpoSeconds", "failedCount", "lastArchivedAt",
                        "secondsSinceLastArchive", "pendingWalBytes");
    }
}
