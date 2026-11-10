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
import java.util.UUID;

import com.lms.identity.command.domain.NumberSeries;
import com.lms.identity.command.domain.SeriesFormat;
import com.lms.identity.numbering.DocumentType;
import com.lms.shared.audit.AuditTrail;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administers number series: create, revise, activate, deactivate, delete,
 * and issue or release a number by hand.
 *
 * <p>One right, {@code NUMBER_SERIES_MANAGE}, for all of it. Every change is
 * audited in its own transaction with what it replaced.
 *
 * <p>The rules that are not shape checks, each a 409 with its own code:
 * <ul>
 *   <li>{@code number-series-code-taken} -- codes are unique in the tenant;</li>
 *   <li>{@code number-series-prefix-taken} -- two series of one type with one
 *       prefix could issue the same number, so prefixes are unique per type;</li>
 *   <li>{@code number-series-scope-taken} -- one active series per type and
 *       scope, so the most specific series is always one series;</li>
 *   <li>{@code number-series-in-use} -- once a number has been issued, the
 *       format is fixed (earlier numbers must stay verifiable) and the series
 *       can be deactivated but not deleted;</li>
 *   <li>{@code number-series-inactive} -- an inactive series issues nothing.</li>
 * </ul>
 */
@Service
public class NumberSeriesService {

    private final NumberSeriesRepository series;
    private final NumberIssuer issuer;
    private final AuditTrail audit;

    public NumberSeriesService(NumberSeriesRepository series, NumberIssuer issuer, AuditTrail audit) {
        this.series = series;
        this.issuer = issuer;
        this.audit = audit;
    }

    /**
     * @param orgUnitId the unit whose subtree the series numbers; null for the whole tenant
     */
    @Transactional
    @PreAuthorize("hasAuthority('NUMBER_SERIES_MANAGE')")
    public UUID create(String code, DocumentType type, UUID orgUnitId, SeriesFormat format,
                       boolean reuseReleased, boolean active, String description) {
        UUID tenantId = TenantContext.requireTenantId();
        // The foreign key is checked as the table owner and would accept
        // another tenant's unit; this lookup runs under row-level security.
        if (orgUnitId != null && series.orgUnitPath(orgUnitId).isEmpty()) {
            throw new ResourceNotFoundException("OrgUnit", orgUnitId);
        }
        NumberSeries draft = NumberSeries.create(UUID.randomUUID(), tenantId, code, type, orgUnitId,
                format, reuseReleased, active, description);
        series.findByCode(tenantId, draft.code()).ifPresent(existing -> {
            throw new BusinessRuleViolationException("number-series-code-taken",
                    "A number series with code " + draft.code() + " already exists");
        });
        requirePrefixFree(draft, format.prefix());
        if (active) {
            requireScopeFree(draft);
        }
        NumberSeries created = series.save(draft);
        audit.recordAtomically("NUMBER_SERIES_CREATED", NumberIssuer.AUDIT_TYPE, created.id(), null,
                snapshot(created));
        return created.id();
    }

    /**
     * Changes the format, the reuse setting and the description. The code, the
     * type and the scope never change; the format only until the first number
     * is issued.
     */
    @Transactional
    @PreAuthorize("hasAuthority('NUMBER_SERIES_MANAGE')")
    public void revise(UUID seriesId, SeriesFormat format, boolean reuseReleased, String description) {
        NumberSeries existing = require(seriesId);
        if (existing.formatDiffers(format)) {
            if (series.hasIssued(existing.tenantId(), existing.id())) {
                throw new BusinessRuleViolationException("number-series-in-use",
                        "Series " + existing.code() + " has issued numbers, so its format is fixed;"
                                + " create a new series for a new format");
            }
            if (!existing.prefix().equals(format.prefix())) {
                requirePrefixFree(existing, format.prefix());
            }
        }
        NumberSeries revised = series.save(existing.revise(format, reuseReleased, description));
        audit.recordAtomically("NUMBER_SERIES_REVISED", NumberIssuer.AUDIT_TYPE, revised.id(),
                snapshot(existing), snapshot(revised));
    }

    @Transactional
    @PreAuthorize("hasAuthority('NUMBER_SERIES_MANAGE')")
    public void activate(UUID seriesId) {
        NumberSeries existing = require(seriesId);
        if (existing.active()) {
            return;
        }
        requireScopeFree(existing);
        NumberSeries activated = series.save(existing.withActive(true));
        audit.recordAtomically("NUMBER_SERIES_ACTIVATED", NumberIssuer.AUDIT_TYPE, activated.id(),
                snapshot(existing), snapshot(activated));
    }

    /** Stops the series numbering anything; the next most specific series, if any, takes over. */
    @Transactional
    @PreAuthorize("hasAuthority('NUMBER_SERIES_MANAGE')")
    public void deactivate(UUID seriesId) {
        NumberSeries existing = require(seriesId);
        if (!existing.active()) {
            return;
        }
        NumberSeries deactivated = series.save(existing.withActive(false));
        audit.recordAtomically("NUMBER_SERIES_DEACTIVATED", NumberIssuer.AUDIT_TYPE, deactivated.id(),
                snapshot(existing), snapshot(deactivated));
    }

    /** Removes a series that never issued a number; one that did can only be deactivated. */
    @Transactional
    @PreAuthorize("hasAuthority('NUMBER_SERIES_MANAGE')")
    public void delete(UUID seriesId) {
        NumberSeries existing = require(seriesId);
        if (series.hasIssued(existing.tenantId(), existing.id())) {
            throw new BusinessRuleViolationException("number-series-in-use",
                    "Series " + existing.code() + " has issued numbers; deactivate it instead");
        }
        series.delete(existing);
        audit.recordAtomically("NUMBER_SERIES_DELETED", NumberIssuer.AUDIT_TYPE, existing.id(),
                snapshot(existing), null);
    }

    /**
     * Issues the next number of the series for a document made outside the
     * platform -- a pre-printed book, a manual pass -- and audits it.
     */
    @Transactional
    @PreAuthorize("hasAuthority('NUMBER_SERIES_MANAGE')")
    public String issue(UUID seriesId) {
        NumberSeries from = require(seriesId);
        if (!from.active()) {
            throw new BusinessRuleViolationException("number-series-inactive",
                    "Series " + from.code() + " is inactive and issues no numbers");
        }
        String number = issuer.issue(from, Instant.now());
        audit.recordAtomically("NUMBER_ISSUED", NumberIssuer.AUDIT_TYPE, from.id(), null,
                Json.object("number", number));
        return number;
    }

    /** Gives a number back to the series, with a reason; refused with a code unless it is issued and not already released. */
    @Transactional
    @PreAuthorize("hasAuthority('NUMBER_SERIES_MANAGE')")
    public void release(UUID seriesId, String number, String reason) {
        issuer.releaseOrRefuse(require(seriesId), number == null ? null : number.trim(), reason);
    }

    // ------------------------------------------------------------- internals

    private NumberSeries require(UUID seriesId) {
        return series.findById(seriesId)
                .orElseThrow(() -> new ResourceNotFoundException("NumberSeries", seriesId));
    }

    private void requirePrefixFree(NumberSeries owner, String prefix) {
        boolean taken = series.findByType(owner.tenantId(), owner.documentType().name()).stream()
                .anyMatch(other -> !other.id().equals(owner.id()) && other.prefix().equals(prefix));
        if (taken) {
            throw new BusinessRuleViolationException("number-series-prefix-taken",
                    "Another " + owner.documentType() + " series already uses prefix " + prefix);
        }
    }

    private void requireScopeFree(NumberSeries candidate) {
        if (series.scopeTaken(candidate.tenantId(), candidate.documentType().name(),
                candidate.orgUnitId(), candidate.id())) {
            throw new BusinessRuleViolationException("number-series-scope-taken",
                    "Another active " + candidate.documentType() + " series already numbers "
                            + (candidate.orgUnitId() == null ? "the whole tenant" : "that unit")
                            + "; deactivate it first");
        }
    }

    private static Json snapshot(NumberSeries s) {
        return Json.object("code", s.code(), "documentType", s.documentType().name(),
                "orgUnitId", s.orgUnitId() == null ? null : s.orgUnitId().toString(),
                "prefix", s.prefix(), "yearCode", s.yearCode().name(), "separator", s.separator(),
                "counterLength", String.valueOf(s.counterLength()),
                "yearStartMonth", String.valueOf(s.yearStartMonth()), "timeZone", s.timeZone(),
                "reuseReleased", String.valueOf(s.reuseReleased()), "active", String.valueOf(s.active()),
                "sample", s.format().format(s.format().financialYear(Instant.now()), 1));
    }
}
