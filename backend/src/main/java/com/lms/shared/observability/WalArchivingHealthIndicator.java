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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Whether the database's write-ahead log is reaching the backup repository
 * fast enough to keep the recovery point objective ({@code lms.dr.rpo}, five
 * minutes by default). See {@code deploy/dr/README.md}.
 *
 * <ul>
 *   <li><b>UNKNOWN</b> when {@code archive_mode} is off. That is the normal
 *       state on a managed database (Render), where the provider takes the
 *       backups and the application cannot see them. It is not a fault this
 *       service can report on, so it must not turn the health endpoint DOWN.
 *   <li><b>DOWN</b> when the last archive attempt failed after the last
 *       success, or when WAL has been written since the last archived segment
 *       and that segment is older than the RPO.
 *   <li><b>UP</b> otherwise, including an idle database whose last archive is
 *       old only because nothing has been written since.
 * </ul>
 *
 * <p>Reads only {@code pg_stat_archiver} and settings visible to any role. The
 * details carry timings and counters, never the archive command, a path or a
 * repository location -- and they are hidden from callers anyway
 * ({@code show-details: never}). The indicator is not part of the readiness
 * group, so an archiving fault is reported without taking the service out of
 * the load balancer.
 */
@Component("walArchiving")
public class WalArchivingHealthIndicator implements HealthIndicator {

    private static final String SNAPSHOT_SQL = """
            SELECT current_setting('archive_mode')                    AS archive_mode,
                   a.last_archived_wal                                AS last_archived_wal,
                   a.last_archived_time                               AS last_archived_time,
                   a.last_failed_time                                 AS last_failed_time,
                   a.failed_count                                     AS failed_count,
                   CASE WHEN pg_is_in_recovery() THEN NULL
                        ELSE pg_current_wal_lsn()::text END           AS current_lsn,
                   (SELECT setting::bigint FROM pg_settings
                     WHERE name = 'wal_segment_size')                 AS segment_size
              FROM pg_stat_archiver a
            """;

    private final JdbcClient jdbc;
    private final Duration rpo;
    private final Clock clock;

    @Autowired
    public WalArchivingHealthIndicator(JdbcClient jdbc, @Value("${lms.dr.rpo:5m}") Duration rpo) {
        this(jdbc, rpo, Clock.systemUTC());
    }

    WalArchivingHealthIndicator(JdbcClient jdbc, Duration rpo, Clock clock) {
        this.jdbc = jdbc;
        this.rpo = rpo;
        this.clock = clock;
    }

    @Override
    public Health health() {
        ArchiverSnapshot snapshot = jdbc.sql(SNAPSHOT_SQL)
                .query((rs, row) -> new ArchiverSnapshot(
                        rs.getString("archive_mode"),
                        rs.getString("last_archived_wal"),
                        instant(rs.getObject("last_archived_time", OffsetDateTime.class)),
                        instant(rs.getObject("last_failed_time", OffsetDateTime.class)),
                        rs.getLong("failed_count"),
                        rs.getString("current_lsn"),
                        rs.getLong("segment_size")))
                .single();
        return assess(snapshot, clock.instant(), rpo);
    }

    private static Instant instant(OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }

    /**
     * The decision, separated from the query so it can be tested with fixed
     * inputs and a fixed clock.
     */
    static Health assess(ArchiverSnapshot s, Instant now, Duration rpo) {
        if ("off".equals(s.archiveMode())) {
            return Health.unknown()
                    .withDetail("archiveMode", "off")
                    .withDetail("detail", "WAL archiving is not enabled on this server; "
                            + "backups and point-in-time recovery are provider-managed")
                    .build();
        }

        Health.Builder builder;
        Long pendingBytes = s.pendingWalBytes();
        if (s.lastFailedTime() != null
                && (s.lastArchivedTime() == null || s.lastFailedTime().isAfter(s.lastArchivedTime()))) {
            builder = Health.down().withDetail("reason", "the last archive attempt failed");
        } else if (s.lastArchivedTime() == null) {
            // Nothing archived and nothing failed: a new server whose first
            // segment has not been switched yet. A command that cannot run
            // would have failed by now, so this is not evidence of a fault.
            builder = Health.unknown().withDetail("reason", "no WAL segment has been archived yet");
        } else if (Duration.between(s.lastArchivedTime(), now).compareTo(rpo) > 0
                && pendingBytes != null
                && pendingBytes > 0) {
            builder = Health.down()
                    .withDetail("reason", "WAL written since the last archived segment is older than the RPO");
        } else {
            builder = Health.up();
        }

        builder.withDetail("archiveMode", s.archiveMode())
                .withDetail("rpoSeconds", rpo.toSeconds())
                .withDetail("failedCount", s.failedCount());
        if (s.lastArchivedTime() != null) {
            builder.withDetail("lastArchivedAt", s.lastArchivedTime().toString())
                    .withDetail("secondsSinceLastArchive",
                            Duration.between(s.lastArchivedTime(), now).toSeconds());
        }
        if (s.lastFailedTime() != null) {
            builder.withDetail("lastFailedAt", s.lastFailedTime().toString());
        }
        if (pendingBytes != null) {
            builder.withDetail("pendingWalBytes", Math.max(0, pendingBytes));
        }
        return builder.build();
    }

    /**
     * One row of {@code pg_stat_archiver} plus what is needed to tell whether
     * WAL has been written since the last archived segment.
     *
     * @param currentLsn the current write position as {@code X/Y}, or null on
     *     a standby
     */
    record ArchiverSnapshot(
            String archiveMode,
            String lastArchivedWal,
            Instant lastArchivedTime,
            Instant lastFailedTime,
            long failedCount,
            String currentLsn,
            long segmentSize) {

        /**
         * Bytes of WAL beyond the end of the last archived segment, or null
         * when that cannot be told (a standby, a history file, nothing archived
         * yet). Zero or negative means everything written has been archived.
         */
        Long pendingWalBytes() {
            if (currentLsn == null || lastArchivedWal == null || segmentSize <= 0) {
                return null;
            }
            // A segment file name is 24 hex digits: timeline, log, segment.
            // Anything else (a .history or .partial file) cannot be placed.
            if (!lastArchivedWal.matches("[0-9A-F]{24}")) {
                return null;
            }
            long log = Long.parseLong(lastArchivedWal.substring(8, 16), 16);
            long segment = Long.parseLong(lastArchivedWal.substring(16, 24), 16);
            long archivedEnd = (log << 32) + (segment + 1) * segmentSize;
            return lsn(currentLsn) - archivedEnd;
        }

        private static long lsn(String text) {
            int slash = text.indexOf('/');
            return (Long.parseLong(text.substring(0, slash), 16) << 32) + Long.parseLong(text.substring(slash + 1), 16);
        }
    }
}
