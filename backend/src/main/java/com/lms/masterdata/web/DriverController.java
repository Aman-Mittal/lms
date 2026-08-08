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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.lms.masterdata.command.FleetCommandService;
import com.lms.masterdata.command.domain.ComplianceDocument;
import com.lms.masterdata.query.ComplianceDocumentView;
import com.lms.masterdata.query.DriverView;
import com.lms.masterdata.query.MasterDataQueryService;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.query.Slice;
import com.lms.shared.web.IdResponse;
import jakarta.validation.Valid;
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

/** Drivers and their licences (vision document 3.2.2). */
@RestController
@RequestMapping("/api/v1/drivers")
public class DriverController {

    private final FleetCommandService fleet;
    private final MasterDataQueryService masterData;

    public DriverController(FleetCommandService fleet, MasterDataQueryService masterData) {
        this.fleet = fleet;
        this.masterData = masterData;
    }

    @GetMapping
    public Slice<DriverView> list(@RequestParam(required = false) String status,
                                  @RequestParam(required = false) String cursor,
                                  @RequestParam(required = false) Integer limit) {
        return masterData.listDrivers(status, cursor, limit);
    }

    @GetMapping("/{id}")
    public DriverView get(@PathVariable UUID id) {
        return masterData.findDriver(id)
                .orElseThrow(() -> new ResourceNotFoundException("Driver", id));
    }

    @GetMapping("/{id}/documents")
    public List<ComplianceDocumentView> documents(@PathVariable UUID id) {
        return masterData.documentsFor("DRIVER", id);
    }

    @PostMapping
    public ResponseEntity<IdResponse> register(@Valid @RequestBody RegisterDriverRequest request) {
        UUID id = fleet.registerDriver(request.orgUnitId(), request.employerPartnerId(),
                request.fullName(), request.phone(), request.licenceNo(), request.licenceClass(),
                request.licenceAuthority(), request.licenceExpiresOn(), request.hazmatEndorsed());
        return ResponseEntity.created(URI.create("/api/v1/drivers/" + id))
                .body(new IdResponse(id));
    }

    @PostMapping("/{id}/documents")
    public ResponseEntity<IdResponse> attachDocument(
            @PathVariable UUID id, @Valid @RequestBody AttachDocumentRequest request) {
        UUID documentId = fleet.attachDocument(ComplianceDocument.OwnerType.DRIVER, id,
                request.documentType(), request.documentNo(), request.issuingAuthority(),
                request.issuedOn(), request.expiresOn());
        return ResponseEntity.created(URI.create("/api/v1/drivers/" + id + "/documents"))
                .body(new IdResponse(documentId));
    }

    public record RegisterDriverRequest(
            @NotNull UUID orgUnitId,
            UUID employerPartnerId,
            @NotBlank String fullName,
            @NotBlank String phone,
            @NotBlank String licenceNo,
            @NotBlank String licenceClass,
            String licenceAuthority,
            @NotNull LocalDate licenceExpiresOn,
            boolean hazmatEndorsed) {
    }
}
