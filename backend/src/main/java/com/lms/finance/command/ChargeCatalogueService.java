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

import java.math.BigDecimal;
import java.util.UUID;

import com.lms.finance.command.domain.ChargeBasis;
import com.lms.finance.command.domain.ChargeCatalogueItem;
import com.lms.finance.command.domain.ChargeDirection;
import com.lms.shared.audit.AuditTrail;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeping the tenant's catalogue of manual charges (loading, unloading, toll,
 * halting, penalty): created, edited under {@code If-Match} and switched off,
 * never deleted, because charges raised from an entry keep its code.
 */
@Service
public class ChargeCatalogueService {

    private final ChargeCatalogueRepository entries;
    private final AuditTrail audit;
    private final JdbcClient jdbc;

    public ChargeCatalogueService(ChargeCatalogueRepository entries, AuditTrail audit, JdbcClient jdbc) {
        this.entries = entries;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    @Transactional
    @PreAuthorize("hasAuthority('CHARGE_CATALOGUE_MANAGE')")
    public UUID create(String code, String name, ChargeBasis basis, BigDecimal defaultRate,
                       ChargeDirection direction, Boolean active) {
        UUID tenantId = TenantContext.requireTenantId();
        ChargeCatalogueItem entry = ChargeCatalogueItem.create(UUID.randomUUID(), tenantId, code, name, basis,
                defaultRate, direction, active == null || active);
        if (entries.findByCode(tenantId, entry.code()).isPresent()) {
            throw taken(entry.code());
        }
        ChargeCatalogueItem saved;
        try {
            saved = entries.save(entry);
        } catch (DuplicateKeyException race) {
            throw taken(entry.code());
        }
        audit.recordAtomically("CHARGE_CATALOGUE_CREATED", "CHARGE_CATALOGUE", saved.id(), null, snapshot(saved));
        return saved.id();
    }

    /** Edits everything but the code; a stale {@code expectedVersion} is a 409. */
    @Transactional
    @PreAuthorize("hasAuthority('CHARGE_CATALOGUE_MANAGE')")
    public void update(UUID id, long expectedVersion, String name, ChargeBasis basis, BigDecimal defaultRate,
                       ChargeDirection direction, Boolean active) {
        ChargeCatalogueItem before = entries.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ChargeCatalogue", id));
        if (before.version() == null || before.version() != expectedVersion) {
            throw new OptimisticLockingFailureException("Charge " + before.code() + " changed since it was read");
        }
        ChargeCatalogueItem edited = entries.save(before.edited(name, basis, defaultRate, direction,
                active == null ? before.active() : active));
        audit.recordAtomically("CHARGE_CATALOGUE_UPDATED", "CHARGE_CATALOGUE", id, snapshot(before), snapshot(edited));
    }

    /**
     * Switches an entry off, with a reason. An inactive entry is not offered and a charge cannot be
     * raised from it ({@code charge-inactive}); charges already raised keep their copy. Refused with
     * {@code charge-in-use} while a charge raised from it still waits for approval.
     */
    @Transactional
    @PreAuthorize("hasAuthority('CHARGE_CATALOGUE_MANAGE')")
    public void deactivate(UUID id, Long expectedVersion, String reason) {
        requireReason(reason);
        ChargeCatalogueItem before = entries.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ChargeCatalogue", id));
        requireVersionIfGiven(before, expectedVersion);
        if (!before.active()) {
            throw new BusinessRuleViolationException("charge-already-inactive",
                    "Charge " + before.code() + " is already switched off");
        }
        long waiting = jdbc.sql("""
                        SELECT count(*) FROM manual_charge
                         WHERE tenant_id = :tenantId AND catalogue_id = :id AND status = 'PENDING_APPROVAL'
                        """)
                .param("tenantId", before.tenantId()).param("id", id).query(Long.class).single();
        if (waiting > 0) {
            throw new BusinessRuleViolationException("charge-in-use",
                    "Charge " + before.code() + " has " + waiting
                            + " raised charge(s) still waiting for approval; decide them first");
        }
        ChargeCatalogueItem saved = entries.save(before.withActive(false));
        audit.recordAtomically("CHARGE_CATALOGUE_DEACTIVATED", "CHARGE_CATALOGUE", id, snapshot(before),
                snapshot(saved, reason));
    }

    @Transactional
    @PreAuthorize("hasAuthority('CHARGE_CATALOGUE_MANAGE')")
    public void reactivate(UUID id, Long expectedVersion, String reason) {
        requireReason(reason);
        ChargeCatalogueItem before = entries.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ChargeCatalogue", id));
        requireVersionIfGiven(before, expectedVersion);
        if (before.active()) {
            throw new BusinessRuleViolationException("charge-not-inactive",
                    "Charge " + before.code() + " is on, not switched off");
        }
        ChargeCatalogueItem saved = entries.save(before.withActive(true));
        audit.recordAtomically("CHARGE_CATALOGUE_REACTIVATED", "CHARGE_CATALOGUE", id, snapshot(before),
                snapshot(saved, reason));
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required");
        }
    }

    private static void requireVersionIfGiven(ChargeCatalogueItem entry, Long expected) {
        if (expected != null && (entry.version() == null || !entry.version().equals(expected))) {
            throw new OptimisticLockingFailureException("Charge " + entry.code() + " changed since it was read");
        }
    }

    private static BusinessRuleViolationException taken(String code) {
        return new BusinessRuleViolationException("charge-code-taken",
                "A catalogue charge with code " + code + " already exists");
    }

    private static Json snapshot(ChargeCatalogueItem e, String reason) {
        return Json.object("code", e.code(), "name", e.name(), "basis", e.basis().name(),
                "defaultRate", e.defaultRate().toPlainString(), "direction", e.direction().name(),
                "active", String.valueOf(e.active()), "reason", reason);
    }

    private static Json snapshot(ChargeCatalogueItem e) {
        return Json.object("code", e.code(), "name", e.name(), "basis", e.basis().name(),
                "defaultRate", e.defaultRate().toPlainString(), "direction", e.direction().name(),
                "active", String.valueOf(e.active()));
    }
}
