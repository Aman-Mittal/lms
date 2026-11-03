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
package com.lms.warehouse.web;

import java.util.UUID;

import com.lms.shared.query.Slice;
import com.lms.shared.web.IdResponse;
import com.lms.warehouse.command.InventoryCommandService;
import com.lms.warehouse.query.StockBalanceView;
import com.lms.warehouse.query.StockMovementView;
import com.lms.warehouse.query.WarehouseQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Stock on hand, the ledger behind it, and corrections after a count. */
@RestController
public class InventoryController {

    private final InventoryCommandService inventory;
    private final WarehouseQueryService queries;

    public InventoryController(InventoryCommandService inventory, WarehouseQueryService queries) {
        this.inventory = inventory;
        this.queries = queries;
    }

    @GetMapping("/api/v1/stock")
    public Slice<StockBalanceView> stock(@RequestParam UUID warehouseId,
                                         @RequestParam(required = false) UUID skuId,
                                         @RequestParam(required = false) UUID binId,
                                         @RequestParam(required = false) String cursor,
                                         @RequestParam(required = false) Integer limit) {
        return queries.stock(warehouseId, skuId, binId, cursor, limit);
    }

    @GetMapping("/api/v1/stock/movements")
    public Slice<StockMovementView> movements(@RequestParam UUID skuId,
                                              @RequestParam(required = false) String cursor,
                                              @RequestParam(required = false) Integer limit) {
        return queries.movements(skuId, cursor, limit);
    }

    /** Answers with the adjustment's own movement id: it is the record of the correction. */
    @PostMapping("/api/v1/stock/adjustments")
    public ResponseEntity<IdResponse> adjust(@Valid @RequestBody AdjustStockRequest request) {
        UUID id = inventory.adjust(request.binId(), request.skuId(), request.lotNo(),
                request.quantity(), request.reason());
        return ResponseEntity.status(HttpStatus.CREATED).body(new IdResponse(id));
    }

    /** Answers with the id of the movement that took the units out of quarantine. */
    @PostMapping("/api/v1/stock/quarantine/releases")
    public ResponseEntity<IdResponse> releaseFromQuarantine(
            @Valid @RequestBody ReleaseQuarantineRequest request) {
        UUID id = inventory.releaseFromQuarantine(request.binId(), request.skuId(),
                request.lotNo(), request.quantity(), request.reason());
        return ResponseEntity.status(HttpStatus.CREATED).body(new IdResponse(id));
    }

    /** Answers with the write-off movement's id. */
    @PostMapping("/api/v1/stock/quarantine/write-offs")
    public ResponseEntity<IdResponse> writeOffQuarantine(
            @Valid @RequestBody WriteOffQuarantineRequest request) {
        UUID id = inventory.writeOffQuarantine(request.warehouseId(), request.skuId(),
                request.lotNo(), request.quantity(), request.reason());
        return ResponseEntity.status(HttpStatus.CREATED).body(new IdResponse(id));
    }

    public record ReleaseQuarantineRequest(@NotNull UUID binId, @NotNull UUID skuId,
                                           String lotNo, @Positive int quantity,
                                           @NotBlank String reason) {
    }

    public record WriteOffQuarantineRequest(@NotNull UUID warehouseId, @NotNull UUID skuId,
                                            String lotNo, @Positive int quantity,
                                            @NotBlank String reason) {
    }

    /** @param quantity signed; negative for a loss found on counting */
    public record AdjustStockRequest(@NotNull UUID binId, @NotNull UUID skuId, String lotNo,
                                     @NotNull Integer quantity, @NotBlank String reason) {
    }
}
