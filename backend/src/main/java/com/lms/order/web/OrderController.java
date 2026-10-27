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

import com.lms.order.api.FreightMode;
import com.lms.order.api.MaterialClass;
import com.lms.order.command.OrderCommandService;
import com.lms.order.command.OrderHeaderChange;
import com.lms.order.command.OrderLineSpec;
import com.lms.order.command.domain.SalesOrder;
import com.lms.order.query.OrderLineView;
import com.lms.order.query.OrderQueryService;
import com.lms.order.query.OrderView;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.query.Slice;
import com.lms.shared.web.IdResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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

    /** The order, with its version as the ETag: what an edit sends back in {@code If-Match}. */
    @GetMapping("/{id}")
    public ResponseEntity<OrderView> get(@PathVariable UUID id) {
        OrderView order = orderQueries.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("SalesOrder", id));
        return ResponseEntity.ok().eTag("\"" + (order.version() == null ? 0 : order.version()) + "\"")
                .body(order);
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
                request.source() == null ? SalesOrder.OrderSource.MANUAL : request.source(),
                request.freightMode(), request.originHubTerminalId(),
                request.destinationHubTerminalId());
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
    public ResponseEntity<Void> cancel(@PathVariable UUID id,
                                       @RequestBody(required = false) @Valid CancelOrderRequest request) {
        orders.cancel(id, request == null ? null : request.reason());
        return ResponseEntity.noContent().build();
    }

    /**
     * Replaces the header of a draft order. {@code If-Match} carries the
     * version from the last read; a stale one is 409 {@code concurrent-modification}.
     */
    @PutMapping("/{id}")
    public ResponseEntity<Void> revise(@PathVariable UUID id,
                                       @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                       @Valid @RequestBody OrderHeaderRequest request) {
        orders.reviseDraft(id, versionOf(ifMatch), request.toChange());
        return ResponseEntity.noContent().build();
    }

    /** Replaces every line of a draft order. */
    @PutMapping("/{id}/lines")
    public ResponseEntity<Void> replaceLines(@PathVariable UUID id,
                                             @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                             @Valid @RequestBody ReplaceLinesRequest request) {
        orders.replaceLines(id, versionOf(ifMatch), specsOf(request.lines()));
        return ResponseEntity.noContent().build();
    }

    /** Removes one line from a draft order. */
    @DeleteMapping("/{id}/lines/{lineId}")
    public ResponseEntity<Void> removeLine(@PathVariable UUID id, @PathVariable UUID lineId,
                                           @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        orders.removeLine(id, lineId, versionOf(ifMatch));
        return ResponseEntity.noContent().build();
    }

    /** Amends a validated order that has no consignments yet; see the service for the refusals. */
    @PostMapping("/{id}/amend")
    public ResponseEntity<Void> amend(@PathVariable UUID id,
                                      @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                      @Valid @RequestBody AmendOrderRequest request) {
        if (request.header() == null && request.lines() == null) {
            throw new IllegalArgumentException("An amendment must change the header, the lines or both");
        }
        orders.amend(id, versionOf(ifMatch), request.header() == null ? null : request.header().toChange(),
                request.lines() == null ? null : specsOf(request.lines()));
        return ResponseEntity.noContent().build();
    }

    /**
     * The version in an {@code If-Match} header: the ETag a read returned, quoted
     * or not. Mandatory, because an unconditional edit is the lost update the
     * header exists to prevent; {@code *} is not accepted for the same reason.
     */
    private static long versionOf(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new IllegalArgumentException("An If-Match header carrying the order's version is required");
        }
        String raw = ifMatch.strip();
        if (raw.startsWith("W/")) {
            raw = raw.substring(2);
        }
        if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            raw = raw.substring(1, raw.length() - 1);
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("If-Match must be an order version as returned in its ETag");
        }
    }

    private static List<OrderLineSpec> specsOf(List<AddLineRequest> lines) {
        return lines.stream().map(l -> new OrderLineSpec(l.lineNo(), l.materialCode(),
                l.materialDescription(), l.materialClass(), l.hazmatUnCode(), l.quantity(), l.uom(),
                l.deadWeightKg(), l.lengthM(), l.widthM(), l.heightM(), l.volumetricDivisor(),
                l.consigneePartnerId(), l.destinationTerminalId())).toList();
    }

    /** The editable header, stated whole: an omitted pickup is cleared, not kept. */
    public record OrderHeaderRequest(
            @NotNull UUID customerPartnerId,
            @NotNull UUID originTerminalId,
            Instant requestedPickupAt,
            Instant requestedDeliveryAt,
            UUID originHubTerminalId,
            UUID destinationHubTerminalId) {

        OrderHeaderChange toChange() {
            return new OrderHeaderChange(customerPartnerId, originTerminalId, requestedPickupAt,
                    requestedDeliveryAt, originHubTerminalId, destinationHubTerminalId);
        }
    }

    public record ReplaceLinesRequest(@NotEmpty List<@Valid AddLineRequest> lines) {
    }

    /** Either part may be omitted to leave it as it is, but not both. */
    public record AmendOrderRequest(@Valid OrderHeaderRequest header,
                                    List<@Valid AddLineRequest> lines) {
    }

    /** Why the order is being cancelled. The whole body is optional, and so is the reason. */
    public record CancelOrderRequest(@Size(max = 500) String reason) {
    }

    public record RaiseOrderRequest(
            @NotNull UUID orgUnitId,
            @NotNull UUID customerPartnerId,
            @NotBlank String orderNo,
            @NotNull UUID originTerminalId,
            Instant requestedPickupAt,
            Instant requestedDeliveryAt,
            SalesOrder.OrderSource source,
            /** FTL when omitted, so every existing client keeps its behaviour. */
            FreightMode freightMode,
            /** Required for LTL, refused for FTL. */
            UUID originHubTerminalId,
            UUID destinationHubTerminalId) {
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
            @NotBlank String materialDescription,
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
