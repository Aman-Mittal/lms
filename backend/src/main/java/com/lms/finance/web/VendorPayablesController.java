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

package com.lms.finance.web;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.lms.finance.command.VendorPayablesService;
import com.lms.finance.command.domain.VendorPayment;
import com.lms.finance.query.BillSettlementView;
import com.lms.finance.query.PayablesQueryService;
import com.lms.finance.query.VendorInvoiceBillView;
import com.lms.finance.query.VendorInvoiceView;
import com.lms.finance.query.VendorPaymentView;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.query.Slice;
import com.lms.shared.web.IdResponse;
import com.lms.shared.web.IfMatch;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The vendor payables desk (issue #225): invoices captured against approved
 * freight bills, the payments made against them, what is overdue, and the
 * balance settlement of a bill.
 */
@RestController
@RequestMapping("/api/v1")
public class VendorPayablesController {

    private final VendorPayablesService commands;
    private final PayablesQueryService queries;

    public VendorPayablesController(VendorPayablesService commands, PayablesQueryService queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @PostMapping("/vendor-invoices")
    public ResponseEntity<IdResponse> capture(@Valid @RequestBody CaptureRequest request) {
        UUID id = commands.capture(new VendorPayablesService.CaptureCommand(request.vendorInvoiceNo(),
                request.invoiceDate(), request.amount(), request.taxAmount(), request.freightBillIds(),
                request.documentRef(), request.remarks()));
        return ResponseEntity.created(URI.create("/api/v1/vendor-invoices/" + id)).body(new IdResponse(id));
    }

    /** {@code overdue=true} lists what is owed past its due date, earliest due first; otherwise newest first. */
    @GetMapping("/vendor-invoices")
    public Slice<VendorInvoiceView> invoices(@RequestParam(required = false) String status,
                                             @RequestParam(required = false) UUID vendorId,
                                             @RequestParam(required = false) Boolean overdue,
                                             @RequestParam(required = false) String q,
                                             @RequestParam(required = false) String cursor,
                                             @RequestParam(required = false) Integer limit) {
        if (Boolean.TRUE.equals(overdue)) {
            return queries.overdue(vendorId, cursor, limit);
        }
        return queries.invoices(status, vendorId, q, cursor, limit);
    }

    @GetMapping("/vendor-invoices/{id}")
    public ResponseEntity<VendorInvoiceView> invoice(@PathVariable UUID id) {
        VendorInvoiceView view = queries.invoice(id)
                .orElseThrow(() -> new ResourceNotFoundException("VendorInvoice", id));
        return ResponseEntity.ok().eTag(IfMatch.etag(view.version())).body(view);
    }

    @GetMapping("/vendor-invoices/{id}/bills")
    public List<VendorInvoiceBillView> bills(@PathVariable UUID id) {
        requireInvoice(id);
        return queries.billsOf(id);
    }

    @PostMapping("/vendor-invoices/{id}/payments")
    public ResponseEntity<IdResponse> pay(@PathVariable UUID id, @Valid @RequestBody PaymentRequest request) {
        UUID payment = commands.recordPayment(id, new VendorPayablesService.PaymentCommand(request.amount(),
                request.paidOn(), request.mode(), request.reference()));
        return ResponseEntity.created(URI.create("/api/v1/vendor-invoices/" + id + "/payments")).body(new IdResponse(payment));
    }

    @GetMapping("/vendor-invoices/{id}/payments")
    public Slice<VendorPaymentView> invoicePayments(@PathVariable UUID id,
                                                    @RequestParam(required = false) String cursor,
                                                    @RequestParam(required = false) Integer limit) {
        requireInvoice(id);
        return queries.payments(id, null, cursor, limit);
    }

    @GetMapping("/vendor-payments")
    public Slice<VendorPaymentView> payments(@RequestParam(required = false) UUID vendorId,
                                             @RequestParam(required = false) String cursor,
                                             @RequestParam(required = false) Integer limit) {
        return queries.payments(null, vendorId, cursor, limit);
    }

    /** What a freight bill is approved at, the advances against its trip and what is payable. */
    @GetMapping("/freight-bills/{id}/settlement")
    public BillSettlementView settlement(@PathVariable UUID id) {
        return queries.settlementOf(id).orElseThrow(() -> new ResourceNotFoundException("FreightBill", id));
    }

    private void requireInvoice(UUID id) {
        if (queries.invoice(id).isEmpty()) {
            throw new ResourceNotFoundException("VendorInvoice", id);
        }
    }

    public record CaptureRequest(
            @NotBlank @Size(max = 60) String vendorInvoiceNo,
            @NotNull LocalDate invoiceDate,
            @NotNull @Positive BigDecimal amount,
            @PositiveOrZero BigDecimal taxAmount,
            @NotEmpty @Size(max = 100) List<@NotNull UUID> freightBillIds,
            @Size(max = 200) String documentRef,
            @Size(max = 500) String remarks) {
    }

    public record PaymentRequest(
            @NotNull @Positive BigDecimal amount,
            @NotNull LocalDate paidOn,
            VendorPayment.Mode mode,
            @NotBlank @Size(max = 80) String reference) {
    }
}
