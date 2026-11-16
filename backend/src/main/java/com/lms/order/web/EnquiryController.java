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

package com.lms.order.web;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.lms.order.command.enquiry.EnquiryConversion;
import com.lms.order.command.enquiry.EnquiryLineSpec;
import com.lms.order.command.enquiry.EnquiryService;
import com.lms.order.command.enquiry.LineOutcome;
import com.lms.order.query.EnquiryQueryService;
import com.lms.order.query.EnquiryView;
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
 * The enquiry desk (issue #229). An enquiry is read with its version as the
 * {@code ETag}; every change but converting it sends that version back in
 * {@code If-Match}. Converting is idempotent by itself: a second call answers
 * with the order the first made.
 */
@RestController
@RequestMapping("/api/v1/enquiries")
public class EnquiryController {

    private final EnquiryService enquiries;
    private final EnquiryConversion conversion;
    private final EnquiryQueryService queries;

    public EnquiryController(EnquiryService enquiries, EnquiryConversion conversion, EnquiryQueryService queries) {
        this.enquiries = enquiries;
        this.conversion = conversion;
        this.queries = queries;
    }

    @GetMapping
    public Slice<EnquiryView> list(@RequestParam(required = false) String status,
                                   @RequestParam(required = false) UUID customerId,
                                   @RequestParam(required = false) String q,
                                   @RequestParam(required = false) String cursor,
                                   @RequestParam(required = false) Integer limit) {
        return queries.list(status, customerId, q, cursor, limit);
    }

    @PostMapping
    public ResponseEntity<IdResponse> create(@Valid @RequestBody CreateEnquiryRequest request) {
        UUID id = enquiries.create(request.orgUnitId(), request.customerPartnerId(), request.reference(),
                request.notes(), request.lines().stream().map(EnquiryLineRequest::toSpec).toList());
        return ResponseEntity.created(URI.create("/api/v1/enquiries/" + id)).body(new IdResponse(id));
    }

    @GetMapping("/{id}")
    public ResponseEntity<EnquiryView> get(@PathVariable UUID id) {
        EnquiryView view = queries.find(id).orElseThrow(() -> new ResourceNotFoundException("Enquiry", id));
        return ResponseEntity.ok().eTag(IfMatch.etag(view.version())).body(view);
    }

    @PostMapping("/{id}/rate-discovery")
    public ResponseEntity<Void> discoverRates(@PathVariable UUID id,
                                              @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        enquiries.discoverRates(id, versionOf(ifMatch));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/lines/{lineId}/quote")
    public ResponseEntity<IdResponse> issueQuote(@PathVariable UUID id, @PathVariable UUID lineId,
                                                 @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                 @Valid @RequestBody QuoteRequest request) {
        UUID quote = enquiries.issueQuote(id, lineId, versionOf(ifMatch), request.amount(), request.validUntil(),
                request.note());
        return ResponseEntity.created(URI.create("/api/v1/enquiries/" + id)).body(new IdResponse(quote));
    }

    @PutMapping("/{id}/lines/{lineId}/quote")
    public IdResponse reviseQuote(@PathVariable UUID id, @PathVariable UUID lineId,
                                  @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                  @Valid @RequestBody QuoteRequest request) {
        return new IdResponse(enquiries.reviseQuote(id, lineId, versionOf(ifMatch), request.amount(),
                request.validUntil(), request.note()));
    }

    @PostMapping("/{id}/lines/{lineId}/quote/revocation")
    public ResponseEntity<Void> revokeQuote(@PathVariable UUID id, @PathVariable UUID lineId,
                                            @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                            @RequestBody(required = false) @Valid ReasonRequest request) {
        enquiries.revokeQuote(id, lineId, versionOf(ifMatch), request == null ? null : request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/lines/{lineId}/outcome")
    public ResponseEntity<Void> recordOutcome(@PathVariable UUID id, @PathVariable UUID lineId,
                                              @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                              @Valid @RequestBody OutcomeRequest request) {
        enquiries.recordOutcome(id, lineId, versionOf(ifMatch), request.outcome(), request.wonQuantity(),
                request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable UUID id,
                                       @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                       @Valid @RequestBody CancelEnquiryRequest request) {
        enquiries.cancel(id, versionOf(ifMatch), request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/convert")
    public ConversionResponse convert(@PathVariable UUID id) {
        EnquiryConversion.Conversion done = conversion.convert(id);
        return new ConversionResponse(done.orderId(), done.alreadyConverted());
    }

    private static long versionOf(String ifMatch) {
        return IfMatch.version(ifMatch, "the enquiry's version");
    }

    public record EnquiryLineRequest(
            @NotNull UUID originTerminalId,
            @NotNull UUID destinationTerminalId,
            @Size(max = 60) String loadType,
            @Size(max = 200) String material,
            @NotNull @Positive BigDecimal quantity,
            @NotBlank @Size(max = 20) String uom,
            @Positive BigDecimal weightKg,
            LocalDate wantedPickupOn,
            LocalDate wantedDeliveryOn) {

        EnquiryLineSpec toSpec() {
            return new EnquiryLineSpec(originTerminalId, destinationTerminalId, loadType, material, quantity, uom,
                    weightKg, wantedPickupOn, wantedDeliveryOn);
        }
    }

    public record CreateEnquiryRequest(
            @NotNull UUID orgUnitId,
            @NotNull UUID customerPartnerId,
            @Size(max = 120) String reference,
            @Size(max = 1000) String notes,
            @NotEmpty @Size(max = 100) List<@Valid EnquiryLineRequest> lines) {
    }

    /** The amount of a quote; it holds until {@code validUntil}, else for the tenant's validity policy. */
    public record QuoteRequest(
            @NotNull @Positive BigDecimal amount,
            Instant validUntil,
            @Size(max = 500) String note) {
    }

    public record ReasonRequest(@Size(max = 500) String reason) {
    }

    /** WON, LOST (with a reason) or PARTIAL (with the quantity won). */
    public record OutcomeRequest(
            @NotNull LineOutcome outcome,
            @Positive BigDecimal wonQuantity,
            @Size(max = 500) String reason) {
    }

    public record CancelEnquiryRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record ConversionResponse(UUID orderId, boolean alreadyConverted) {
    }
}
