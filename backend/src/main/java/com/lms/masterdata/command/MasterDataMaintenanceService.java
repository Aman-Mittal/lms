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
import java.time.LocalDate;
import java.util.UUID;

import com.lms.masterdata.command.domain.BusinessPartner;
import com.lms.masterdata.command.domain.Driver;
import com.lms.masterdata.command.domain.Vehicle;
import com.lms.shared.audit.AuditTrail;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Editing, switching off and switching back on the partner, vehicle and driver
 * registers.
 *
 * <p>Edits are guarded on the version the caller read: a stale version is a 409
 * {@code concurrent-modification}, never a silent overwrite. Nothing here
 * deletes. A vehicle or driver is made INACTIVE with a reason, which is audited,
 * and trips, loads and bills keep pointing at the row; the assignment paths
 * refuse an inactive one ({@code vehicle-inactive}, {@code driver-inactive}).
 */
@Service
public class MasterDataMaintenanceService {

    private final BusinessPartnerRepository partners;
    private final VehicleRepository vehicles;
    private final DriverRepository drivers;
    private final AuditTrail audit;

    public MasterDataMaintenanceService(BusinessPartnerRepository partners, VehicleRepository vehicles,
                                        DriverRepository drivers, AuditTrail audit) {
        this.partners = partners;
        this.vehicles = vehicles;
        this.drivers = drivers;
        this.audit = audit;
    }

    // -------------------------------------------------------------- partners

    /** Edits the partner's name, tax id, payment terms and billing address. */
    @Transactional
    @PreAuthorize("hasAuthority('PARTNER_UPDATE')")
    public void updatePartner(UUID id, long expectedVersion, String legalName, String taxId,
                              String paymentTerms, String billingAddress) {
        BusinessPartner before = partners.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Business partner", id));
        requireVersion(before.version(), expectedVersion, "Partner " + before.code());
        BusinessPartner edited = partners.save(
                before.edited(legalName, taxId, paymentTerms, billingAddress));
        audit.recordAtomically("PARTNER_UPDATED", "BUSINESS_PARTNER", id, snapshot(before), snapshot(edited));
    }

    /**
     * Moves a partner along its lifecycle and records why. A reason is mandatory
     * for the moves that take a partner out of (or back into) trade: SUSPENDED
     * (deactivate), BLACKLISTED, and ACTIVE from SUSPENDED (reactivate). The
     * forward KYC steps need none. {@code expectedVersion} is optional here; a
     * console sends it so a transition cannot be applied to a record somebody
     * else has just changed.
     */
    @Transactional
    @PreAuthorize("hasAuthority('PARTNER_UPDATE')")
    public void transitionPartner(UUID id, BusinessPartner.PartnerStatus target, String reason,
                                  Long expectedVersion) {
        BusinessPartner before = partners.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Business partner", id));
        requireVersionIfGiven(before.version(), expectedVersion, "Partner " + before.code());
        boolean needsReason = target == BusinessPartner.PartnerStatus.SUSPENDED
                || target == BusinessPartner.PartnerStatus.BLACKLISTED
                || (target == BusinessPartner.PartnerStatus.ACTIVE
                && before.status() == BusinessPartner.PartnerStatus.SUSPENDED);
        if (needsReason) {
            requireReason(reason);
        }
        BusinessPartner saved = partners.save(before.transitionTo(target));
        audit.recordAtomically("PARTNER_" + target.name(), "BUSINESS_PARTNER", id,
                snapshot(before, null), snapshot(saved, reason));
    }

    private static Json snapshot(BusinessPartner p) {
        return snapshot(p, null);
    }

    private static Json snapshot(BusinessPartner p, String reason) {
        return Json.object("legalName", p.legalName(), "taxId", p.taxId(),
                "paymentTerms", p.paymentTerms(), "billingAddress", p.billingAddress(),
                "status", p.status().name(), "reason", reason);
    }

    // -------------------------------------------------------------- vehicles

    @Transactional
    @PreAuthorize("hasAuthority('VEHICLE_UPDATE')")
    public void updateVehicle(UUID id, long expectedVersion, UUID ownerPartnerId, String category,
                              String vehicleType, String axleConfig, BigDecimal grossWeightKg,
                              BigDecimal tareWeightKg, BigDecimal maxVolumeM3,
                              boolean hazmatCertified, boolean reeferCapable) {
        Vehicle before = vehicles.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle", id));
        requireVersion(before.version(), expectedVersion, "Vehicle " + before.registrationNo());
        if (ownerPartnerId != null && !ownerPartnerId.equals(before.ownerPartnerId())) {
            BusinessPartner owner = partners.findById(ownerPartnerId)
                    .orElseThrow(() -> new ResourceNotFoundException("Business partner", ownerPartnerId));
            if (!owner.canTransact()) {
                throw new BusinessRuleViolationException("partner-not-transactable",
                        "Partner " + owner.code() + " is " + owner.status()
                                + " and cannot be assigned vehicles");
            }
        }
        Vehicle edited = vehicles.save(before.edited(ownerPartnerId, category, vehicleType, axleConfig,
                grossWeightKg, tareWeightKg, maxVolumeM3, hazmatCertified, reeferCapable, expectedVersion));
        audit.recordAtomically("VEHICLE_UPDATED", "VEHICLE", id, snapshot(before), snapshot(edited));
    }

    @Transactional
    @PreAuthorize("hasAuthority('VEHICLE_UPDATE')")
    public void deactivateVehicle(UUID id, Long expectedVersion, String reason) {
        requireReason(reason);
        Vehicle before = vehicles.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle", id));
        requireVersionIfGiven(before.version(), expectedVersion, "Vehicle " + before.registrationNo());
        if (before.isInactive()) {
            throw new BusinessRuleViolationException("vehicle-already-inactive",
                    "Vehicle " + before.registrationNo() + " is already inactive");
        }
        if (before.status() == Vehicle.VehicleStatus.ON_TRIP) {
            throw new BusinessRuleViolationException("vehicle-on-trip",
                    "Vehicle " + before.registrationNo() + " is on a trip and cannot be deactivated");
        }
        Vehicle saved = vehicles.save(before.withStatus(Vehicle.VehicleStatus.INACTIVE));
        audit.recordAtomically("VEHICLE_DEACTIVATED", "VEHICLE", id, snapshot(before),
                snapshot(saved, reason));
    }

    @Transactional
    @PreAuthorize("hasAuthority('VEHICLE_UPDATE')")
    public void reactivateVehicle(UUID id, Long expectedVersion, String reason) {
        requireReason(reason);
        Vehicle before = vehicles.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle", id));
        requireVersionIfGiven(before.version(), expectedVersion, "Vehicle " + before.registrationNo());
        if (!before.isInactive()) {
            throw new BusinessRuleViolationException("vehicle-not-inactive",
                    "Vehicle " + before.registrationNo() + " is " + before.status()
                            + ", not inactive");
        }
        Vehicle saved = vehicles.save(before.withStatus(Vehicle.VehicleStatus.AVAILABLE));
        audit.recordAtomically("VEHICLE_REACTIVATED", "VEHICLE", id, snapshot(before),
                snapshot(saved, reason));
    }

    private static Json snapshot(Vehicle v) {
        return snapshot(v, null);
    }

    private static Json snapshot(Vehicle v, String reason) {
        return Json.object("registrationNo", v.registrationNo(), "category", v.category(),
                "vehicleType", v.vehicleType(), "axleConfig", v.axleConfig(),
                "grossWeightKg", v.grossWeightKg().toPlainString(),
                "tareWeightKg", v.tareWeightKg().toPlainString(),
                "maxVolumeM3", v.maxVolumeM3().toPlainString(),
                "hazmatCertified", String.valueOf(v.hazmatCertified()),
                "reeferCapable", String.valueOf(v.reeferCapable()),
                "ownerPartnerId", v.ownerPartnerId() == null ? null : v.ownerPartnerId().toString(),
                "status", v.status().name(), "reason", reason);
    }

    // --------------------------------------------------------------- drivers

    @Transactional
    @PreAuthorize("hasAuthority('DRIVER_UPDATE')")
    public void updateDriver(UUID id, long expectedVersion, UUID employerPartnerId, String fullName,
                             String phone, String licenceClass, String licenceAuthority,
                             LocalDate licenceExpiresOn, boolean hazmatEndorsed) {
        Driver before = drivers.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Driver", id));
        requireVersion(before.version(), expectedVersion, "Driver " + before.fullName());
        Driver edited = drivers.save(before.edited(employerPartnerId, fullName, phone, licenceClass,
                licenceAuthority, licenceExpiresOn, hazmatEndorsed, expectedVersion));
        audit.recordAtomically("DRIVER_UPDATED", "DRIVER", id, snapshot(before, null), snapshot(edited, null));
    }

    @Transactional
    @PreAuthorize("hasAuthority('DRIVER_UPDATE')")
    public void deactivateDriver(UUID id, Long expectedVersion, String reason) {
        requireReason(reason);
        Driver before = drivers.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Driver", id));
        requireVersionIfGiven(before.version(), expectedVersion, "Driver " + before.fullName());
        if (before.isInactive()) {
            throw new BusinessRuleViolationException("driver-already-inactive",
                    "Driver " + before.fullName() + " is already inactive");
        }
        if (before.status() == Driver.DriverStatus.ON_TRIP) {
            throw new BusinessRuleViolationException("driver-on-trip",
                    "Driver " + before.fullName() + " is on a trip and cannot be deactivated");
        }
        Driver saved = drivers.save(before.withStatus(Driver.DriverStatus.INACTIVE));
        audit.recordAtomically("DRIVER_DEACTIVATED", "DRIVER", id, snapshot(before, null), snapshot(saved, reason));
    }

    @Transactional
    @PreAuthorize("hasAuthority('DRIVER_UPDATE')")
    public void reactivateDriver(UUID id, Long expectedVersion, String reason) {
        requireReason(reason);
        Driver before = drivers.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Driver", id));
        requireVersionIfGiven(before.version(), expectedVersion, "Driver " + before.fullName());
        if (!before.isInactive()) {
            throw new BusinessRuleViolationException("driver-not-inactive",
                    "Driver " + before.fullName() + " is " + before.status() + ", not inactive");
        }
        Driver saved = drivers.save(before.withStatus(Driver.DriverStatus.AVAILABLE));
        audit.recordAtomically("DRIVER_REACTIVATED", "DRIVER", id, snapshot(before, null), snapshot(saved, reason));
    }

    private static Json snapshot(Driver d, String reason) {
        return Json.object("fullName", d.fullName(), "phone", d.phone(), "licenceNo", d.licenceNo(),
                "licenceClass", d.licenceClass(), "licenceAuthority", d.licenceAuthority(),
                "licenceExpiresOn", d.licenceExpiresOn() == null ? null : d.licenceExpiresOn().toString(),
                "hazmatEndorsed", String.valueOf(d.hazmatEndorsed()),
                "employerPartnerId", d.employerPartnerId() == null ? null : d.employerPartnerId().toString(),
                "status", d.status().name(), "reason", reason);
    }

    // ---------------------------------------------------------------- shared

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required");
        }
    }

    private static void requireVersion(Long actual, long expected, String what) {
        if (actual == null || actual != expected) {
            throw new OptimisticLockingFailureException(what + " changed since it was read");
        }
    }

    private static void requireVersionIfGiven(Long actual, Long expected, String what) {
        if (expected != null) {
            requireVersion(actual, expected, what);
        }
    }
}
