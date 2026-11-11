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
import com.lms.order.command.OrderCloseService;
import com.lms.order.command.OrderCloseSettings;
import com.lms.order.command.OrderCommandService;
import com.lms.order.command.OrderHeaderChange;
import com.lms.approvals.api.ApprovalTicket;
import com.lms.order.command.OrderLineCancellationService;
import com.lms.order.command.OrderLineSpec;
import com.lms.order.command.domain.CloseKind;
import com.lms.order.command.domain.SalesOrder;
import com.lms.order.query.OrderLineView;
import com.lms.order.query.OrderQueryService;
import com.lms.order.query.OrderView;
import com.lms.shared.audit.AuditHistoryQueryService;
import com.lms.shared.audit.HistoryEntryView;
import com.lms.shared.audit.HistoryViewer;
import com.lms.identity.access.AccessSubject;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.query.Slice;
import com.lms.shared.web.IdResponse;
import com.lms.shared.web.IfMatch;
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
    private final OrderCloseService closes;
    private final OrderCloseSettings closeSettings;
    private final OrderQueryService orderQueries;
    private final AuditHistoryQueryService history;
    private final OrderLineCancellationService lineCancellations;

    /** The audit resource type the order module writes; see OrderCommandService. */
    private static final String AUDIT_TYPE = "SalesOrder";

    public OrderController(OrderCommandService orders, OrderCloseService closes,
                           OrderCloseSettings closeSettings, OrderQueryService orderQueries,
                           AuditHistoryQueryService history,
                           OrderLineCancellationService lineCancellations) {
        this.orders = orders;
        this.closes = closes;
        this.closeSettings = closeSettings;
        this.orderQueries = orderQueries;
        this.history = history;
        this.lineCancellations = lineCancellations;
    }

    @GetMapping
    public Slice<OrderView> list(@RequestParam(required = false) String status,
                                 @RequestParam(required = false) String cursor,
                                 @RequestParam(required = false) Integer limit,
                                 @RequestParam(required = false) String q) {
        return orderQueries.list(status, cursor, limit, q);
    }

    /** The order, with its version as the ETag: what an edit sends back in {@code If-Match}. */
    @GetMapping("/{id}")
    public ResponseEntity<OrderView> get(@PathVariable UUID id) {
        OrderView order = orderQueries.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("SalesOrder", id));
        return ResponseEntity.ok().eTag(IfMatch.etag(order.version()))
                .body(order);
    }

    /** Exact lookup by order number; a number that is not the tenant's (or the caller's) is a 404. */
    @GetMapping("/by-number/{orderNo}")
    public OrderView byNumber(@PathVariable String orderNo) {
        return orderQueries.findByOrderNo(orderNo)
                .orElseThrow(() -> new ResourceNotFoundException("No order numbered " + orderNo));
    }

    /**
     * What has happened to the order, newest first. Readable by whoever can
     * read the order: the order is read first, so an order the caller may not
     * see is a 404 here exactly as it is on {@code GET /orders/{id}}.
     */
    @GetMapping("/{id}/history")
    public Slice<HistoryEntryView> history(@PathVariable UUID id,
                                           @RequestParam(required = false) String cursor,
                                           @RequestParam(required = false) Integer limit) {
        if (orderQueries.findById(id).isEmpty()) {
            throw new ResourceNotFoundException("SalesOrder", id);
        }
        AccessSubject caller = AccessSubject.current();
        return history.historyOf(AUDIT_TYPE, id, cursor, limit,
                HistoryViewer.of(caller.userId(), caller.partnerScope() != null));
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

    /**
     * 204 when the order is cancelled; 202 with the approval request when the
     * tenant's policy asks for approval first.
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<ApprovalTicket> cancel(@PathVariable UUID id,
                                                 @RequestBody(required = false) @Valid CancelOrderRequest request) {
        OrderCommandService.Cancellation outcome = orders.cancel(id, request == null ? null : request.reason());
        if (outcome.approval() != null) {
            return ResponseEntity.accepted()
                    .location(URI.create("/api/v1/approvals/" + outcome.approval().id()))
                    .body(outcome.approval());
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Closes the order: NORMAL (the default, when the body or its kind is
     * omitted), SHORT or FORCE. See {@link OrderCloseService} for the rules.
     */
    @PostMapping("/{id}/close")
    public ResponseEntity<Void> close(@PathVariable UUID id,
                                      @RequestBody(required = false) @Valid CloseRequest request) {
        closes.close(id, request == null ? null : request.kind(), request == null ? null : request.reason());
        return ResponseEntity.noContent().build();
    }

    /** Closes one line; the order follows when nothing else is left open. */
    @PostMapping("/{id}/lines/{lineId}/close")
    public ResponseEntity<Void> closeLine(@PathVariable UUID id, @PathVariable UUID lineId,
                                          @RequestBody(required = false) @Valid CloseRequest request) {
        closes.closeLine(id, lineId, request == null ? null : request.kind(),
                request == null ? null : request.reason());
        return ResponseEntity.noContent().build();
    }

    /** The tenant's close policy, defaults included. */
    @GetMapping("/close-settings")
    public CloseSettingsResponse closeSettings() {
        return CloseSettingsResponse.of(closeSettings.view());
    }

    @PutMapping("/close-settings")
    public CloseSettingsResponse setCloseSettings(@Valid @RequestBody CloseSettingsRequest request) {
        return CloseSettingsResponse.of(closeSettings.update(request.tolerancePercent(), request.autoFulfil()));
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

    /**
     * Cancels one line of a validated order, and the consignment it is on;
     * see the service for what that frees and refuses.
     */
    @PostMapping("/{id}/lines/{lineId}/cancellation")
    public OrderLineCancellationService.LineCancellation cancelLine(
            @PathVariable UUID id, @PathVariable UUID lineId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody(required = false) @Valid CancelOrderRequest request) {
        return lineCancellations.cancel(id, lineId, versionOf(ifMatch),
                request == null ? null : request.reason());
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
        return IfMatch.version(ifMatch, "the order's version");
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

    /** How and why an order or line is closed; the kind defaults to NORMAL. */
    public record CloseRequest(CloseKind kind, @Size(max = 500) String reason) {
    }

    /** Boxed and required, so that an omitted switch does not silently turn fulfilment off. */
    public record CloseSettingsRequest(@NotNull BigDecimal tolerancePercent, @NotNull Boolean autoFulfil) {
    }

    public record CloseSettingsResponse(BigDecimal tolerancePercent, boolean autoFulfil, boolean customised) {

        static CloseSettingsResponse of(OrderCloseSettings.Policy policy) {
            return new CloseSettingsResponse(policy.tolerancePercent(), policy.autoFulfil(), policy.customised());
        }
    }

    /** Why the order is being cancelled. The whole body is optional, and so is the reason. */
    public record CancelOrderRequest(@Size(max = 500) String reason) {
    }

    public record RaiseOrderRequest(
            @NotNull UUID orgUnitId,
            @NotNull UUID customerPartnerId,
            /** Assigned from the tenant's sequence when omitted or blank. */
            String orderNo,
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
