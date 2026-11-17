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
import java.util.Optional;
import java.util.UUID;

import com.lms.masterdata.command.domain.BusinessPartner;
import com.lms.masterdata.command.domain.Driver;
import com.lms.masterdata.command.domain.Vehicle;
import com.lms.shared.audit.AuditTrail;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The maintenance rules of issue 238 without a database: versions, reasons, and what may be switched off. */
class MasterDataMaintenanceTest {

    private final BusinessPartnerRepository partners = mock(BusinessPartnerRepository.class);
    private final VehicleRepository vehicles = mock(VehicleRepository.class);
    private final DriverRepository drivers = mock(DriverRepository.class);
    private final AuditTrail audit = mock(AuditTrail.class);
    private MasterDataMaintenanceService service;

    private final UUID tenant = UUID.randomUUID();
    private final UUID unit = UUID.randomUUID();

    @BeforeEach
    void service() {
        service = new MasterDataMaintenanceService(partners, vehicles, drivers, audit);
        when(vehicles.save(any())).thenAnswer(call -> call.getArgument(0));
        when(drivers.save(any())).thenAnswer(call -> call.getArgument(0));
        when(partners.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    private Vehicle vehicle(Vehicle.VehicleStatus status, long version) {
        Vehicle fresh = Vehicle.register(UUID.randomUUID(), tenant, unit, null, "MH-01-UT-0001", "TRUCK",
                "TRUCK_20FT", null, BigDecimal.valueOf(18000), BigDecimal.valueOf(6000), BigDecimal.valueOf(30),
                null, null, null, false, false);
        return new Vehicle(fresh.id(), tenant, unit, null, fresh.registrationNo(), fresh.category(),
                fresh.vehicleType(), null, fresh.grossWeightKg(), fresh.tareWeightKg(), fresh.maxVolumeM3(),
                null, null, null, status, false, false, version, fresh.createdAt(), fresh.updatedAt());
    }

    private Driver driver(Driver.DriverStatus status, long version) {
        Driver fresh = Driver.register(UUID.randomUUID(), tenant, unit, null, "Asha", "9", "DL-1", "HMV", "RTO",
                LocalDate.now().plusYears(1), false);
        return new Driver(fresh.id(), tenant, unit, null, "Asha", "9", "DL-1", "HMV", "RTO",
                fresh.licenceExpiresOn(), false, status, 0, null, version, fresh.createdAt(), fresh.updatedAt());
    }

    @Test
    @DisplayName("an edit against a stale version is an optimistic-lock failure and writes nothing")
    void staleEditWritesNothing() {
        Vehicle current = vehicle(Vehicle.VehicleStatus.AVAILABLE, 3);
        when(vehicles.findById(current.id())).thenReturn(Optional.of(current));

        assertThatThrownBy(() -> service.updateVehicle(current.id(), 2, null, "TRUCK", "TRUCK_32FT", null,
                BigDecimal.valueOf(20000), BigDecimal.valueOf(6000), BigDecimal.valueOf(30), false, false))
                .isInstanceOf(OptimisticLockingFailureException.class);
        verify(vehicles, never()).save(any());
        verify(audit, never()).recordAtomically(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("an edit keeps the registration and sends the version it was read at to the save")
    void editKeepsIdentity() {
        Vehicle current = vehicle(Vehicle.VehicleStatus.AVAILABLE, 3);
        when(vehicles.findById(current.id())).thenReturn(Optional.of(current));

        service.updateVehicle(current.id(), 3, null, "TRUCK", "TRUCK_32FT", "3-axle",
                BigDecimal.valueOf(20000), BigDecimal.valueOf(7000), BigDecimal.valueOf(35), true, false);

        Vehicle edited = current.edited(null, "TRUCK", "TRUCK_32FT", "3-axle", BigDecimal.valueOf(20000),
                BigDecimal.valueOf(7000), BigDecimal.valueOf(35), true, false, 3L);
        assertThat(edited.registrationNo()).isEqualTo("MH-01-UT-0001");
        assertThat(edited.version()).isEqualTo(3L);
        assertThat(edited.status()).isEqualTo(Vehicle.VehicleStatus.AVAILABLE);
        verify(audit).recordAtomically(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("an edit whose tare is not below its gross is refused as invalid")
    void tareBelowGross() {
        assertThatThrownBy(() -> vehicle(Vehicle.VehicleStatus.AVAILABLE, 0).edited(null, "T", "T", null,
                BigDecimal.valueOf(5000), BigDecimal.valueOf(5000), BigDecimal.valueOf(1), false, false, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a vehicle is switched off with a reason, and only once, and not while on a trip")
    void vehicleDeactivation() {
        Vehicle available = vehicle(Vehicle.VehicleStatus.AVAILABLE, 1);
        when(vehicles.findById(available.id())).thenReturn(Optional.of(available));
        assertThatThrownBy(() -> service.deactivateVehicle(available.id(), null, " "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reason");

        service.deactivateVehicle(available.id(), 1L, "sold");
        verify(vehicles).save(any(Vehicle.class));

        Vehicle off = vehicle(Vehicle.VehicleStatus.INACTIVE, 2);
        when(vehicles.findById(off.id())).thenReturn(Optional.of(off));
        assertThatThrownBy(() -> service.deactivateVehicle(off.id(), null, "again"))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo("vehicle-already-inactive"));

        Vehicle moving = vehicle(Vehicle.VehicleStatus.ON_TRIP, 2);
        when(vehicles.findById(moving.id())).thenReturn(Optional.of(moving));
        assertThatThrownBy(() -> service.deactivateVehicle(moving.id(), null, "scrap"))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo("vehicle-on-trip"));
    }

    @Test
    @DisplayName("only an inactive vehicle or driver can be switched back on")
    void reactivation() {
        Vehicle available = vehicle(Vehicle.VehicleStatus.AVAILABLE, 1);
        when(vehicles.findById(available.id())).thenReturn(Optional.of(available));
        assertThatThrownBy(() -> service.reactivateVehicle(available.id(), null, "why"))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo("vehicle-not-inactive"));

        Driver working = driver(Driver.DriverStatus.AVAILABLE, 1);
        when(drivers.findById(working.id())).thenReturn(Optional.of(working));
        assertThatThrownBy(() -> service.reactivateDriver(working.id(), null, "why"))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo("driver-not-inactive"));

        Driver off = driver(Driver.DriverStatus.INACTIVE, 4);
        when(drivers.findById(off.id())).thenReturn(Optional.of(off));
        service.reactivateDriver(off.id(), 4L, "rejoined");
        verify(drivers).save(any(Driver.class));
    }

    @Test
    @DisplayName("a driver on a trip cannot be switched off, and a stale transition is refused")
    void driverGuards() {
        Driver moving = driver(Driver.DriverStatus.ON_TRIP, 1);
        when(drivers.findById(moving.id())).thenReturn(Optional.of(moving));
        assertThatThrownBy(() -> service.deactivateDriver(moving.id(), null, "leave"))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo("driver-on-trip"));
        assertThatThrownBy(() -> service.deactivateDriver(moving.id(), 0L, "leave"))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    @DisplayName("an unknown record is not found")
    void unknown() {
        UUID id = UUID.randomUUID();
        when(vehicles.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deactivateVehicle(id, null, "x")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("a partner needs a reason to be suspended, blacklisted or reactivated, but not to move forward in KYC")
    void partnerReasons() {
        BusinessPartner draft = BusinessPartner.register(UUID.randomUUID(), tenant, unit, "P-1", "Partner One",
                BusinessPartner.PartnerType.CUSTOMER, null);
        when(partners.findById(draft.id())).thenReturn(Optional.of(draft));

        service.transitionPartner(draft.id(), BusinessPartner.PartnerStatus.PENDING_VERIFICATION, null, null);

        assertThatThrownBy(() -> service.transitionPartner(draft.id(), BusinessPartner.PartnerStatus.BLACKLISTED, " ", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.transitionPartner(draft.id(), BusinessPartner.PartnerStatus.SUSPENDED, null, null))
                .isInstanceOf(IllegalArgumentException.class);

        BusinessPartner suspended = new BusinessPartner(draft.id(), tenant, unit, "P-1", "Partner One",
                BusinessPartner.PartnerType.CUSTOMER, BusinessPartner.PartnerStatus.SUSPENDED, null, null, null,
                null, null, null, null, null, 2L, draft.createdAt(), draft.updatedAt());
        when(partners.findById(draft.id())).thenReturn(Optional.of(suspended));
        assertThatThrownBy(() -> service.transitionPartner(draft.id(), BusinessPartner.PartnerStatus.ACTIVE, "", null))
                .isInstanceOf(IllegalArgumentException.class);
        service.transitionPartner(draft.id(), BusinessPartner.PartnerStatus.ACTIVE, "review passed", 2L);
    }

    @Test
    @DisplayName("a partner edit changes its descriptive fields only, and needs a name")
    void partnerEditFields() {
        BusinessPartner partner = BusinessPartner.register(UUID.randomUUID(), tenant, unit, "P-2", "Old",
                BusinessPartner.PartnerType.VENDOR, "T");
        BusinessPartner edited = partner.edited(" New name ", " ", "NET30", null);
        assertThat(edited.legalName()).isEqualTo("New name");
        assertThat(edited.taxId()).isNull();
        assertThat(edited.paymentTerms()).isEqualTo("NET30");
        assertThat(edited.code()).isEqualTo("P-2");
        assertThat(edited.partnerType()).isEqualTo(BusinessPartner.PartnerType.VENDOR);
        assertThatThrownBy(() -> partner.edited(" ", null, null, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
