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
 */package com.lms.masterdata.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.lms.masterdata.command.FleetCommandService;
import com.lms.masterdata.command.MasterDataMaintenanceService;
import com.lms.masterdata.command.domain.BusinessPartner;
import com.lms.masterdata.query.ComplianceDocumentView;
import com.lms.masterdata.query.MasterDataQueryService;
import com.lms.masterdata.query.PartnerView;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.query.Slice;
import com.lms.shared.web.IdResponse;
import com.lms.shared.web.IfMatch;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Trading partners (vision document 3.2.1).
 *
 * <p>No {@code @PreAuthorize} here, and that is deliberate throughout the web
 * layer. Every command and query service already carries its own, and a second
 * copy on the controller would be a second place to keep in step -- which is
 * how an endpoint ends up guarded by a permission the service it calls does not
 * check, or the reverse. The controller's job is HTTP: bind, delegate, choose a
 * status code.
 */
@RestController
@RequestMapping("/api/v1/partners")
public class PartnerController {

    private final FleetCommandService fleet;
    private final MasterDataQueryService masterData;
    private final MasterDataMaintenanceService maintenance;

    public PartnerController(FleetCommandService fleet, MasterDataQueryService masterData,
                             MasterDataMaintenanceService maintenance) {
        this.fleet = fleet;
        this.masterData = masterData;
        this.maintenance = maintenance;
    }

    @GetMapping
    public Slice<PartnerView> list(@RequestParam(required = false) String partnerType,
                                   @RequestParam(required = false) String status,
                                   @RequestParam(required = false) String cursor,
                                   @RequestParam(required = false) Integer limit,
                                   @RequestParam(required = false) String q) {
        return masterData.listPartners(partnerType, status, cursor, limit, q);
    }

    @GetMapping("/{id}")
    public ResponseEntity<PartnerView> get(@PathVariable UUID id) {
        PartnerView view = masterData.findPartner(id)
                .orElseThrow(() -> new ResourceNotFoundException("BusinessPartner", id));
        return ResponseEntity.ok().eTag(IfMatch.etag(view.version())).body(view);
    }

    /**
     * Edits the descriptive fields. The code, unit and type are fixed, and the
     * status moves only through {@code /transitions}. {@code If-Match} carries
     * the version read; a stale one is 409 {@code concurrent-modification}.
     */
    @PutMapping("/{id}")
    public ResponseEntity<Void> update(@PathVariable UUID id,
                                       @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                       @Valid @RequestBody UpdatePartnerRequest request) {
        maintenance.updatePartner(id, IfMatch.version(ifMatch, "the partner's version"),
                request.legalName(), request.taxId(), request.paymentTerms(), request.billingAddress());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/documents")
    public List<ComplianceDocumentView> documents(@PathVariable UUID id) {
        return masterData.documentsFor("PARTNER", id);
    }

    @PostMapping
    public ResponseEntity<IdResponse> register(@Valid @RequestBody RegisterPartnerRequest request) {
        UUID id = fleet.registerPartner(request.orgUnitId(), request.code(), request.legalName(),
                request.partnerType(), request.taxId());
        return ResponseEntity.created(URI.create("/api/v1/partners/" + id))
                .body(new IdResponse(id));
    }

    /**
     * Moves a partner along its KYC lifecycle.
     *
     * <p>A POST to a named sub-resource rather than a PATCH of the status
     * field. The transitions are a state machine with rules -- a blacklisted
     * partner does not become active because somebody sent a different string
     * -- and modelling them as a field invites clients to treat them as one.
     */
    @PostMapping("/{id}/transitions")
    public ResponseEntity<Void> transition(@PathVariable UUID id,
                                           @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                           @Valid @RequestBody TransitionRequest request) {
        maintenance.transitionPartner(id, request.target(), request.reason(),
                ifMatch == null || ifMatch.isBlank() ? null : IfMatch.version(ifMatch, "the partner's version"));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/documents")
    public ResponseEntity<IdResponse> attachDocument(
            @PathVariable UUID id, @Valid @RequestBody AttachDocumentRequest request) {
        UUID documentId = fleet.attachDocument(
                com.lms.masterdata.command.domain.ComplianceDocument.OwnerType.PARTNER, id,
                request.documentType(), request.documentNo(), request.issuingAuthority(),
                request.issuedOn(), request.expiresOn());
        return ResponseEntity.created(URI.create("/api/v1/partners/" + id + "/documents"))
                .body(new IdResponse(documentId));
    }

    public record RegisterPartnerRequest(
            @NotNull UUID orgUnitId,
            @NotBlank String code,
            @NotBlank String legalName,
            @NotNull BusinessPartner.PartnerType partnerType,
            String taxId) {
    }

    /** {@code reason} is mandatory for SUSPENDED, BLACKLISTED and reactivation (ACTIVE from SUSPENDED). */
    public record TransitionRequest(@NotNull BusinessPartner.PartnerStatus target, String reason) {
    }

    public record UpdatePartnerRequest(
            @NotBlank String legalName,
            String taxId,
            String paymentTerms,
            String billingAddress) {
    }
}
