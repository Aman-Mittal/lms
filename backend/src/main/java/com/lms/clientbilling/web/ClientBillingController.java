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
import com.lms.clientbilling.command.ClientContractLifecycleService;
import com.lms.clientbilling.command.ClientContractService;
import com.lms.clientbilling.command.ClientCreditNoteService;
import com.lms.clientbilling.command.ClientPaymentService;
import com.lms.clientbilling.command.ClientStorageService;
import com.lms.clientbilling.command.ConsignmentBillingService;
import com.lms.approvals.api.ApprovalTicket;
import com.lms.clientbilling.command.domain.ClientContract;
import com.lms.clientbilling.command.domain.ServiceType;
import com.lms.clientbilling.query.ClientAgeingQueryService;
import com.lms.clientbilling.query.ClientAgeingView;
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
import com.lms.shared.web.IfMatch;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
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
    private final ClientContractLifecycleService lifecycle;
    private final ClientChargeService charges;
    private final ClientBillingRunService runs;
    private final ClientCreditNoteService creditNotes;
    private final ClientPaymentService payments;
    private final ClientStorageService storage;
    private final ClientBillingQueryService queries;
    private final ClientAgeingQueryService ageing;
    private final ConsignmentBillingService consignmentBilling;

    public ClientBillingController(ClientContractService contracts,
                                   ClientContractLifecycleService lifecycle, ClientChargeService charges,
                                   ClientBillingRunService runs,
                                   ClientCreditNoteService creditNotes,
                                   ClientPaymentService payments,
                                   ClientStorageService storage,
                                   ClientBillingQueryService queries,
                                   ClientAgeingQueryService ageing,
                                   ConsignmentBillingService consignmentBilling) {
        this.consignmentBilling = consignmentBilling;
        this.contracts = contracts;
        this.lifecycle = lifecycle;
        this.charges = charges;
        this.runs = runs;
        this.creditNotes = creditNotes;
        this.payments = payments;
        this.ageing = ageing;
        this.storage = storage;
        this.queries = queries;
    }

    /** Starts a draft version; it applies to nothing until it is verified. */
    @PostMapping("/contracts")
    public ResponseEntity<IdResponse> createContractDraft(
            @Valid @RequestBody ContractDraftRequest request) {
        UUID id = lifecycle.createDraft(request.orgUnitId(), request.code(),
                request.clientPartnerId(), request.currency(), request.effectiveFrom(), request.validTill(),
                ratesOf(request.transportRatePerKg(), request.transportMinimumCharge(),
                        request.storageRatePerPalletDay(), request.handlingInRatePerPallet(),
                        request.handlingOutRatePerPallet(), request.transportTaxRatePct(),
                        request.warehouseTaxRatePct(), request.storageUnitsPerPallet()),
                termsOf(request.valueAddedServices()), Boolean.TRUE.equals(request.billOnDispatch()));
        return ResponseEntity.created(URI.create("/api/v1/client-billing/contracts/" + id))
                .body(new IdResponse(id));
    }

    /** One contract version, its version as the {@code ETag} an edit sends back in {@code If-Match}. */
    @GetMapping("/contracts/{id}")
    public ResponseEntity<ClientContractView> contract(@PathVariable UUID id) {
        ClientContractView view = queries.findContract(id)
                .orElseThrow(() -> new ResourceNotFoundException("ClientContract", id));
        return ResponseEntity.ok().eTag(IfMatch.etag(view.version())).body(view);
    }

    /** Replaces a draft's content; 409 {@code client-contract-not-draft} once it has been submitted. */
    @PutMapping("/contracts/{id}")
    public ResponseEntity<Void> reviseContractDraft(
            @PathVariable UUID id, @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody ReviseContractRequest request) {
        lifecycle.reviseDraft(id, versionOf(ifMatch), request.orgUnitId(), request.code(), request.currency(),
                request.effectiveFrom(), request.validTill(),
                ratesOf(request.transportRatePerKg(), request.transportMinimumCharge(),
                        request.storageRatePerPalletDay(), request.handlingInRatePerPallet(),
                        request.handlingOutRatePerPallet(), request.transportTaxRatePct(),
                        request.warehouseTaxRatePct(), request.storageUnitsPerPallet()),
                termsOf(request.valueAddedServices()), Boolean.TRUE.equals(request.billOnDispatch()));
        return ResponseEntity.noContent().build();
    }

    /** Deletes a draft. */
    @DeleteMapping("/contracts/{id}")
    public ResponseEntity<Void> deleteContractDraft(
            @PathVariable UUID id, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        lifecycle.deleteDraft(id, versionOf(ifMatch));
        return ResponseEntity.noContent().build();
    }

    /** Every checklist rule, whether the tenant requires it and whether the contract passes it. */
    @GetMapping("/contracts/{id}/checklist")
    public List<ClientContractLifecycleService.ChecklistItem> checklist(@PathVariable UUID id) {
        return lifecycle.checklistOf(id);
    }

    /**
     * Asks for the draft to be verified: 202 with the approval request, or 204
     * when the tenant's policy asks for none and the contract is in force.
     */
    @PostMapping("/contracts/{id}/verification-requests")
    public ResponseEntity<ApprovalTicket> requestVerification(
            @PathVariable UUID id, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return answer(lifecycle.requestVerification(id, versionOf(ifMatch)));
    }

    /** Asks to move the end date of a contract in force later: 202 with the request, or 204 when done. */
    @PostMapping("/contracts/{id}/extensions")
    public ResponseEntity<ApprovalTicket> requestExtension(
            @PathVariable UUID id, @Valid @RequestBody ExtensionRequest request) {
        return answer(lifecycle.requestExtension(id, request.validTill(), request.reason()));
    }

    /** Asks to add a value-added charge from a date: 202 with the request, or 204 when done. */
    @PostMapping("/contracts/{id}/charge-additions")
    public ResponseEntity<ApprovalTicket> requestChargeAddition(
            @PathVariable UUID id, @Valid @RequestBody ChargeAdditionRequest request) {
        return answer(lifecycle.requestChargeAddition(id, request.serviceCode(), request.unit(), request.rate(),
                request.effectiveFrom(), request.reason()));
    }

    private static ResponseEntity<ApprovalTicket> answer(ClientContractLifecycleService.Outcome outcome) {
        if (outcome.approval() != null) {
            return ResponseEntity.accepted()
                    .location(URI.create("/api/v1/approvals/" + outcome.approval().id()))
                    .body(outcome.approval());
        }
        return ResponseEntity.noContent().build();
    }

    private static long versionOf(String ifMatch) {
        return IfMatch.version(ifMatch, "the contract's version");
    }

    private static ClientContract.Rates ratesOf(BigDecimal transport, BigDecimal minimum, BigDecimal storage,
                                                BigDecimal handlingIn, BigDecimal handlingOut,
                                                BigDecimal transportTax, BigDecimal warehouseTax, Integer units) {
        return new ClientContract.Rates(transport, minimum, storage, handlingIn, handlingOut, transportTax,
                warehouseTax, units);
    }

    private static List<ClientContractService.ServiceRateTerm> termsOf(List<ValueAddedServiceRate> rates) {
        return rates == null ? List.of() : rates.stream()
                .map(s -> new ClientContractService.ServiceRateTerm(s.serviceCode(), s.unit(), s.rate()))
                .toList();
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
    /**
     * Marks a consignment billable to its client without waiting for its proof
     * of delivery. The reason is audited; marking it again changes nothing.
     */
    @PostMapping("/consignments/{consignmentId}/billing-eligibility")
    public ResponseEntity<Void> markBillable(@PathVariable UUID consignmentId,
                                             @Valid @RequestBody BillingEligibilityRequest request) {
        consignmentBilling.markBillable(consignmentId, request.reason());
        return ResponseEntity.noContent().build();
    }

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

    @GetMapping("/clients/{clientId}/ageing")
    public ClientAgeingView ageing(
            @PathVariable UUID clientId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate asOf) {
        return ageing.ageing(clientId, asOf);
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

    /** @param code optional: the next number of the tenant's CONTRACT series when omitted */
    public record ContractDraftRequest(@NotNull UUID orgUnitId,
                                       String code,
                                       @NotNull UUID clientPartnerId,
                                       String currency,
                                       @NotNull LocalDate effectiveFrom,
                                       LocalDate validTill,
                                       BigDecimal transportRatePerKg,
                                       BigDecimal transportMinimumCharge,
                                       BigDecimal storageRatePerPalletDay,
                                       BigDecimal handlingInRatePerPallet,
                                       BigDecimal handlingOutRatePerPallet,
                                       BigDecimal transportTaxRatePct,
                                       BigDecimal warehouseTaxRatePct,
                                       Integer storageUnitsPerPallet,
                                       List<@Valid ValueAddedServiceRate> valueAddedServices,
                                       Boolean billOnDispatch) {
    }

    /** A draft's content, replaced whole; the client is not part of it. */
    public record ReviseContractRequest(@NotNull UUID orgUnitId,
                                        String code,
                                        String currency,
                                        @NotNull LocalDate effectiveFrom,
                                        LocalDate validTill,
                                        BigDecimal transportRatePerKg,
                                        BigDecimal transportMinimumCharge,
                                        BigDecimal storageRatePerPalletDay,
                                        BigDecimal handlingInRatePerPallet,
                                        BigDecimal handlingOutRatePerPallet,
                                        BigDecimal transportTaxRatePct,
                                        BigDecimal warehouseTaxRatePct,
                                        Integer storageUnitsPerPallet,
                                        List<@Valid ValueAddedServiceRate> valueAddedServices,
                                        Boolean billOnDispatch) {
    }

    public record ExtensionRequest(@NotNull LocalDate validTill, @NotBlank @Size(max = 500) String reason) {
    }

    public record ChargeAdditionRequest(@NotBlank String serviceCode,
                                        @NotBlank String unit,
                                        @NotNull @PositiveOrZero BigDecimal rate,
                                        @NotNull LocalDate effectiveFrom,
                                        @NotBlank @Size(max = 500) String reason) {
    }

    public record BillingEligibilityRequest(@NotBlank String reason) {
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
