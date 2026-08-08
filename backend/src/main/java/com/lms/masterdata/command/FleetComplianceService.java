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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.lms.masterdata.api.FleetCompliancePort;
import com.lms.masterdata.command.domain.ComplianceDocument;
import com.lms.masterdata.command.domain.Driver;
import com.lms.masterdata.command.domain.Vehicle;
import com.lms.shared.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements the dispatch hard stop of vision document 3.2.2.
 *
 * <p>Collects <em>every</em> blocking reason rather than returning on the first
 * one. An operator with an expired insurance certificate and a lapsed licence
 * should learn both at once; discovering them one failed dispatch at a time is
 * how a five-minute fix becomes an afternoon.
 */
@Service
@Transactional(readOnly = true)
public class FleetComplianceService implements FleetCompliancePort {

    private final VehicleRepository vehicles;
    private final DriverRepository drivers;
    private final ComplianceDocumentRepository documents;

    public FleetComplianceService(VehicleRepository vehicles, DriverRepository drivers,
                                  ComplianceDocumentRepository documents) {
        this.vehicles = vehicles;
        this.drivers = drivers;
        this.documents = documents;
    }

    @Override
    public ComplianceVerdict checkHazmatReadiness(UUID vehicleId, UUID driverId) {
        List<String> reasons = new ArrayList<>();

        vehicles.findById(vehicleId).ifPresentOrElse(vehicle -> {
            if (!vehicle.hazmatCertified()) {
                reasons.add("vehicle " + vehicle.registrationNo()
                        + " is not certified to carry dangerous goods");
            }
        }, () -> reasons.add("vehicle " + vehicleId + " does not exist"));

        if (driverId == null) {
            // Not a warning. Dangerous goods need a named, endorsed driver, and
            // "nobody assigned yet" is not a person who has been trained.
            reasons.add("no driver is assigned, and dangerous goods require an endorsed driver");
        } else {
            drivers.findById(driverId).ifPresentOrElse(driver -> {
                if (!driver.hazmatEndorsed()) {
                    reasons.add("driver " + driver.fullName()
                            + " is not endorsed to carry dangerous goods");
                }
            }, () -> reasons.add("driver " + driverId + " does not exist"));
        }

        return reasons.isEmpty() ? ComplianceVerdict.clear() : ComplianceVerdict.blocked(reasons);
    }

    @Override
    public ComplianceVerdict checkDispatchReadiness(UUID vehicleId, UUID driverId, LocalDate dispatchOn) {
        UUID tenantId = TenantContext.requireTenantId();
        List<String> reasons = new ArrayList<>();

        Vehicle vehicle = vehicles.findById(vehicleId).orElse(null);
        if (vehicle == null) {
            // Under row-level security a vehicle belonging to another tenant is
            // simply absent, which is the correct answer here too.
            reasons.add("Vehicle " + vehicleId + " does not exist");
        } else {
            if (vehicle.status() == Vehicle.VehicleStatus.RETIRED
                    || vehicle.status() == Vehicle.VehicleStatus.MAINTENANCE) {
                reasons.add("Vehicle " + vehicle.registrationNo() + " is " + vehicle.status());
            }
            for (ComplianceDocument expired : documents.findExpired(
                    tenantId, ComplianceDocument.OwnerType.VEHICLE.name(), vehicleId, dispatchOn)) {
                reasons.add("Vehicle " + vehicle.registrationNo() + " has an expired "
                        + expired.documentType() + " (expired " + expired.expiresOn() + ")");
            }
        }

        if (driverId != null) {
            Driver driver = drivers.findById(driverId).orElse(null);
            if (driver == null) {
                reasons.add("Driver " + driverId + " does not exist");
            } else {
                if (driver.licenceExpiresOn() != null && driver.licenceExpiresOn().isBefore(dispatchOn)) {
                    reasons.add("Driver " + driver.fullName() + " has a licence that expired on "
                            + driver.licenceExpiresOn());
                }
                if (driver.exceedsHoursOfService()) {
                    // Vision document 3.2.2: hours-of-service limits exist to
                    // prevent fatigue, so this blocks rather than warns.
                    reasons.add("Driver " + driver.fullName()
                            + " has exceeded the permitted hours of service");
                }
                for (ComplianceDocument expired : documents.findExpired(
                        tenantId, ComplianceDocument.OwnerType.DRIVER.name(), driverId, dispatchOn)) {
                    reasons.add("Driver " + driver.fullName() + " has an expired "
                            + expired.documentType() + " (expired " + expired.expiresOn() + ")");
                }
            }
        }

        return reasons.isEmpty() ? ComplianceVerdict.clear() : ComplianceVerdict.blocked(reasons);
    }
}
