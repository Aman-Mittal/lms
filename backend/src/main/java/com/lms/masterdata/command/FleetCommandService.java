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
package com.lms.masterdata.command;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.lms.masterdata.command.domain.BusinessPartner;
import com.lms.masterdata.command.domain.ComplianceDocument;
import com.lms.masterdata.command.domain.Driver;
import com.lms.masterdata.command.domain.Vehicle;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Write side for vehicles, drivers, partners and their statutory documents
 * (vision document 3.2.1 and 3.2.2).
 */
@Service
public class FleetCommandService {

    private final VehicleRepository vehicles;
    private final DriverRepository drivers;
    private final BusinessPartnerRepository partners;
    private final ComplianceDocumentRepository documents;

    public FleetCommandService(VehicleRepository vehicles, DriverRepository drivers,
                               BusinessPartnerRepository partners,
                               ComplianceDocumentRepository documents) {
        this.vehicles = vehicles;
        this.drivers = drivers;
        this.partners = partners;
        this.documents = documents;
    }

    @Transactional
    @PreAuthorize("hasAuthority('VEHICLE_CREATE')")
    public UUID registerVehicle(UUID orgUnitId, UUID ownerPartnerId, String registrationNo,
                                String category, String vehicleType, String axleConfig,
                                BigDecimal grossWeightKg, BigDecimal tareWeightKg, BigDecimal maxVolumeM3,
                                boolean hazmatCertified, boolean reeferCapable) {
        UUID tenantId = TenantContext.requireTenantId();

        vehicles.findByRegistration(tenantId, registrationNo).ifPresent(existing -> {
            throw new BusinessRuleViolationException("vehicle-registration-taken",
                    "A vehicle with registration " + registrationNo + " already exists");
        });

        if (ownerPartnerId != null) {
            BusinessPartner owner = partners.findById(ownerPartnerId)
                    .orElseThrow(() -> new ResourceNotFoundException("Business partner", ownerPartnerId));
            // 3.2.1: no work may be given to a partner that is not ACTIVE, and
            // that includes attaching assets to them.
            if (!owner.canTransact()) {
                throw new BusinessRuleViolationException("partner-not-transactable",
                        "Partner " + owner.code() + " is " + owner.status()
                                + " and cannot be assigned vehicles");
            }
        }

        return vehicles.save(Vehicle.register(UUID.randomUUID(), tenantId, orgUnitId, ownerPartnerId,
                registrationNo, category, vehicleType, axleConfig, grossWeightKg, tareWeightKg,
                maxVolumeM3, null, null, null, hazmatCertified, reeferCapable)).id();
    }

    @Transactional
    @PreAuthorize("hasAuthority('DRIVER_CREATE')")
    public UUID registerDriver(UUID orgUnitId, UUID employerPartnerId, String fullName, String phone,
                               String licenceNo, String licenceClass, String licenceAuthority,
                               LocalDate licenceExpiresOn, boolean hazmatEndorsed) {
        UUID tenantId = TenantContext.requireTenantId();

        drivers.findByLicence(tenantId, licenceNo).ifPresent(existing -> {
            throw new BusinessRuleViolationException("driver-licence-taken",
                    "A driver with licence " + licenceNo + " already exists");
        });

        return drivers.save(Driver.register(UUID.randomUUID(), tenantId, orgUnitId, employerPartnerId,
                fullName, phone, licenceNo, licenceClass, licenceAuthority,
                licenceExpiresOn, hazmatEndorsed)).id();
    }

    @Transactional
    @PreAuthorize("hasAuthority('PARTNER_CREATE')")
    public UUID registerPartner(UUID orgUnitId, String code, String legalName,
                                BusinessPartner.PartnerType type, String taxId) {
        UUID tenantId = TenantContext.requireTenantId();

        partners.findByCode(tenantId, code).ifPresent(existing -> {
            throw new BusinessRuleViolationException("partner-code-taken",
                    "A partner with code " + code + " already exists");
        });

        return partners.save(BusinessPartner.register(
                UUID.randomUUID(), tenantId, orgUnitId, code, legalName, type, taxId)).id();
    }

    @Transactional
    @PreAuthorize("hasAuthority('PARTNER_UPDATE')")
    public void transitionPartner(UUID partnerId, BusinessPartner.PartnerStatus target) {
        BusinessPartner partner = partners.findById(partnerId)
                .orElseThrow(() -> new ResourceNotFoundException("Business partner", partnerId));
        partners.save(partner.transitionTo(target));
    }

    @Transactional
    @PreAuthorize("hasAuthority('VEHICLE_UPDATE')")
    public UUID attachDocument(ComplianceDocument.OwnerType ownerType, UUID ownerId,
                               String documentType, String documentNo, String issuingAuthority,
                               LocalDate issuedOn, LocalDate expiresOn) {
        UUID tenantId = TenantContext.requireTenantId();

        return documents.save(new ComplianceDocument(UUID.randomUUID(), tenantId, ownerType, ownerId,
                documentType, documentNo, issuingAuthority, issuedOn, expiresOn, null,
                null, Instant.now())).id();
    }
}
