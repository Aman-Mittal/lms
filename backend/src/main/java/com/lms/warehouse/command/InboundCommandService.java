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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import com.lms.warehouse.command.domain.Asn;
import com.lms.warehouse.command.domain.AsnLine;
import com.lms.warehouse.command.domain.Bin;
import com.lms.warehouse.command.domain.HandlingUnit;
import com.lms.warehouse.command.domain.LocationType;
import com.lms.warehouse.command.domain.MovementType;
import com.lms.warehouse.events.GoodsReceived;
import com.lms.warehouse.events.ReceiptVarianceRecorded;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Goods in: the notice, the count at the dock, and putaway to a bin.
 *
 * <p>The count is the moment stock comes into existence. Undamaged units go
 * onto the dock; damaged ones are recorded on the line and published as a
 * variance, and never become stock. A cross-docked receipt moves the good
 * units off the dock again at once, into a handling unit, without a bin.
 */
@Service
public class InboundCommandService {

    private final AsnRepository asns;
    private final AsnLineRepository asnLines;
    private final SkuRepository skus;
    private final BinRepository bins;
    private final HandlingUnitRepository handlingUnits;
    private final StockLedger ledger;
    private final Warehouses warehouses;
    private final ApplicationEventPublisher events;

    public InboundCommandService(AsnRepository asns, AsnLineRepository asnLines,
                                 SkuRepository skus, BinRepository bins,
                                 HandlingUnitRepository handlingUnits, StockLedger ledger,
                                 Warehouses warehouses, ApplicationEventPublisher events) {
        this.asns = asns;
        this.asnLines = asnLines;
        this.skus = skus;
        this.bins = bins;
        this.handlingUnits = handlingUnits;
        this.ledger = ledger;
        this.warehouses = warehouses;
        this.events = events;
    }

    /** What the supplier says is on its way. */
    public record ExpectedLine(UUID skuId, String lotNo, int quantity) {
    }

    /** What was counted against one line. */
    public record CountedLine(int lineNo, int receivedQty, int damagedQty) {
    }

    @Transactional
    @PreAuthorize("hasAuthority('WAREHOUSE_OPERATE')")
    public UUID createAsn(UUID warehouseId, String asnNo, UUID supplierPartnerId,
                          List<ExpectedLine> lines) {
        warehouses.requireWarehouse(warehouseId);
        if (lines == null || lines.isEmpty()) {
            throw new BusinessRuleViolationException("asn-has-no-lines",
                    "An ASN needs at least one line");
        }
        UUID tenantId = TenantContext.requireTenantId();
        Asn asn = Asn.expect(tenantId, warehouseId, asnNo, supplierPartnerId, Instant.now());
        if (asns.existsByAsnNo(asn.asnNo())) {
            throw new BusinessRuleViolationException("asn-number-taken",
                    "There is already an ASN " + asn.asnNo());
        }
        asns.save(asn);

        int lineNo = 0;
        for (ExpectedLine line : lines) {
            requireSku(line.skuId());
            asnLines.save(AsnLine.expect(tenantId, asn.id(), ++lineNo, line.skuId(),
                    line.lotNo(), line.quantity()));
        }
        return asn.id();
    }

    /**
     * Counts the delivery in.
     *
     * <p>A line the count does not mention arrived not at all: it is recorded
     * as received zero, which makes it wholly short rather than silently
     * still expected.
     *
     * @param crossDock route the good units straight to a new outbound
     *                  handling unit instead of leaving them for putaway
     * @return the handling unit created for a cross-dock, otherwise null
     */
    @Transactional
    @PreAuthorize("hasAuthority('WAREHOUSE_OPERATE')")
    public UUID receive(UUID asnId, List<CountedLine> counts, boolean crossDock) {
        UUID tenantId = TenantContext.requireTenantId();
        Asn asn = asns.findById(asnId)
                .orElseThrow(() -> new ResourceNotFoundException("ASN", asnId));
        Instant now = Instant.now();
        String actor = Warehouses.currentActor();
        Asn received = asns.save(asn.receive(crossDock, actor, now));

        Map<Integer, CountedLine> byLine = new HashMap<>();
        for (CountedLine count : counts == null ? List.<CountedLine>of() : counts) {
            if (byLine.put(count.lineNo(), count) != null) {
                throw new BusinessRuleViolationException("receipt-line-repeated",
                        "Line " + count.lineNo() + " was counted twice in one receipt");
            }
        }

        List<AsnLine> lines = asnLines.findByAsnIdOrderByLineNo(asnId);
        Set<Integer> known = new HashSet<>();
        lines.forEach(line -> known.add(line.lineNo()));
        for (Integer lineNo : byLine.keySet()) {
            if (!known.contains(lineNo)) {
                throw new BusinessRuleViolationException("receipt-line-unknown",
                        "ASN " + asn.asnNo() + " has no line " + lineNo);
            }
        }

        HandlingUnit unit = crossDock
                ? handlingUnits.save(HandlingUnit.crossDockedFrom(tenantId, asn.warehouseId(),
                        asnId, now))
                : null;

        int good = 0;
        int damaged = 0;
        List<ReceiptVarianceRecorded> variances = new ArrayList<>();
        for (AsnLine line : lines) {
            CountedLine count = byLine.getOrDefault(line.lineNo(),
                    new CountedLine(line.lineNo(), 0, 0));
            AsnLine counted = asnLines.save(line.count(count.receivedQty(), count.damagedQty()));
            good += counted.goodQty();
            damaged += counted.damagedQty();

            if (counted.goodQty() > 0) {
                ledger.post(asn.warehouseId(), MovementType.RECEIPT, LocationType.DOCK,
                        asn.warehouseId(), counted.skuId(), counted.lotNo(), counted.goodQty(),
                        asnId, null, actor, now);
                if (unit != null) {
                    ledger.transfer(asn.warehouseId(), MovementType.CROSS_DOCK,
                            LocationType.DOCK, asn.warehouseId(), LocationType.HU, unit.id(),
                            counted.skuId(), counted.lotNo(), counted.goodQty(), unit.id(),
                            actor, now);
                }
            }
            if (counted.hasVariance()) {
                variances.add(new ReceiptVarianceRecorded(tenantId, asnId, asn.asnNo(),
                        asn.warehouseId(), counted.lineNo(), counted.skuId(), counted.lotNo(),
                        counted.expectedQty(), counted.receivedQty(), counted.shortQty(),
                        counted.excessQty(), counted.damagedQty(), now));
            }
        }

        if (unit != null && good == 0) {
            throw new BusinessRuleViolationException("cross-dock-nothing-received",
                    "Nothing undamaged was received on ASN " + asn.asnNo()
                            + ", so there is nothing to cross-dock");
        }

        events.publishEvent(new GoodsReceived(tenantId, asnId, received.asnNo(),
                received.warehouseId(), good, damaged, crossDock,
                unit == null ? null : unit.id(), now));
        variances.forEach(events::publishEvent);
        return unit == null ? null : unit.id();
    }

    /**
     * Moves received stock from the dock into a bin.
     *
     * <p>Refused when the dock does not hold that much of the SKU and lot, or
     * when the bin has no room for it -- capacity counts everything already
     * in the bin, whatever the SKU.
     */
    @Transactional
    @PreAuthorize("hasAuthority('WAREHOUSE_OPERATE')")
    public void putaway(UUID binId, UUID skuId, String lotNo, int quantity) {
        if (quantity <= 0) {
            throw new BusinessRuleViolationException("putaway-quantity-invalid",
                    "Put away at least one unit");
        }
        Bin bin = bins.findById(binId)
                .orElseThrow(() -> new ResourceNotFoundException("Bin", binId));
        requireSku(skuId);
        String lot = AsnLine.normaliseLot(lotNo);

        int onDock = ledger.onHand(LocationType.DOCK, bin.warehouseId(), skuId, lot);
        if (onDock < quantity) {
            throw new BusinessRuleViolationException("dock-stock-insufficient",
                    "The dock holds " + onDock + " of that SKU and lot; cannot put away "
                            + quantity);
        }
        int occupied = ledger.binOccupancy(binId);
        if (occupied + quantity > bin.capacityUnits()) {
            throw new BusinessRuleViolationException("bin-over-capacity",
                    "Bin " + bin.code() + " holds " + occupied + " of " + bin.capacityUnits()
                            + " units; " + quantity + " more would exceed its capacity");
        }
        ledger.transfer(bin.warehouseId(), MovementType.PUTAWAY, LocationType.DOCK,
                bin.warehouseId(), LocationType.BIN, binId, skuId, lot, quantity, null,
                Warehouses.currentActor(), Instant.now());
    }

    private void requireSku(UUID skuId) {
        if (skuId == null || !skus.existsById(skuId)) {
            throw new ResourceNotFoundException("SKU", skuId);
        }
    }
}
