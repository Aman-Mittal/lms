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

import java.net.URI;
import java.util.UUID;

import com.lms.shared.query.Slice;
import com.lms.shared.web.IdResponse;
import com.lms.warehouse.command.WarehouseSetupCommandService;
import com.lms.warehouse.query.BinView;
import com.lms.warehouse.query.SkuView;
import com.lms.warehouse.query.WarehouseQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Bins in a warehouse, and the SKUs a tenant stores. */
@RestController
public class WarehouseSetupController {

    private final WarehouseSetupCommandService setup;
    private final WarehouseQueryService queries;

    public WarehouseSetupController(WarehouseSetupCommandService setup,
                                    WarehouseQueryService queries) {
        this.setup = setup;
        this.queries = queries;
    }

    @GetMapping("/api/v1/warehouses/{warehouseId}/bins")
    public Slice<BinView> bins(@PathVariable UUID warehouseId,
                               @RequestParam(required = false) String cursor,
                               @RequestParam(required = false) Integer limit) {
        return queries.bins(warehouseId, cursor, limit);
    }

    @PostMapping("/api/v1/warehouses/{warehouseId}/bins")
    public ResponseEntity<IdResponse> createBin(@PathVariable UUID warehouseId,
                                                @Valid @RequestBody CreateBinRequest request) {
        UUID id = setup.createBin(warehouseId, request.code(), request.capacityUnits());
        return ResponseEntity.created(URI.create("/api/v1/warehouses/" + warehouseId + "/bins"))
                .body(new IdResponse(id));
    }

    @GetMapping("/api/v1/skus")
    public Slice<SkuView> skus(@RequestParam(required = false) String cursor,
                               @RequestParam(required = false) Integer limit) {
        return queries.skus(cursor, limit);
    }

    @PostMapping("/api/v1/skus")
    public ResponseEntity<IdResponse> createSku(@Valid @RequestBody CreateSkuRequest request) {
        UUID id = setup.createSku(request.code(), request.description(),
                request.ownerPartnerId(), request.uom());
        return ResponseEntity.created(URI.create("/api/v1/skus")).body(new IdResponse(id));
    }

    public record CreateBinRequest(@NotBlank String code, @Positive int capacityUnits) {
    }

    public record CreateSkuRequest(@NotBlank String code, @NotBlank String description,
                                   UUID ownerPartnerId, String uom) {
    }
}
