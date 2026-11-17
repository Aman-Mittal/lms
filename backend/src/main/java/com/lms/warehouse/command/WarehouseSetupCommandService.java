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
package com.lms.warehouse.command;

import java.time.Instant;
import java.util.UUID;

import com.lms.shared.audit.AuditTrail;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import com.lms.warehouse.command.domain.Bin;
import com.lms.warehouse.command.domain.Sku;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The warehouse's own master data: bins and the SKUs stored in them. */
@Service
public class WarehouseSetupCommandService {

    private final BinRepository bins;
    private final SkuRepository skus;
    private final Warehouses warehouses;
    private final JdbcClient jdbc;
    private final AuditTrail audit;

    public WarehouseSetupCommandService(BinRepository bins, SkuRepository skus,
                                        Warehouses warehouses, JdbcClient jdbc, AuditTrail audit) {
        this.bins = bins;
        this.skus = skus;
        this.warehouses = warehouses;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional
    @PreAuthorize("hasAuthority('WAREHOUSE_SETUP')"
            + " and @abac.permits('WAREHOUSE_SETUP', 'TERMINAL', #warehouseId)")
    public UUID createBin(UUID warehouseId, String code, int capacityUnits) {
        warehouses.requireWarehouse(warehouseId);
        Bin bin = Bin.create(TenantContext.requireTenantId(), warehouseId, code, capacityUnits,
                Instant.now());
        if (bins.existsByWarehouseIdAndCode(warehouseId, bin.code())) {
            throw new BusinessRuleViolationException("bin-code-taken",
                    "This warehouse already has a bin " + bin.code());
        }
        return bins.save(bin).id();
    }

    @Transactional
    @PreAuthorize("hasAuthority('WAREHOUSE_SETUP')"
            + " and @abac.permits('WAREHOUSE_SETUP', 'BUSINESS_PARTNER', #ownerPartnerId)")
    public UUID createSku(String code, String description, UUID ownerPartnerId, String uom) {
        Sku sku = Sku.create(TenantContext.requireTenantId(), code, description, ownerPartnerId,
                uom, Instant.now());
        if (skus.existsByCode(sku.code())) {
            throw new BusinessRuleViolationException("sku-code-taken",
                    "There is already a SKU " + sku.code());
        }
        return skus.save(sku).id();
    }

    // ------------------------------------------------------ switching on and off

    /**
     * Switches a bin off, with a reason. An inactive bin takes no putaway, adjustment or release
     * into it ({@code bin-inactive}); refused with {@code bin-not-empty} while it holds stock.
     * {@code expectedVersion} is optional; a console sends it.
     */
    @Transactional
    @PreAuthorize("hasAuthority('WAREHOUSE_SETUP') and @abac.permits('WAREHOUSE_SETUP', 'WAREHOUSE_BIN', #binId)")
    public void deactivateBin(UUID binId, Long expectedVersion, String reason) {
        requireReason(reason);
        Bin before = bin(binId, expectedVersion);
        if (!before.active()) {
            throw new BusinessRuleViolationException("bin-already-inactive",
                    "Bin " + before.code() + " is already switched off");
        }
        long onHand = jdbc.sql("""
                        SELECT coalesce(sum(on_hand), 0) FROM stock_balance
                         WHERE tenant_id = :tenantId AND location_type = 'BIN' AND location_id = :binId
                        """)
                .param("tenantId", before.tenantId()).param("binId", binId).query(Long.class).single();
        if (onHand > 0) {
            throw new BusinessRuleViolationException("bin-not-empty",
                    "Bin " + before.code() + " still holds " + onHand + " unit(s); move or adjust them out first");
        }
        Bin saved = bins.save(before.withActive(false));
        audit.recordAtomically("BIN_DEACTIVATED", "WAREHOUSE_BIN", binId, snapshot(before, null),
                snapshot(saved, reason));
    }

    @Transactional
    @PreAuthorize("hasAuthority('WAREHOUSE_SETUP') and @abac.permits('WAREHOUSE_SETUP', 'WAREHOUSE_BIN', #binId)")
    public void reactivateBin(UUID binId, Long expectedVersion, String reason) {
        requireReason(reason);
        Bin before = bin(binId, expectedVersion);
        if (before.active()) {
            throw new BusinessRuleViolationException("bin-not-inactive",
                    "Bin " + before.code() + " is on, not switched off");
        }
        Bin saved = bins.save(before.withActive(true));
        audit.recordAtomically("BIN_REACTIVATED", "WAREHOUSE_BIN", binId, snapshot(before, null),
                snapshot(saved, reason));
    }

    /**
     * Switches a SKU off, with a reason. An inactive SKU cannot be expected on an ASN, ordered,
     * received or put away ({@code sku-inactive}); refused with {@code sku-in-use} while an ASN
     * still expects it, an outbound order not yet dispatched asks for it, or any stock of it is held.
     */
    @Transactional
    @PreAuthorize("hasAuthority('WAREHOUSE_SETUP')")
    public void deactivateSku(UUID skuId, Long expectedVersion, String reason) {
        requireReason(reason);
        Sku before = sku(skuId, expectedVersion);
        if (!before.active()) {
            throw new BusinessRuleViolationException("sku-already-inactive",
                    "SKU " + before.code() + " is already switched off");
        }
        long asns = count("""
                SELECT count(DISTINCT l.asn_id) FROM asn_line l JOIN asn a ON a.id = l.asn_id
                 WHERE l.sku_id = :skuId AND a.status = 'EXPECTED'
                """, skuId);
        long orders = count("""
                SELECT count(DISTINCT l.order_id) FROM outbound_line l JOIN outbound_order o ON o.id = l.order_id
                 WHERE l.sku_id = :skuId AND o.status <> 'DISPATCHED'
                """, skuId);
        long stock = count("SELECT coalesce(sum(on_hand), 0) FROM stock_balance WHERE sku_id = :skuId", skuId);
        if (asns > 0 || orders > 0 || stock > 0) {
            throw new BusinessRuleViolationException("sku-in-use",
                    "SKU " + before.code() + " is still on " + asns + " open ASN(s) and " + orders
                            + " open order(s), with " + stock + " unit(s) in stock; finish or clear them first");
        }
        Sku saved = skus.save(before.withActive(false));
        audit.recordAtomically("SKU_DEACTIVATED", "SKU", skuId, snapshot(before, null), snapshot(saved, reason));
    }

    @Transactional
    @PreAuthorize("hasAuthority('WAREHOUSE_SETUP')")
    public void reactivateSku(UUID skuId, Long expectedVersion, String reason) {
        requireReason(reason);
        Sku before = sku(skuId, expectedVersion);
        if (before.active()) {
            throw new BusinessRuleViolationException("sku-not-inactive",
                    "SKU " + before.code() + " is on, not switched off");
        }
        Sku saved = skus.save(before.withActive(true));
        audit.recordAtomically("SKU_REACTIVATED", "SKU", skuId, snapshot(before, null), snapshot(saved, reason));
    }

    private long count(String sql, UUID skuId) {
        return jdbc.sql(sql).param("skuId", skuId).query(Long.class).single();
    }

    private Bin bin(UUID binId, Long expectedVersion) {
        Bin bin = bins.findById(binId).orElseThrow(() -> new ResourceNotFoundException("Bin", binId));
        if (expectedVersion != null && (bin.version() == null || !bin.version().equals(expectedVersion))) {
            throw new OptimisticLockingFailureException("Bin " + bin.code() + " changed since it was read");
        }
        return bin;
    }

    private Sku sku(UUID skuId, Long expectedVersion) {
        Sku sku = skus.findById(skuId).orElseThrow(() -> new ResourceNotFoundException("SKU", skuId));
        if (expectedVersion != null && (sku.version() == null || !sku.version().equals(expectedVersion))) {
            throw new OptimisticLockingFailureException("SKU " + sku.code() + " changed since it was read");
        }
        return sku;
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required");
        }
    }

    private static Json snapshot(Bin b, String reason) {
        return Json.object("code", b.code(), "capacityUnits", Integer.toString(b.capacityUnits()),
                "active", String.valueOf(b.active()), "reason", reason);
    }

    private static Json snapshot(Sku s, String reason) {
        return Json.object("code", s.code(), "description", s.description(), "uom", s.uom(),
                "active", String.valueOf(s.active()), "reason", reason);
    }
}
