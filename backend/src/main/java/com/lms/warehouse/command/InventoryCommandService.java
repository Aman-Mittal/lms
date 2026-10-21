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
import com.lms.warehouse.command.domain.AsnLine;
import com.lms.warehouse.command.domain.Bin;
import com.lms.warehouse.command.domain.LocationType;
import com.lms.warehouse.command.domain.MovementType;
import com.lms.warehouse.events.QuarantineResolved;
import com.lms.warehouse.events.StockAdjusted;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Corrections to stock after a count.
 *
 * <p>An adjustment is the one movement with no physical event behind it, so
 * it needs its own permission, a reason, and an audit entry. It is still a
 * movement: the ledger row that was wrong stays as it was, and the
 * correction sits next to it.
 *
 * <p>Releasing quarantined units to a bin, and writing them off, are
 * adjustments in the same sense: nobody received or picked anything, a
 * person decided. They take the same permission, need a reason, and are
 * audited the same way.
 */
@Service
public class InventoryCommandService {

    private final BinRepository bins;
    private final SkuRepository skus;
    private final StockLedger ledger;
    private final AuditTrail audit;
    private final ApplicationEventPublisher events;

    public InventoryCommandService(BinRepository bins, SkuRepository skus, StockLedger ledger,
                                   AuditTrail audit, ApplicationEventPublisher events) {
        this.bins = bins;
        this.skus = skus;
        this.ledger = ledger;
        this.audit = audit;
        this.events = events;
    }

    /**
     * @param quantity signed: negative for a loss found on counting
     */
    @Transactional
    @PreAuthorize("hasAuthority('INVENTORY_ADJUST')"
            + " and @abac.permits('INVENTORY_ADJUST', 'WAREHOUSE_BIN', #binId)")
    public UUID adjust(UUID binId, UUID skuId, String lotNo, int quantity, String reason) {
        if (quantity == 0) {
            throw new BusinessRuleViolationException("adjustment-empty",
                    "An adjustment of zero changes nothing");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleViolationException("adjustment-not-explained",
                    "An adjustment needs a reason a shrinkage review can act on");
        }
        Bin bin = bins.findById(binId)
                .orElseThrow(() -> new ResourceNotFoundException("Bin", binId));
        if (skuId == null || !skus.existsById(skuId)) {
            throw new ResourceNotFoundException("SKU", skuId);
        }
        String lot = AsnLine.normaliseLot(lotNo);

        int onHand = ledger.onHand(LocationType.BIN, binId, skuId, lot);
        if (onHand + quantity < 0) {
            throw new BusinessRuleViolationException("stock-cannot-go-negative",
                    "Bin " + bin.code() + " holds " + onHand + " of that SKU and lot; removing "
                            + -quantity + " would leave negative stock");
        }
        if (quantity > 0 && ledger.binOccupancy(binId) + quantity > bin.capacityUnits()) {
            throw new BusinessRuleViolationException("bin-over-capacity",
                    "Bin " + bin.code() + " cannot take " + quantity
                            + " more units; it would exceed its capacity of "
                            + bin.capacityUnits());
        }

        Instant now = Instant.now();
        String actor = Warehouses.currentActor();
        UUID movementId = ledger.post(bin.warehouseId(), MovementType.ADJUST, LocationType.BIN,
                binId, skuId, lot, quantity, null, reason.strip(), actor, now);

        audit.record("STOCK_ADJUSTED", "STOCK_MOVEMENT", movementId,
                Json.object("onHand", String.valueOf(onHand)),
                Json.object("onHand", String.valueOf(onHand + quantity),
                        "reason", reason.strip()));
        events.publishEvent(new StockAdjusted(TenantContext.requireTenantId(), movementId,
                bin.warehouseId(), binId, skuId, lot, quantity, reason.strip(), actor, now));
        return movementId;
    }

    /**
     * Moves quarantined units judged fit to sell into a bin.
     *
     * <p>Two ADJUST movements sharing a reference, both carrying the reason:
     * out of the warehouse's quarantine, into the bin. The bin's capacity
     * applies exactly as for a putaway.
     *
     * @return the id of the movement that took the units out of quarantine
     */
    @Transactional
    @PreAuthorize("hasAuthority('INVENTORY_ADJUST')"
            + " and @abac.permits('INVENTORY_ADJUST', 'WAREHOUSE_BIN', #binId)")
    public UUID releaseFromQuarantine(UUID binId, UUID skuId, String lotNo, int quantity,
                                      String reason) {
        requireQuarantineChange(quantity, reason);
        Bin bin = bins.findById(binId)
                .orElseThrow(() -> new ResourceNotFoundException("Bin", binId));
        requireSku(skuId);
        String lot = AsnLine.normaliseLot(lotNo);
        int held = requireQuarantined(bin.warehouseId(), skuId, lot, quantity, "release");
        if (ledger.binOccupancy(binId) + quantity > bin.capacityUnits()) {
            throw new BusinessRuleViolationException("bin-over-capacity",
                    "Bin " + bin.code() + " cannot take " + quantity
                            + " more units; it would exceed its capacity of "
                            + bin.capacityUnits());
        }

        Instant now = Instant.now();
        String actor = Warehouses.currentActor();
        String why = reason.strip();
        UUID reference = UUID.randomUUID();
        UUID movementId = ledger.post(bin.warehouseId(), MovementType.ADJUST,
                LocationType.QUARANTINE, bin.warehouseId(), skuId, lot, -quantity, reference, why,
                actor, now);
        ledger.post(bin.warehouseId(), MovementType.ADJUST, LocationType.BIN, binId, skuId, lot,
                quantity, reference, why, actor, now);

        audit.record("QUARANTINE_RELEASED", "STOCK_MOVEMENT", movementId,
                Json.object("quarantined", String.valueOf(held)),
                Json.object("quarantined", String.valueOf(held - quantity),
                        "binId", binId.toString(), "reason", why));
        events.publishEvent(new QuarantineResolved(TenantContext.requireTenantId(), movementId,
                bin.warehouseId(), QuarantineResolved.Outcome.RELEASED, binId, skuId, lot,
                quantity, why, actor, now));
        return movementId;
    }

    /**
     * Writes quarantined units off: they leave the ledger by one ADJUST
     * movement out of quarantine, with the reason on it.
     *
     * @return the write-off movement's id
     */
    @Transactional
    @PreAuthorize("hasAuthority('INVENTORY_ADJUST')"
            + " and @abac.permits('INVENTORY_ADJUST', 'TERMINAL', #warehouseId)")
    public UUID writeOffQuarantine(UUID warehouseId, UUID skuId, String lotNo, int quantity,
                                   String reason) {
        requireQuarantineChange(quantity, reason);
        if (warehouseId == null) {
            throw new ResourceNotFoundException("Warehouse", null);
        }
        requireSku(skuId);
        String lot = AsnLine.normaliseLot(lotNo);
        int held = requireQuarantined(warehouseId, skuId, lot, quantity, "write off");

        Instant now = Instant.now();
        String actor = Warehouses.currentActor();
        String why = reason.strip();
        UUID movementId = ledger.post(warehouseId, MovementType.ADJUST, LocationType.QUARANTINE,
                warehouseId, skuId, lot, -quantity, null, why, actor, now);

        audit.record("QUARANTINE_WRITTEN_OFF", "STOCK_MOVEMENT", movementId,
                Json.object("quarantined", String.valueOf(held)),
                Json.object("quarantined", String.valueOf(held - quantity), "reason", why));
        events.publishEvent(new QuarantineResolved(TenantContext.requireTenantId(), movementId,
                warehouseId, QuarantineResolved.Outcome.WRITTEN_OFF, null, skuId, lot, quantity,
                why, actor, now));
        return movementId;
    }

    private static void requireQuarantineChange(int quantity, String reason) {
        if (quantity <= 0) {
            throw new BusinessRuleViolationException("quarantine-quantity-invalid",
                    "Release or write off at least one unit");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleViolationException("adjustment-not-explained",
                    "An adjustment needs a reason a shrinkage review can act on");
        }
    }

    private int requireQuarantined(UUID warehouseId, UUID skuId, String lot, int quantity,
                                   String what) {
        int held = ledger.onHand(LocationType.QUARANTINE, warehouseId, skuId, lot);
        if (held < quantity) {
            throw new BusinessRuleViolationException("quarantine-stock-insufficient",
                    "Quarantine holds " + held + " of that SKU and lot; cannot " + what + " "
                            + quantity);
        }
        return held;
    }

    private void requireSku(UUID skuId) {
        if (skuId == null || !skus.existsById(skuId)) {
            throw new ResourceNotFoundException("SKU", skuId);
        }
    }
}
