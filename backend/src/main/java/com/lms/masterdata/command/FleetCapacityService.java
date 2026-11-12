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

import java.util.Optional;
import java.util.UUID;

import com.lms.masterdata.api.FleetCapacityPort;
import com.lms.masterdata.command.domain.Vehicle;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Serves {@link FleetCapacityPort} from the vehicle register. */
@Service
@Transactional(readOnly = true)
public class FleetCapacityService implements FleetCapacityPort {

    private final VehicleRepository vehicles;
    private final DriverRepository drivers;

    public FleetCapacityService(VehicleRepository vehicles, DriverRepository drivers) {
        this.vehicles = vehicles;
        this.drivers = drivers;
    }

    @Override
    public Optional<String> registrationOf(UUID vehicleId) {
        return vehicleId == null ? Optional.empty()
                : vehicles.findById(vehicleId).map(Vehicle::registrationNo);
    }

    @Override
    public boolean isDriverRegistered(UUID driverId) {
        return driverId != null && drivers.findById(driverId).isPresent();
    }

    @Override
    public boolean isRegistered(UUID vehicleId) {
        return vehicleId != null && vehicles.findById(vehicleId).isPresent();
    }

    @Override
    public Optional<VehicleCapacity> capacityOf(UUID vehicleId) {
        return vehicles.findById(vehicleId)
                .filter(Vehicle::isAvailable)
                .map(vehicle -> new VehicleCapacity(vehicle.id(), vehicle.registrationNo(),
                        vehicle.vehicleType(), vehicle.payloadCapacityKg(), vehicle.maxVolumeM3(),
                        vehicle.hazmatCertified(), vehicle.reeferCapable()));
    }
}
