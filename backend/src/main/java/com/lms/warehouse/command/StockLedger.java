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
import java.util.List;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.tenant.TenantContext;
import com.lms.warehouse.command.domain.LocationType;
import com.lms.warehouse.command.domain.MovementType;
import com.lms.warehouse.command.domain.StockMovement;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Writes the inventory ledger and reads the balances it derives.
 *
 * <p>The only way the command side changes stock. A transfer is written as two
 * movements sharing a reference -- minus at the source, plus at the
 * destination -- so the ledger balances by construction, and the database
 * applies each to its location's balance as it is inserted.
 *
 * <p>Balances are read with plain SQL rather than through a repository: they
 * are a derived figure, not an aggregate anybody saves.
 */
@Component
class StockLedger {

    private final StockMovementRepository movements;
    private final JdbcClient jdbc;

    StockLedger(StockMovementRepository movements, JdbcClient jdbc) {
        this.movements = movements;
        this.jdbc = jdbc;
    }

    /** One location's holding of one SKU and lot. */
    record Holding(LocationType locationType, UUID locationId, UUID skuId, String lotNo,
                   int onHand) {
    }

    /** Moves {@code quantity} from one location to another, as one transfer. */
    UUID transfer(UUID warehouseId, MovementType type, LocationType fromType, UUID fromId,
                  LocationType toType, UUID toId, UUID skuId, String lotNo, int quantity,
                  UUID referenceId, String actor, Instant at) {
        UUID reference = referenceId == null ? UUID.randomUUID() : referenceId;
        post(warehouseId, type, fromType, fromId, skuId, lotNo, -quantity, reference, null,
                actor, at);
        post(warehouseId, type, toType, toId, skuId, lotNo, quantity, reference, null, actor, at);
        return reference;
    }

    /**
     * Posts one signed movement.
     *
     * <p>The service has already checked stock and capacity, so a refusal from
     * the database here means somebody else moved the same stock in between.
     * It is reported as the business rule it is, not as a server error.
     */
    UUID post(UUID warehouseId, MovementType type, LocationType locationType, UUID locationId,
              UUID skuId, String lotNo, int quantity, UUID referenceId, String reason,
              String actor, Instant at) {
        StockMovement movement = StockMovement.of(TenantContext.requireTenantId(), warehouseId,
                type, locationType, locationId, skuId, lotNo, quantity, referenceId, reason, actor,
                at);
        try {
            return movements.save(movement).id();
        } catch (DataIntegrityViolationException e) {
            String detail = String.valueOf(e.getMostSpecificCause().getMessage());
            if (detail.contains("stock_balance_never_negative")) {
                throw new BusinessRuleViolationException("stock-insufficient",
                        "Not enough stock at the " + locationType + " to move " + -quantity
                                + "; it changed while this was being done");
            }
            if (detail.contains("will not fit")) {
                throw new BusinessRuleViolationException("bin-over-capacity",
                        "The bin filled up while this was being done; " + quantity
                                + " more will not fit");
            }
            throw e;
        }
    }

    int onHand(LocationType locationType, UUID locationId, UUID skuId, String lotNo) {
        return jdbc.sql("""
                        SELECT coalesce(sum(on_hand), 0)::int
                          FROM stock_balance
                         WHERE location_type = :locationType
                           AND location_id = :locationId
                           AND sku_id = :skuId
                           AND lot_no = :lotNo
                        """)
                .param("locationType", locationType.name())
                .param("locationId", locationId)
                .param("skuId", skuId)
                .param("lotNo", lotNo)
                .query(Integer.class)
                .single();
    }

    /** Units in a bin, across every SKU and lot. */
    int binOccupancy(UUID binId) {
        return jdbc.sql("""
                        SELECT coalesce(sum(on_hand), 0)::int
                          FROM stock_balance
                         WHERE location_type = 'BIN'
                           AND location_id = :binId
                        """)
                .param("binId", binId)
                .query(Integer.class)
                .single();
    }

    /**
     * Where a SKU can be picked from, in the order it should be: by bin code,
     * then lot. A fixed order keeps two runs of the same pick identical, which
     * is what makes a short pick explainable afterwards.
     */
    List<Holding> pickableStock(UUID warehouseId, UUID skuId) {
        return jdbc.sql("""
                        SELECT b.location_type, b.location_id, b.sku_id, b.lot_no, b.on_hand
                          FROM stock_balance b
                          JOIN warehouse_bin w ON w.id = b.location_id
                         WHERE b.sku_id = :skuId
                           AND b.warehouse_id = :warehouseId
                           AND b.location_type = 'BIN'
                           AND b.on_hand > 0
                         ORDER BY w.code, b.lot_no
                        """)
                .param("skuId", skuId)
                .param("warehouseId", warehouseId)
                .query(Holding.class)
                .list();
    }

    /** Everything at one location: an order's picked goods, a unit's contents. */
    List<Holding> contentsOf(LocationType locationType, UUID locationId) {
        return jdbc.sql("""
                        SELECT location_type, location_id, sku_id, lot_no, on_hand
                          FROM stock_balance
                         WHERE location_type = :locationType
                           AND location_id = :locationId
                           AND on_hand > 0
                         ORDER BY sku_id, lot_no
                        """)
                .param("locationType", locationType.name())
                .param("locationId", locationId)
                .query(Holding.class)
                .list();
    }
}
