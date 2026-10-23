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
package com.lms.clientbilling.web;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.lms.clientbilling.command.ClientBillingRunService;
import com.lms.clientbilling.command.ClientChargeService;
import com.lms.clientbilling.command.ClientContractService;
import com.lms.clientbilling.command.ClientCreditNoteService;
import com.lms.clientbilling.command.ClientPaymentService;
import com.lms.clientbilling.command.ClientStorageService;
import com.lms.clientbilling.command.domain.ClientContract;
import com.lms.clientbilling.command.domain.ServiceType;
import com.lms.clientbilling.query.ClientBillingQueryService;
import com.lms.clientbilling.query.ClientChargeView;
import com.lms.clientbilling.query.ClientContractView;
import com.lms.clientbilling.query.ClientCreditNoteView;
import com.lms.clientbilling.query.ClientInvoiceLineView;
import com.lms.clientbilling.query.ClientInvoiceView;
import com.lms.clientbilling.query.ClientMarginView;
import com.lms.clientbilling.query.ClientStatementEntryView;
import com.lms.clientbilling.query.ClientStorageView;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.query.Slice;
import com.lms.shared.web.IdResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Third-party logistics client billing over HTTP.
 *
 * <p>Client-scoped resources hang off {@code /clients/{clientId}} so that the
 * client a request concerns is explicit in the path, which is what the
 * isolation check is applied to.
 */
@RestController
@RequestMapping("/api/v1/client-billing")
public class ClientBillingController {

    private final ClientContractService contracts;
    private final ClientChargeService charges;
    private final ClientBillingRunService runs;
    private final ClientCreditNoteService creditNotes;
    private final ClientPaymentService payments;
    private final ClientStorageService storage;
    private final ClientBillingQueryService queries;

    public ClientBillingController(ClientContractService contracts, ClientChargeService charges,
                                   ClientBillingRunService runs,
                                   ClientCreditNoteService creditNotes,
                                   ClientPaymentService payments,
                                   ClientStorageService storage,
                                   ClientBillingQueryService queries) {
        this.contracts = contracts;
        this.charges = charges;
        this.runs = runs;
        this.creditNotes = creditNotes;
        this.payments = payments;
        this.storage = storage;
        this.queries = queries;
    }

    @PostMapping("/contracts")
    public ResponseEntity<IdResponse> publishContract(
            @Valid @RequestBody PublishContractRequest request) {
        UUID id = contracts.publishContract(request.orgUnitId(), request.code(),
                request.clientPartnerId(), request.currency(), request.effectiveFrom(),
                new ClientContract.Rates(request.transportRatePerKg(),
                        request.transportMinimumCharge(), request.storageRatePerPalletDay(),
                        request.handlingInRatePerPallet(), request.handlingOutRatePerPallet(),
                        request.transportTaxRatePct(), request.warehouseTaxRatePct(),
                        request.storageUnitsPerPallet()),
                request.valueAddedServices() == null ? List.of()
                        : request.valueAddedServices().stream()
                        .map(s -> new ClientContractService.ServiceRateTerm(s.serviceCode(),
                                s.unit(), s.rate()))
                        .toList());
        return ResponseEntity.created(URI.create("/api/v1/client-billing/clients/"
                        + request.clientPartnerId() + "/contracts"))
                .body(new IdResponse(id));
    }

    @GetMapping("/clients/{clientId}/contracts")
    public List<ClientContractView> contracts(@PathVariable UUID clientId) {
        return queries.contractsOf(clientId);
    }

    @PostMapping("/clients/{clientId}/portal-users")
    public ResponseEntity<Void> grantPortalAccess(@PathVariable UUID clientId,
                                                  @Valid @RequestBody PortalUserRequest request) {
        contracts.grantPortalAccess(clientId, request.userId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/clients/{clientId}/service-usage")
    public ResponseEntity<IdResponse> recordServiceUsage(
            @PathVariable UUID clientId, @Valid @RequestBody ServiceUsageRequest request) {
        UUID id = charges.recordServiceUsage(clientId, request.serviceType(),
                request.serviceCode(), request.quantity(), request.occurredOn(),
                request.reference(), request.sourceKey());
        return ResponseEntity.created(URI.create("/api/v1/client-billing/clients/" + clientId
                        + "/charges?period=" + request.occurredOn().withDayOfMonth(1)))
                .body(new IdResponse(id));
    }

    @GetMapping("/clients/{clientId}/charges")
    public Slice<ClientChargeView> charges(
            @PathVariable UUID clientId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return queries.chargesOf(clientId, period, cursor, limit);
    }

    /**
     * Runs the billing for one month. Answers 200 with the invoice whether this
     * call issued it or an earlier one did: the run is idempotent per client
     * and month.
     */
    @PostMapping("/clients/{clientId}/billing-runs")
    public IdResponse runBilling(@PathVariable UUID clientId,
                                 @Valid @RequestBody BillingRunRequest request) {
        return new IdResponse(runs.run(clientId, request.period()));
    }

    @GetMapping("/clients/{clientId}/invoices")
    public Slice<ClientInvoiceView> invoices(@PathVariable UUID clientId,
                                             @RequestParam(required = false) String cursor,
                                             @RequestParam(required = false) Integer limit) {
        return queries.invoicesOf(clientId, cursor, limit);
    }

    @GetMapping("/invoices/{id}")
    public ClientInvoiceView invoice(@PathVariable UUID id) {
        return queries.findInvoice(id)
                .orElseThrow(() -> new ResourceNotFoundException("ClientInvoice", id));
    }

    @GetMapping("/invoices/{id}/lines")
    public List<ClientInvoiceLineView> invoiceLines(@PathVariable UUID id) {
        return queries.linesOf(id);
    }

    /**
     * Computes a client's storage for an ended month from the warehouse ledger
     * and charges it. Answers 200 with the computation whether this call made
     * it or an earlier one did.
     */
    @PostMapping("/clients/{clientId}/storage-computations")
    public ClientStorageView computeStorage(@PathVariable UUID clientId,
                                            @Valid @RequestBody StorageComputationRequest request) {
        storage.computeMonth(clientId, request.period());
        return queries.storageOf(clientId, request.period());
    }

    @GetMapping("/clients/{clientId}/storage")
    public ClientStorageView storage(
            @PathVariable UUID clientId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        return queries.storageOf(clientId, period);
    }

    @GetMapping("/clients/{clientId}/statement")
    public Slice<ClientStatementEntryView> statement(
            @PathVariable UUID clientId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return queries.statementOf(clientId, cursor, limit);
    }

    @PostMapping("/clients/{clientId}/payments")
    public ResponseEntity<IdResponse> recordPayment(
            @PathVariable UUID clientId, @Valid @RequestBody PaymentRequest request) {
        UUID paymentId = payments.record(clientId, request.amount(), request.receivedOn(),
                request.reference());
        return ResponseEntity.created(URI.create("/api/v1/client-billing/clients/" + clientId
                        + "/statement"))
                .body(new IdResponse(paymentId));
    }

    @PostMapping("/invoices/{id}/credit-notes")
    public ResponseEntity<IdResponse> issueCreditNote(
            @PathVariable UUID id, @Valid @RequestBody CreditNoteRequest request) {
        UUID creditNoteId = creditNotes.issue(id, request.reason(), request.amount(),
                request.lines() == null ? List.of() : request.lines().stream()
                        .map(l -> new ClientCreditNoteService.LineCredit(l.lineNo(), l.amount()))
                        .toList());
        return ResponseEntity.created(URI.create("/api/v1/client-billing/credit-notes/"
                        + creditNoteId))
                .body(new IdResponse(creditNoteId));
    }

    @GetMapping("/credit-notes/{id}")
    public ClientCreditNoteView creditNote(@PathVariable UUID id) {
        return queries.findCreditNote(id)
                .orElseThrow(() -> new ResourceNotFoundException("ClientCreditNote", id));
    }

    @GetMapping("/margins")
    public Slice<ClientMarginView> margins(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return queries.margins(period, cursor, limit);
    }

    public record PublishContractRequest(@NotNull UUID orgUnitId,
                                         @NotBlank String code,
                                         @NotNull UUID clientPartnerId,
                                         String currency,
                                         @NotNull LocalDate effectiveFrom,
                                         BigDecimal transportRatePerKg,
                                         BigDecimal transportMinimumCharge,
                                         BigDecimal storageRatePerPalletDay,
                                         BigDecimal handlingInRatePerPallet,
                                         BigDecimal handlingOutRatePerPallet,
                                         BigDecimal transportTaxRatePct,
                                         BigDecimal warehouseTaxRatePct,
                                         Integer storageUnitsPerPallet,
                                         List<@Valid ValueAddedServiceRate> valueAddedServices) {
    }

    public record ValueAddedServiceRate(@NotBlank String serviceCode,
                                        @NotBlank String unit,
                                        @NotNull BigDecimal rate) {
    }

    public record PortalUserRequest(@NotNull UUID userId) {
    }

    public record ServiceUsageRequest(@NotNull ServiceType serviceType,
                                      String serviceCode,
                                      @NotNull @Positive BigDecimal quantity,
                                      @NotNull LocalDate occurredOn,
                                      String reference,
                                      @NotBlank String sourceKey) {
    }

    /** @param period any day of the month to bill */
    public record BillingRunRequest(@NotNull LocalDate period) {
    }

    /** @param period any day of the month to compute */
    public record StorageComputationRequest(@NotNull LocalDate period) {
    }

    /**
     * Exactly one of {@code amount} (before tax, spread over the invoice) and
     * {@code lines}.
     */
    public record CreditNoteRequest(@NotBlank String reason,
                                    @Positive BigDecimal amount,
                                    List<@Valid CreditNoteLineRequest> lines) {
    }

    /**
     * @param amount     what arrived, tax included, at most two decimals
     * @param receivedOn the day the money reached the bank
     * @param reference  the client's remittance reference; one per client
     */
    public record PaymentRequest(@NotNull @Positive BigDecimal amount,
                                 @NotNull LocalDate receivedOn,
                                 @NotBlank String reference) {
    }

    /** @param amount taxable amount to credit; omitted credits what remains on the line */
    public record CreditNoteLineRequest(@NotNull @Positive Integer lineNo,
                                        @Positive BigDecimal amount) {
    }
}
