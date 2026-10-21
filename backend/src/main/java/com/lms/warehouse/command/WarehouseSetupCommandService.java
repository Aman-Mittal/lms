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

import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.tenant.TenantContext;
import com.lms.warehouse.command.domain.Bin;
import com.lms.warehouse.command.domain.Sku;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The warehouse's own master data: bins and the SKUs stored in them. */
@Service
public class WarehouseSetupCommandService {

    private final BinRepository bins;
    private final SkuRepository skus;
    private final Warehouses warehouses;

    public WarehouseSetupCommandService(BinRepository bins, SkuRepository skus,
                                        Warehouses warehouses) {
        this.bins = bins;
        this.skus = skus;
        this.warehouses = warehouses;
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
}
