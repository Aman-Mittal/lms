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
package com.lms.delivery.web;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lms.approvals.api.ApprovalTicket;
import com.lms.delivery.command.ClaimService;
import com.lms.delivery.command.DeliveryService;
import com.lms.delivery.command.ProofFeedingService;
import com.lms.delivery.command.ProofReversalService;
import com.lms.delivery.command.domain.ClaimStatus;
import com.lms.delivery.command.domain.ClaimType;
import com.lms.delivery.command.domain.GoodsCondition;
import com.lms.delivery.command.domain.ProofDocumentType;
import com.lms.delivery.query.ClaimView;
import com.lms.delivery.query.DeliveryProofView;
import com.lms.delivery.query.DeliveryQueryService;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.query.Slice;
import com.lms.shared.web.IdResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Proof of delivery and claims. Authorisation is in the services, as everywhere else.
 */
@RestController
@RequestMapping("/api/v1")
public class DeliveryController {

    private final DeliveryService delivery;
    private final ClaimService claims;
    private final DeliveryQueryService queries;
    private final ProofReversalService reversals;
    private final ProofFeedingService feeding;

    public DeliveryController(DeliveryService delivery, ClaimService claims,
                              DeliveryQueryService queries, ProofReversalService reversals,
                              ProofFeedingService feeding) {
        this.delivery = delivery;
        this.claims = claims;
        this.queries = queries;
        this.reversals = reversals;
        this.feeding = feeding;
    }

    // ------------------------------------------------------ proof of delivery

    @PostMapping("/deliveries/{consignmentId}/otp")
    public OtpIssued issueOtp(@PathVariable UUID consignmentId) {
        DeliveryService.Issued issued = delivery.issueOtp(consignmentId);
        return new OtpIssued(issued.expiresAt(), issued.otp());
    }

    @PostMapping("/deliveries/{consignmentId}/proof")
    public ResponseEntity<Void> recordProof(@PathVariable UUID consignmentId,
                                            @Valid @RequestBody RecordProofRequest request) {
        delivery.recordProof(consignmentId, request.otp(), request.recipientName(),
                request.photoRef(), request.deliveredAt(), request.lat(), request.lon(),
                request.goodsCondition(), request.remark());
        return ResponseEntity.noContent().build();
    }

    /** Starts the consignment's draft proof, or finds it; asking again changes nothing. */
    @PostMapping("/deliveries/{consignmentId}/pod")
    public ResponseEntity<Void> ensurePod(@PathVariable UUID consignmentId) {
        feeding.ensure(consignmentId);
        return ResponseEntity.noContent().build();
    }

    /** Replaces what was received against the consignment's order lines. */
    @PutMapping("/deliveries/{consignmentId}/pod/lines")
    public ResponseEntity<Void> feedLines(@PathVariable UUID consignmentId,
                                          @Valid @RequestBody FeedLinesRequest request) {
        feeding.feedLines(consignmentId, request.lines() == null ? List.of() : request.lines().stream()
                .map(l -> new ProofFeedingService.LineInput(l.orderLineId(), l.receivedQty(), l.shortQty(),
                        l.damagedQty(), l.excessQty())).toList());
        return ResponseEntity.noContent().build();
    }

    /** Replaces the documents listed for the proof. */
    @PutMapping("/deliveries/{consignmentId}/pod/documents")
    public ResponseEntity<Void> setDocuments(@PathVariable UUID consignmentId,
                                             @Valid @RequestBody SetDocumentsRequest request) {
        feeding.setDocuments(consignmentId, request.documents() == null ? List.of() : request.documents().stream()
                .map(d -> new ProofFeedingService.DocumentInput(d.type(), d.reference())).toList());
        return ResponseEntity.noContent().build();
    }

    /** Sets the reporting and unloading times; one omitted is cleared. */
    @PutMapping("/deliveries/{consignmentId}/pod/timings")
    public ResponseEntity<Void> setTimings(@PathVariable UUID consignmentId,
                                           @Valid @RequestBody SetTimingsRequest request) {
        feeding.setTimings(consignmentId,
                request.reportedAt() == null ? null : request.reportedAt().toInstant(),
                request.unloadingStartedAt() == null ? null : request.unloadingStartedAt().toInstant(),
                request.unloadingEndedAt() == null ? null : request.unloadingEndedAt().toInstant());
        return ResponseEntity.noContent().build();
    }

    /** Submits the fed draft with the consignee's code. */
    @PostMapping("/deliveries/{consignmentId}/pod/submit")
    public ResponseEntity<Void> submitPod(@PathVariable UUID consignmentId,
                                          @Valid @RequestBody SubmitPodRequest request) {
        delivery.submit(consignmentId, request.otp(), request.recipientName(), request.deliveredAt(),
                request.lat(), request.lon(), request.goodsCondition(), request.remark());
        return ResponseEntity.noContent().build();
    }

    /** Keys the proof from the signed paper copy, with no code. */
    @PostMapping("/deliveries/{consignmentId}/pod/paper")
    public ResponseEntity<Void> keyPaperPod(@PathVariable UUID consignmentId,
                                            @Valid @RequestBody PaperPodRequest request) {
        delivery.keyFromPaper(consignmentId, request.recipientName(), request.deliveredAt(), request.lat(),
                request.lon(), request.goodsCondition(), request.remark());
        return ResponseEntity.noContent().build();
    }

    /** Records that the consignee took nothing. */
    @PostMapping("/deliveries/{consignmentId}/pod/not-received")
    public ResponseEntity<Void> notReceived(@PathVariable UUID consignmentId,
                                            @Valid @RequestBody NotReceivedRequest request) {
        feeding.markNotReceived(consignmentId, request.remark());
        return ResponseEntity.noContent().build();
    }

    /**
     * Asks for the proof to be reversed: 202 with the approval request, which
     * reverses it when approved.
     */
    @PostMapping("/deliveries/{consignmentId}/proof/reversal")
    public ResponseEntity<ApprovalTicket> requestReversal(@PathVariable UUID consignmentId,
                                                          @Valid @RequestBody ReversalRequest request) {
        ApprovalTicket ticket = reversals.requestReversal(consignmentId, request.reason());
        if (ticket == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.accepted().location(URI.create("/api/v1/approvals/" + ticket.id())).body(ticket);
    }

    @GetMapping("/deliveries/{consignmentId}")
    public DeliveryProofView proof(@PathVariable UUID consignmentId) {
        return queries.proofOf(consignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Delivery proof", consignmentId));
    }

    // -------------------------------------------------------------- claims

    @PostMapping("/claims")
    public ResponseEntity<IdResponse> raiseClaim(@Valid @RequestBody RaiseClaimRequest request) {
        UUID id = claims.raise(request.consignmentId(), request.claimType(), request.amount(),
                request.description());
        return ResponseEntity.created(URI.create("/api/v1/claims/" + id)).body(new IdResponse(id));
    }

    @GetMapping("/claims")
    public Slice<ClaimView> listClaims(@RequestParam(required = false) String status,
                                       @RequestParam(required = false) UUID consignmentId,
                                       @RequestParam(required = false) String cursor,
                                       @RequestParam(required = false) Integer limit) {
        return queries.listClaims(status, consignmentId, cursor, limit);
    }

    @GetMapping("/claims/{id}")
    public ClaimView claim(@PathVariable UUID id) {
        return queries.findClaim(id).orElseThrow(() -> new ResourceNotFoundException("Claim", id));
    }

    @PostMapping("/claims/{id}/transitions")
    public ResponseEntity<Void> transition(@PathVariable UUID id,
                                           @Valid @RequestBody TransitionClaimRequest request) {
        claims.transition(id, request.target(), request.approvedAmount(), request.resolutionNote());
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- bodies

    /** The code is present only where the deployment exposes it (a demo). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OtpIssued(Instant expiresAt, String otp) {
    }

    public record RecordProofRequest(
            @NotBlank String otp,
            @NotBlank String recipientName,
            String photoRef,
            OffsetDateTime deliveredAt,
            @DecimalMin("-90") @DecimalMax("90") BigDecimal lat,
            @DecimalMin("-180") @DecimalMax("180") BigDecimal lon,
            /** Omitted is CLEAN. */
            GoodsCondition goodsCondition,
            String remark) {
    }

    public record PodLineRequest(
            @NotNull UUID orderLineId,
            @DecimalMin("0") BigDecimal receivedQty,
            @DecimalMin("0") BigDecimal shortQty,
            @DecimalMin("0") BigDecimal damagedQty,
            @DecimalMin("0") BigDecimal excessQty) {
    }

    public record FeedLinesRequest(@Valid @Size(max = 500) List<PodLineRequest> lines) {
    }

    public record PodDocumentRequest(@NotNull ProofDocumentType type, @NotBlank @Size(max = 500) String reference) {
    }

    public record SetDocumentsRequest(@Valid @Size(max = 20) List<PodDocumentRequest> documents) {
    }

    public record SetTimingsRequest(OffsetDateTime reportedAt, OffsetDateTime unloadingStartedAt,
                                    OffsetDateTime unloadingEndedAt) {
    }

    public record SubmitPodRequest(
            @NotBlank String otp,
            @NotBlank String recipientName,
            OffsetDateTime deliveredAt,
            @DecimalMin("-90") @DecimalMax("90") BigDecimal lat,
            @DecimalMin("-180") @DecimalMax("180") BigDecimal lon,
            GoodsCondition goodsCondition,
            String remark) {
    }

    public record PaperPodRequest(
            @NotBlank String recipientName,
            OffsetDateTime deliveredAt,
            @DecimalMin("-90") @DecimalMax("90") BigDecimal lat,
            @DecimalMin("-180") @DecimalMax("180") BigDecimal lon,
            GoodsCondition goodsCondition,
            String remark) {
    }

    public record NotReceivedRequest(@NotBlank @Size(max = 500) String remark) {
    }

    public record ReversalRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record RaiseClaimRequest(
            @NotNull UUID consignmentId,
            @NotNull ClaimType claimType,
            @NotNull BigDecimal amount,
            @NotBlank String description) {
    }

    public record TransitionClaimRequest(
            @NotNull ClaimStatus target,
            BigDecimal approvedAmount,
            String resolutionNote) {
    }
}
