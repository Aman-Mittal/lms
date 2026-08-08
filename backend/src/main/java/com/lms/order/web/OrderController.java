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
 */package com.lms.order.web;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lms.order.api.MaterialClass;
import com.lms.order.command.OrderCommandService;
import com.lms.order.command.domain.SalesOrder;
import com.lms.order.query.OrderLineView;
import com.lms.order.query.OrderQueryService;
import com.lms.order.query.OrderView;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.query.Slice;
import com.lms.shared.web.IdResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer indents (vision document 3.3).
 *
 * <p>Validation is a POST to {@code /validate} rather than a status field a
 * client sets. It is the step that computes chargeable weight and refuses
 * incompatible material on one order, so it either succeeds or explains why --
 * neither of which a field assignment can express.
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderCommandService orders;
    private final OrderQueryService orderQueries;

    public OrderController(OrderCommandService orders, OrderQueryService orderQueries) {
        this.orders = orders;
        this.orderQueries = orderQueries;
    }

    @GetMapping
    public Slice<OrderView> list(@RequestParam(required = false) String status,
                                 @RequestParam(required = false) String cursor,
                                 @RequestParam(required = false) Integer limit) {
        return orderQueries.list(status, cursor, limit);
    }

    @GetMapping("/{id}")
    public OrderView get(@PathVariable UUID id) {
        return orderQueries.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("SalesOrder", id));
    }

    @GetMapping("/{id}/lines")
    public List<OrderLineView> lines(@PathVariable UUID id) {
        return orderQueries.findLines(id);
    }

    @PostMapping
    public ResponseEntity<IdResponse> raise(@Valid @RequestBody RaiseOrderRequest request) {
        UUID id = orders.raiseOrder(request.orgUnitId(), request.customerPartnerId(),
                request.orderNo(), request.originTerminalId(), request.requestedPickupAt(),
                request.requestedDeliveryAt(),
                request.source() == null ? SalesOrder.OrderSource.MANUAL : request.source());
        return ResponseEntity.created(URI.create("/api/v1/orders/" + id))
                .body(new IdResponse(id));
    }

    @PostMapping("/{id}/lines")
    public ResponseEntity<IdResponse> addLine(@PathVariable UUID id,
                                              @Valid @RequestBody AddLineRequest request) {
        UUID lineId = orders.addLine(id, request.lineNo(), request.materialCode(),
                request.materialDescription(), request.materialClass(), request.hazmatUnCode(),
                request.quantity(), request.uom(), request.deadWeightKg(),
                request.lengthM(), request.widthM(), request.heightM(),
                request.volumetricDivisor(), request.consigneePartnerId(),
                request.destinationTerminalId());
        return ResponseEntity.created(URI.create("/api/v1/orders/" + id + "/lines"))
                .body(new IdResponse(lineId));
    }

    @PostMapping("/{id}/validate")
    public ResponseEntity<Void> validate(@PathVariable UUID id) {
        orders.validate(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable UUID id) {
        orders.cancel(id);
        return ResponseEntity.noContent().build();
    }

    public record RaiseOrderRequest(
            @NotNull UUID orgUnitId,
            @NotNull UUID customerPartnerId,
            @NotBlank String orderNo,
            @NotNull UUID originTerminalId,
            Instant requestedPickupAt,
            Instant requestedDeliveryAt,
            SalesOrder.OrderSource source) {
    }

    /**
     * One line of an indent.
     *
     * <p>The three dimensions are optional together. Volumetric weight is only
     * meaningful with all three, and a partially dimensioned line would be
     * billed on dead weight while looking as though it had been measured.
     */
    public record AddLineRequest(
            @Positive int lineNo,
            @NotBlank String materialCode,
            String materialDescription,
            @NotNull MaterialClass materialClass,
            String hazmatUnCode,
            @NotNull @Positive BigDecimal quantity,
            @NotBlank String uom,
            @NotNull @Positive BigDecimal deadWeightKg,
            BigDecimal lengthM,
            BigDecimal widthM,
            BigDecimal heightM,
            BigDecimal volumetricDivisor,
            UUID consigneePartnerId,
            @NotNull UUID destinationTerminalId) {
    }
}
