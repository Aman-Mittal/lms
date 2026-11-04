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
package com.lms.identity.command;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.lms.identity.command.domain.NumberSeries;
import com.lms.identity.command.domain.SeriesFormat;
import com.lms.identity.numbering.DocumentNumbers;
import com.lms.identity.numbering.DocumentType;
import com.lms.shared.audit.AuditTrail;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Draws and releases numbers: the {@link DocumentNumbers} port, and the
 * statements behind the administration desk's issue and release.
 *
 * <p>Everything here joins the caller's transaction ({@code MANDATORY}). A
 * draw is one statement on one row -- the series' counter for the financial
 * year, or the lowest unused release in its pool -- and the row lock it takes
 * is held until the caller commits. Two concurrent draws from one series
 * therefore queue on the row rather than read the same value, and a caller
 * that rolls back returns the value with it, so numbers stay dense. No second
 * connection is opened (ADR 0006).
 */
@Service
public class NumberIssuer implements DocumentNumbers {

    /** The audit resource type of a series and everything done to its numbers. */
    public static final String AUDIT_TYPE = "NUMBER_SERIES";

    /** What a release came to. */
    public enum ReleaseOutcome {
        RELEASED, NOT_IN_SERIES, NOT_ISSUED, ALREADY_RELEASED
    }

    private final NumberSeriesRepository series;
    private final JdbcClient jdbc;
    private final AuditTrail audit;

    public NumberIssuer(NumberSeriesRepository series, JdbcClient jdbc, AuditTrail audit) {
        this.series = series;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<String> next(DocumentType type, UUID orgUnitId) {
        return next(type, orgUnitId, Instant.now());
    }

    /** {@link #next(DocumentType, UUID)} as of {@code at}, which fixes the financial year. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<String> next(DocumentType type, UUID orgUnitId, Instant at) {
        UUID tenantId = TenantContext.requireTenantId();
        return series.findEffective(tenantId, type.name(), orgUnitId)
                .map(effective -> issue(effective, at));
    }

    /** The next number of one series as of {@code at}: from its pool when it reuses, else its counter. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String issue(NumberSeries from, Instant at) {
        SeriesFormat format = from.format();
        int financialYear = format.financialYear(at);
        long value = from.reuseReleased()
                ? takeFromPool(from, financialYear).orElseGet(() -> advance(from, financialYear))
                : advance(from, financialYear);
        return format.format(financialYear, value);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean release(DocumentType type, String number, String reason) {
        UUID tenantId = TenantContext.requireTenantId();
        for (NumberSeries candidate : series.findByType(tenantId, type.name())) {
            if (candidate.format().parse(number).isPresent()) {
                return release(candidate, number, reason) == ReleaseOutcome.RELEASED;
            }
        }
        return false;
    }

    /**
     * Records {@code number} as given back to {@code from}'s pool, and audits
     * it. Nothing is written unless the outcome is {@code RELEASED}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ReleaseOutcome release(NumberSeries from, String number, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A release needs a reason");
        }
        Optional<SeriesFormat.Parsed> parsed = from.format().parse(number);
        if (parsed.isEmpty()) {
            return ReleaseOutcome.NOT_IN_SERIES;
        }
        int financialYear = parsed.get().financialYear();
        long value = parsed.get().value();
        if (value > lastValue(from, financialYear)) {
            return ReleaseOutcome.NOT_ISSUED;
        }
        // A number still in the pool cannot be released again; one taken back
        // out of it (reused) can, and goes back in.
        Optional<UUID> recorded = jdbc.sql("""
                        INSERT INTO number_series_release
                            (id, tenant_id, series_id, financial_year, counter_value, number, reason,
                             released_by)
                        VALUES (:id, :tenantId, :seriesId, :year, :value, :number, :reason, :actor)
                        ON CONFLICT (tenant_id, series_id, financial_year, counter_value)
                        DO UPDATE SET reused_at = NULL, reason = EXCLUDED.reason,
                                      released_by = EXCLUDED.released_by, released_at = now()
                         WHERE number_series_release.reused_at IS NOT NULL
                        RETURNING id
                        """)
                .param("id", UUID.randomUUID())
                .param("tenantId", from.tenantId())
                .param("seriesId", from.id())
                .param("year", financialYear)
                .param("value", value)
                .param("number", number)
                .param("reason", reason.trim())
                .param("actor", currentActorId())
                .query(UUID.class)
                .optional();
        if (recorded.isEmpty()) {
            return ReleaseOutcome.ALREADY_RELEASED;
        }
        audit.recordAtomically("NUMBER_RELEASED", AUDIT_TYPE, from.id(), null,
                Json.object("number", number, "reason", reason.trim(),
                        "reusable", String.valueOf(from.reuseReleased())));
        return ReleaseOutcome.RELEASED;
    }

    /** {@link #release(NumberSeries, String, String)}, refusing anything but a release with its code. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseOrRefuse(NumberSeries from, String number, String reason) {
        switch (release(from, number, reason)) {
            case RELEASED -> {
            }
            case NOT_IN_SERIES -> throw new BusinessRuleViolationException("number-not-in-series",
                    number + " is not a number series " + from.code() + " produces");
            case NOT_ISSUED -> throw new BusinessRuleViolationException("number-not-issued",
                    number + " has not been issued by series " + from.code());
            case ALREADY_RELEASED -> throw new BusinessRuleViolationException("number-already-released",
                    number + " has already been released and not issued again");
        }
    }

    // ------------------------------------------------------------- internals

    /** Takes the lowest unused release of the year; a release another draw holds is skipped, not waited on. */
    private Optional<Long> takeFromPool(NumberSeries from, int financialYear) {
        return jdbc.sql("""
                        UPDATE number_series_release
                           SET reused_at = now()
                         WHERE id = (SELECT id FROM number_series_release
                                      WHERE tenant_id = :tenantId AND series_id = :seriesId
                                        AND financial_year = :year AND reused_at IS NULL
                                      ORDER BY counter_value
                                      LIMIT 1
                                      FOR UPDATE SKIP LOCKED)
                        RETURNING counter_value
                        """)
                .param("tenantId", from.tenantId())
                .param("seriesId", from.id())
                .param("year", financialYear)
                .query(Long.class)
                .optional();
    }

    private long advance(NumberSeries from, int financialYear) {
        return jdbc.sql("""
                        INSERT INTO number_series_counter (tenant_id, series_id, financial_year, last_value)
                        VALUES (:tenantId, :seriesId, :year, 1)
                        ON CONFLICT (tenant_id, series_id, financial_year)
                        DO UPDATE SET last_value = number_series_counter.last_value + 1,
                                      updated_at = now()
                        RETURNING last_value
                        """)
                .param("tenantId", from.tenantId())
                .param("seriesId", from.id())
                .param("year", financialYear)
                .query(Long.class)
                .single();
    }

    private long lastValue(NumberSeries from, int financialYear) {
        return jdbc.sql("""
                        SELECT last_value FROM number_series_counter
                         WHERE tenant_id = :tenantId AND series_id = :seriesId AND financial_year = :year
                        """)
                .param("tenantId", from.tenantId())
                .param("seriesId", from.id())
                .param("year", financialYear)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    private static UUID currentActorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
