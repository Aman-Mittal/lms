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

    public ChargeCatalogueService(ChargeCatalogueRepository entries, AuditTrail audit) {
        this.entries = entries;
        this.audit = audit;
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

    private static BusinessRuleViolationException taken(String code) {
        return new BusinessRuleViolationException("charge-code-taken",
                "A catalogue charge with code " + code + " already exists");
    }

    private static Json snapshot(ChargeCatalogueItem e) {
        return Json.object("code", e.code(), "name", e.name(), "basis", e.basis().name(),
                "defaultRate", e.defaultRate().toPlainString(), "direction", e.direction().name(),
                "active", String.valueOf(e.active()));
    }
}
