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
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lms.delivery.command.ClaimService;
import com.lms.delivery.command.DeliveryService;
import com.lms.delivery.command.domain.ClaimStatus;
import com.lms.delivery.command.domain.ClaimType;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    public DeliveryController(DeliveryService delivery, ClaimService claims,
                              DeliveryQueryService queries) {
        this.delivery = delivery;
        this.claims = claims;
        this.queries = queries;
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
                request.photoRef(), request.deliveredAt(), request.lat(), request.lon());
        return ResponseEntity.noContent().build();
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
            @DecimalMin("-180") @DecimalMax("180") BigDecimal lon) {
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
