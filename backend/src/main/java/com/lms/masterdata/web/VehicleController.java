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

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.lms.masterdata.command.FleetCommandService;
import com.lms.masterdata.command.MasterDataMaintenanceService;
import com.lms.masterdata.command.domain.ComplianceDocument;
import com.lms.masterdata.query.ComplianceDocumentView;
import com.lms.masterdata.query.MasterDataQueryService;
import com.lms.masterdata.query.VehicleView;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.query.Slice;
import com.lms.shared.web.IdResponse;
import com.lms.shared.web.IfMatch;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
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

/** Vehicles and their statutory documents (vision document 3.2.2). */
@RestController
@RequestMapping("/api/v1/vehicles")
public class VehicleController {

    private final FleetCommandService fleet;
    private final MasterDataQueryService masterData;
    private final MasterDataMaintenanceService maintenance;

    public VehicleController(FleetCommandService fleet, MasterDataQueryService masterData,
                             MasterDataMaintenanceService maintenance) {
        this.fleet = fleet;
        this.masterData = masterData;
        this.maintenance = maintenance;
    }

    @GetMapping
    public Slice<VehicleView> list(@RequestParam(required = false) String status,
                                   @RequestParam(required = false) String cursor,
                                   @RequestParam(required = false) Integer limit,
                                   @RequestParam(required = false) String q) {
        return masterData.listVehicles(status, cursor, limit, q);
    }

    @GetMapping("/{id}")
    public ResponseEntity<VehicleView> get(@PathVariable UUID id) {
        VehicleView view = masterData.findVehicle(id)
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle", id));
        return ResponseEntity.ok().eTag(IfMatch.etag(view.version())).body(view);
    }

    /** Edits everything but the registration number; a stale {@code If-Match} is a 409. */
    @PutMapping("/{id}")
    public ResponseEntity<Void> update(@PathVariable UUID id,
                                       @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                       @Valid @RequestBody UpdateVehicleRequest request) {
        maintenance.updateVehicle(id, IfMatch.version(ifMatch, "the vehicle's version"),
                request.ownerPartnerId(), request.category(), request.vehicleType(), request.axleConfig(),
                request.grossWeightKg(), request.tareWeightKg(), request.maxVolumeM3(),
                request.hazmatCertified(), request.reeferCapable());
        return ResponseEntity.noContent().build();
    }

    /** Switches the vehicle off, with a reason. There is no delete. */
    @PostMapping("/{id}/deactivate")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id,
                                           @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                           @Valid @RequestBody ReasonRequest request) {
        maintenance.deactivateVehicle(id, optionalVersion(ifMatch), request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reactivate")
    public ResponseEntity<Void> reactivate(@PathVariable UUID id,
                                           @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                           @Valid @RequestBody ReasonRequest request) {
        maintenance.reactivateVehicle(id, optionalVersion(ifMatch), request.reason());
        return ResponseEntity.noContent().build();
    }

    private static Long optionalVersion(String ifMatch) {
        return ifMatch == null || ifMatch.isBlank() ? null : IfMatch.version(ifMatch, "the vehicle's version");
    }

    @GetMapping("/{id}/documents")
    public List<ComplianceDocumentView> documents(@PathVariable UUID id) {
        return masterData.documentsFor("VEHICLE", id);
    }

    @PostMapping
    public ResponseEntity<IdResponse> register(@Valid @RequestBody RegisterVehicleRequest request) {
        UUID id = fleet.registerVehicle(request.orgUnitId(), request.ownerPartnerId(),
                request.registrationNo(), request.category(), request.vehicleType(),
                request.axleConfig(), request.grossWeightKg(), request.tareWeightKg(),
                request.maxVolumeM3(), request.hazmatCertified(), request.reeferCapable());
        return ResponseEntity.created(URI.create("/api/v1/vehicles/" + id))
                .body(new IdResponse(id));
    }

    @PostMapping("/{id}/documents")
    public ResponseEntity<IdResponse> attachDocument(
            @PathVariable UUID id, @Valid @RequestBody AttachDocumentRequest request) {
        UUID documentId = fleet.attachDocument(ComplianceDocument.OwnerType.VEHICLE, id,
                request.documentType(), request.documentNo(), request.issuingAuthority(),
                request.issuedOn(), request.expiresOn());
        return ResponseEntity.created(URI.create("/api/v1/vehicles/" + id + "/documents"))
                .body(new IdResponse(documentId));
    }

    public record UpdateVehicleRequest(
            UUID ownerPartnerId,
            @NotBlank String category,
            @NotBlank String vehicleType,
            String axleConfig,
            @NotNull @Positive BigDecimal grossWeightKg,
            @NotNull @Positive BigDecimal tareWeightKg,
            @NotNull @Positive BigDecimal maxVolumeM3,
            boolean hazmatCertified,
            boolean reeferCapable) {
    }

    public record RegisterVehicleRequest(
            @NotNull UUID orgUnitId,
            UUID ownerPartnerId,
            @NotBlank String registrationNo,
            @NotBlank String category,
            @NotBlank String vehicleType,
            String axleConfig,
            @NotNull @Positive BigDecimal grossWeightKg,
            @NotNull @Positive BigDecimal tareWeightKg,
            @Positive BigDecimal maxVolumeM3,
            boolean hazmatCertified,
            boolean reeferCapable) {
    }
}
