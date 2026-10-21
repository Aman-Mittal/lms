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

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.web.IdResponse;
import com.lms.warehouse.command.OutboundCommandService;
import com.lms.warehouse.command.domain.HandlingUnit;
import com.lms.warehouse.query.HandlingUnitView;
import com.lms.warehouse.query.OutboundOrderView;
import com.lms.warehouse.query.WarehouseQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Goods out: outbound orders, picking, packing and the hand-off. */
@RestController
public class OutboundController {

    private final OutboundCommandService outbound;
    private final WarehouseQueryService queries;

    public OutboundController(OutboundCommandService outbound, WarehouseQueryService queries) {
        this.outbound = outbound;
        this.queries = queries;
    }

    @PostMapping("/api/v1/outbound-orders")
    public ResponseEntity<IdResponse> createOrder(
            @Valid @RequestBody CreateOutboundOrderRequest request) {
        UUID id = outbound.createOrder(request.warehouseId(), request.orderNo(), request.shipTo(),
                request.lines().stream()
                        .map(line -> new OutboundCommandService.RequestedLine(line.skuId(),
                                line.quantity()))
                        .toList());
        return ResponseEntity.created(URI.create("/api/v1/outbound-orders/" + id))
                .body(new IdResponse(id));
    }

    @GetMapping("/api/v1/outbound-orders/{id}")
    public OutboundOrderView order(@PathVariable UUID id) {
        return queries.outboundOrder(id)
                .orElseThrow(() -> new ResourceNotFoundException("Outbound order", id));
    }

    @PostMapping("/api/v1/outbound-orders/{id}/pick")
    public ResponseEntity<Void> pick(@PathVariable UUID id) {
        outbound.pick(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/outbound-orders/{id}/pack")
    public ResponseEntity<IdResponse> pack(@PathVariable UUID id) {
        UUID unitId = outbound.pack(id);
        return ResponseEntity.created(URI.create("/api/v1/handling-units/" + unitId))
                .body(new IdResponse(unitId));
    }

    @GetMapping("/api/v1/handling-units/{id}")
    public HandlingUnitView handlingUnit(@PathVariable UUID id) {
        return queries.handlingUnit(id)
                .orElseThrow(() -> new ResourceNotFoundException("Handling unit", id));
    }

    @PostMapping("/api/v1/handling-units/{id}/dispatch")
    public ResponseEntity<Void> dispatch(@PathVariable UUID id,
                                         @RequestBody(required = false) DispatchRequest request) {
        outbound.dispatch(id, request == null ? null : request.reference(),
                request == null ? null : HandlingUnit.HandOff.ofNullable(
                        request.destinationTerminalId(), request.consigneePartnerId(),
                        request.grossWeightKg(), request.volumeM3()));
        return ResponseEntity.noContent().build();
    }

    public record CreateOutboundOrderRequest(@NotNull UUID warehouseId,
                                             @NotBlank String orderNo,
                                             String shipTo,
                                             @NotEmpty List<@Valid RequestedLine> lines) {
    }

    public record RequestedLine(@NotNull UUID skuId, @Positive int quantity) {
    }

    /**
     * @param reference             what transport calls it: a load, a trip, a docket
     * @param destinationTerminalId with the consignee and the gross weight, hands
     *                              the unit to transport planning; all three or none
     * @param volumeM3              optional even then
     */
    public record DispatchRequest(String reference, UUID destinationTerminalId,
                                  UUID consigneePartnerId, BigDecimal grossWeightKg,
                                  BigDecimal volumeM3) {
    }
}
