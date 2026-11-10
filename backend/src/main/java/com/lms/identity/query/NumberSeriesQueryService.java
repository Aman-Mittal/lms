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
package com.lms.identity.query;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.lms.identity.command.domain.SeriesFormat;
import com.lms.identity.numbering.DocumentType;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.query.Slice;
import com.lms.shared.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side for number series: the list, one series, what it would issue
 * next, what a proposed format looks like, whether a number is genuine, and
 * the log of numbers given back.
 *
 * <p>Guarded by {@code NUMBER_SERIES_MANAGE}, like the writes. Nothing here
 * advances a counter: a preview is a prediction, and a concurrent document
 * may take the number first.
 */
@Service
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('NUMBER_SERIES_MANAGE')")
public class NumberSeriesQueryService {

    private static final String SELECT = """
            SELECT s.id, s.code, s.document_type, s.org_unit_id, u.name AS org_unit_name,
                   u.path AS org_unit_path, s.prefix, s.year_code, s.separator, s.counter_length,
                   s.year_start_month, s.time_zone, s.reuse_released, s.active, s.description,
                   s.created_at, s.updated_at
              FROM number_series s
              LEFT JOIN org_unit u ON u.id = s.org_unit_id
            """;

    private final JdbcClient jdbc;

    public NumberSeriesQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A page of series in code order, optionally of one document type. Keyset
     * on the code, unique per tenant: {@code number_series_type_code_idx} when
     * narrowed, {@code number_series_code_unique} when not.
     */
    public Slice<NumberSeriesView> list(DocumentType type, String cursor, Integer limit) {
        int pageSize = Slice.clampLimit(limit);
        UUID tenantId = TenantContext.requireTenantId();
        List<Row> rows = jdbc.sql(SELECT + """
                         WHERE s.tenant_id = :tenantId
                           AND (CAST(:type AS text) IS NULL OR s.document_type = CAST(:type AS text))
                           AND (CAST(:cursor AS text) IS NULL OR s.code > CAST(:cursor AS text))
                         ORDER BY s.code
                         LIMIT :limit
                        """)
                .param("tenantId", tenantId)
                .param("type", type == null ? null : type.name())
                .param("cursor", cursor)
                .param("limit", pageSize + 1)
                .query(NumberSeriesQueryService::toRow)
                .list();
        return Slice.of(withCounters(tenantId, rows), pageSize, NumberSeriesView::code);
    }

    public Optional<NumberSeriesView> findById(UUID id) {
        UUID tenantId = TenantContext.requireTenantId();
        return jdbc.sql(SELECT + " WHERE s.tenant_id = :tenantId AND s.id = :id")
                .param("tenantId", tenantId)
                .param("id", id)
                .query(NumberSeriesQueryService::toRow)
                .optional()
                .map(row -> withCounters(tenantId, List.of(row)).getFirst());
    }

    /**
     * The series that would number a document of {@code type} at
     * {@code orgUnitId}: the active one on the deepest unit at or above it,
     * else the tenant-wide one. 404 when none would.
     */
    public NumberSeriesView effective(DocumentType type, UUID orgUnitId) {
        UUID tenantId = TenantContext.requireTenantId();
        Optional<UUID> id = jdbc.sql("""
                        SELECT s.id FROM number_series s
                          LEFT JOIN org_unit u ON u.id = s.org_unit_id
                         WHERE s.tenant_id = :tenantId AND s.document_type = :type AND s.active
                           AND (s.org_unit_id IS NULL
                                OR starts_with((SELECT t.path FROM org_unit t
                                                 WHERE t.id = CAST(:unitId AS uuid)), u.path))
                         ORDER BY length(u.path) DESC NULLS LAST
                         LIMIT 1
                        """)
                .param("tenantId", tenantId)
                .param("type", type.name())
                .param("unitId", orgUnitId)
                .query(UUID.class)
                .optional();
        return id.flatMap(this::findById).orElseThrow(() -> new ResourceNotFoundException(
                "No active " + type + " series numbers documents there"));
    }

    /** What the series would issue next, as of now. */
    public NumberPreviewView preview(UUID seriesId) {
        UUID tenantId = TenantContext.requireTenantId();
        Row row = jdbc.sql(SELECT + " WHERE s.tenant_id = :tenantId AND s.id = :id")
                .param("tenantId", tenantId)
                .param("id", seriesId)
                .query(NumberSeriesQueryService::toRow)
                .optional()
                .orElseThrow(() -> new ResourceNotFoundException("NumberSeries", seriesId));
        SeriesFormat format = row.format();
        int financialYear = format.financialYear(Instant.now());
        if (row.reuseReleased()) {
            Optional<Long> pooled = jdbc.sql("""
                            SELECT counter_value FROM number_series_release
                             WHERE tenant_id = :tenantId AND series_id = :id
                               AND financial_year = :year AND reused_at IS NULL
                             ORDER BY counter_value
                             LIMIT 1
                            """)
                    .param("tenantId", tenantId)
                    .param("id", seriesId)
                    .param("year", financialYear)
                    .query(Long.class)
                    .optional();
            if (pooled.isPresent()) {
                return new NumberPreviewView(seriesId, format.format(financialYear, pooled.get()),
                        financialYear, true);
            }
        }
        long last = lastValue(tenantId, seriesId, financialYear);
        return new NumberPreviewView(seriesId, format.format(financialYear, last + 1), financialYear, false);
    }

    /** Shows a proposed format; refuses one that could not be saved, as bad input. */
    public FormatPreviewView formatPreview(String prefix, SeriesFormat.YearCode yearCode, String separator,
                                           Integer counterLength, Integer yearStartMonth, String timeZone) {
        SeriesFormat format = new SeriesFormat(prefix, yearCode == null ? SeriesFormat.YearCode.NONE : yearCode,
                separator == null ? "-" : separator, counterLength == null ? 5 : counterLength,
                yearStartMonth == null ? 4 : yearStartMonth, SeriesFormat.zoneOf(timeZone));
        int financialYear = format.financialYear(Instant.now());
        int nextYear = format.yearCode() == SeriesFormat.YearCode.NONE ? financialYear : financialYear + 1;
        return new FormatPreviewView(format.format(financialYear, 1), format.format(nextYear, 1),
                financialYear);
    }

    /**
     * Whether {@code number} belongs to one of the tenant's series of
     * {@code type}, active or not, and whether it has been issued.
     */
    public NumberVerificationView verify(DocumentType type, String number) {
        UUID tenantId = TenantContext.requireTenantId();
        String candidate = number == null ? "" : number.trim();
        List<Row> series = jdbc.sql(SELECT + """
                         WHERE s.tenant_id = :tenantId AND s.document_type = :type
                         ORDER BY length(s.prefix) DESC, s.prefix
                        """)
                .param("tenantId", tenantId)
                .param("type", type.name())
                .query(NumberSeriesQueryService::toRow)
                .list();
        for (Row row : series) {
            Optional<SeriesFormat.Parsed> parsed = row.format().parse(candidate);
            if (parsed.isEmpty()) {
                continue;
            }
            int year = parsed.get().financialYear();
            long value = parsed.get().value();
            String status;
            if (value > lastValue(tenantId, row.id(), year)) {
                status = "NOT_ISSUED";
            } else {
                boolean released = jdbc.sql("""
                                SELECT EXISTS (SELECT 1 FROM number_series_release
                                                WHERE tenant_id = :tenantId AND series_id = :id
                                                  AND financial_year = :year AND counter_value = :value
                                                  AND reused_at IS NULL)
                                """)
                        .param("tenantId", tenantId)
                        .param("id", row.id())
                        .param("year", year)
                        .param("value", value)
                        .query(Boolean.class)
                        .single();
                status = released ? "RELEASED" : "ISSUED";
            }
            return new NumberVerificationView(candidate, type.name(), true, row.id(), row.code(), year,
                    value, status);
        }
        return new NumberVerificationView(candidate, type.name(), false, null, null, null, null, null);
    }

    /**
     * The numbers given back to a series, newest first. Keyset on
     * (released_at, id), served by {@code number_series_release_log_idx}; the
     * cursor is {@code <epoch micros>_<id>}.
     */
    public Slice<NumberReleaseView> releases(UUID seriesId, String cursor, Integer limit) {
        UUID tenantId = TenantContext.requireTenantId();
        if (findById(seriesId).isEmpty()) {
            throw new ResourceNotFoundException("NumberSeries", seriesId);
        }
        int pageSize = Slice.clampLimit(limit);
        Instant afterAt = null;
        UUID afterId = null;
        if (cursor != null && !cursor.isBlank()) {
            String[] parts = cursor.split("_", 2);
            try {
                long micros = Long.parseLong(parts[0]);
                afterAt = Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L),
                        Math.floorMod(micros, 1_000_000L) * 1_000L);
                afterId = UUID.fromString(parts[1]);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Malformed cursor", e);
            }
        }
        List<NumberReleaseView> rows = jdbc.sql("""
                        SELECT id, number, financial_year, reason, released_by, released_at, reused_at
                          FROM number_series_release
                         WHERE tenant_id = :tenantId AND series_id = :seriesId
                           AND (CAST(:afterAt AS timestamptz) IS NULL
                                OR (released_at, id) < (CAST(:afterAt AS timestamptz), CAST(:afterId AS uuid)))
                         ORDER BY released_at DESC, id DESC
                         LIMIT :limit
                        """)
                .param("tenantId", tenantId)
                .param("seriesId", seriesId)
                .param("afterAt", afterAt == null ? null : afterAt.atOffset(ZoneOffset.UTC))
                .param("afterId", afterId)
                .param("limit", pageSize + 1)
                .query((rs, n) -> new NumberReleaseView(
                        rs.getObject("id", UUID.class),
                        rs.getString("number"),
                        rs.getInt("financial_year"),
                        rs.getString("reason"),
                        rs.getObject("released_by", UUID.class),
                        rs.getTimestamp("released_at").toInstant(),
                        instantOrNull(rs.getTimestamp("reused_at"))))
                .list();
        return Slice.of(rows, pageSize, r -> {
            Instant at = r.releasedAt();
            long micros = Math.addExact(Math.multiplyExact(at.getEpochSecond(), 1_000_000L), at.getNano() / 1_000L);
            return micros + "_" + r.id();
        });
    }

    /**
     * The units a series can be scoped to: the caller's own and those beneath
     * it, in path order. Keyset on the path, unique per tenant and served by
     * {@code org_unit_path_unique}.
     */
    public Slice<NumberSeriesScopeView> scopes(String cursor, Integer limit) {
        int pageSize = Slice.clampLimit(limit);
        List<NumberSeriesScopeView> rows = jdbc.sql("""
                        SELECT id, name, path, unit_type FROM org_unit
                         WHERE tenant_id = :tenantId
                           AND starts_with(path, :root)
                           AND (CAST(:cursor AS text) IS NULL OR path > CAST(:cursor AS text))
                         ORDER BY path
                         LIMIT :limit
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("root", TenantContext.requireOrgPath())
                .param("cursor", cursor)
                .param("limit", pageSize + 1)
                .query((rs, n) -> new NumberSeriesScopeView(rs.getObject("id", UUID.class),
                        rs.getString("name"), rs.getString("path"), rs.getString("unit_type")))
                .list();
        return Slice.of(rows, pageSize, NumberSeriesScopeView::path);
    }

    // ------------------------------------------------------------- internals

    /** A series row before its counters are read. */
    private record Row(UUID id, String code, String documentType, UUID orgUnitId, String orgUnitName,
                       String orgUnitPath, String prefix, String yearCode, String separator,
                       int counterLength, int yearStartMonth, String timeZone, boolean reuseReleased,
                       boolean active, String description, Instant createdAt, Instant updatedAt) {

        SeriesFormat format() {
            return new SeriesFormat(prefix, SeriesFormat.YearCode.valueOf(yearCode), separator,
                    counterLength, yearStartMonth, SeriesFormat.zoneOf(timeZone));
        }
    }

    private static Row toRow(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("document_type"),
                rs.getObject("org_unit_id", UUID.class), rs.getString("org_unit_name"),
                rs.getString("org_unit_path"), rs.getString("prefix"), rs.getString("year_code"),
                rs.getString("separator"), rs.getInt("counter_length"), rs.getInt("year_start_month"),
                rs.getString("time_zone"), rs.getBoolean("reuse_released"), rs.getBoolean("active"),
                rs.getString("description"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    /**
     * Adds each series' current-year counter and pool size, read for the
     * whole page in two statements rather than two per series.
     */
    private List<NumberSeriesView> withCounters(UUID tenantId, List<Row> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = rows.stream().map(Row::id).toList();
        Map<String, Long> counters = new HashMap<>();
        jdbc.sql("""
                        SELECT series_id, financial_year, last_value FROM number_series_counter
                         WHERE tenant_id = :tenantId AND series_id IN (:ids)
                        """)
                .param("tenantId", tenantId)
                .param("ids", ids)
                .query((rs, n) -> counters.put(rs.getObject("series_id", UUID.class) + "/"
                        + rs.getInt("financial_year"), rs.getLong("last_value")))
                .list();
        Map<String, Long> pooled = new HashMap<>();
        jdbc.sql("""
                        SELECT series_id, financial_year, count(*) AS pooled FROM number_series_release
                         WHERE tenant_id = :tenantId AND series_id IN (:ids) AND reused_at IS NULL
                         GROUP BY series_id, financial_year
                        """)
                .param("tenantId", tenantId)
                .param("ids", ids)
                .query((rs, n) -> pooled.put(rs.getObject("series_id", UUID.class) + "/"
                        + rs.getInt("financial_year"), rs.getLong("pooled")))
                .list();
        Instant now = Instant.now();
        return rows.stream().map(row -> {
            SeriesFormat format = row.format();
            int year = format.financialYear(now);
            String key = row.id() + "/" + year;
            long issued = counters.getOrDefault(key, 0L);
            return new NumberSeriesView(row.id(), row.code(), row.documentType(), row.orgUnitId(),
                    row.orgUnitName(), row.orgUnitPath(), row.prefix(), row.yearCode(), row.separator(),
                    row.counterLength(), row.yearStartMonth(), row.timeZone(), row.reuseReleased(),
                    row.active(), row.description(), year, issued,
                    issued == 0 ? null : format.format(year, issued), pooled.getOrDefault(key, 0L),
                    format.format(year, 1), row.createdAt(), row.updatedAt());
        }).toList();
    }

    private long lastValue(UUID tenantId, UUID seriesId, int financialYear) {
        return jdbc.sql("""
                        SELECT last_value FROM number_series_counter
                         WHERE tenant_id = :tenantId AND series_id = :id AND financial_year = :year
                        """)
                .param("tenantId", tenantId)
                .param("id", seriesId)
                .param("year", financialYear)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    private static Instant instantOrNull(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
