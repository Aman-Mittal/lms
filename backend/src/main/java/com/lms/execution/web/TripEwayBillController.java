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
package com.lms.execution.web;

import java.util.List;
import java.util.UUID;

import com.lms.execution.query.TripQueryService;
import com.lms.execution.query.TripView;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.statutory.api.EwayBillTripPartB;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The trip-level Part-B update (issue #231): the vehicle of every live e-way
 * bill on the trip's consignments is set in one request, and the answer says
 * per consignment what became of it. Needs {@code EWAY_BILL_MANAGE}, which the
 * statutory module checks.
 */
@RestController
@RequestMapping("/api/v1/trips")
public class TripEwayBillController {

    private final TripQueryService trips;
    private final EwayBillTripPartB partB;

    public TripEwayBillController(TripQueryService trips, EwayBillTripPartB partB) {
        this.trips = trips;
        this.partB = partB;
    }

    /** The vehicle is the trip's own registration unless the request names another (a transhipment). */
    @PostMapping("/{id}/eway-bills/part-b")
    public PartBResponse updatePartB(@PathVariable UUID id, @Valid @RequestBody PartBRequest request) {
        TripView trip = trips.findById(id).orElseThrow(() -> new ResourceNotFoundException("Trip", id));
        String vehicle = request.vehicleNo() != null && !request.vehicleNo().isBlank() ? request.vehicleNo()
                : trip.vehicleRegistrationNo();
        if (vehicle == null || vehicle.isBlank()) {
            throw new BusinessRuleViolationException("trip-vehicle-missing",
                    "Trip " + trip.tripNo() + " has no vehicle, and the request names none");
        }
        List<EwayBillTripPartB.ItemOutcome> items = partB.updateVehicle(trip.loadId(), vehicle);
        int updated = (int) items.stream().filter(item -> "UPDATED".equals(item.outcome())).count();
        return new PartBResponse(id, vehicle.replace(" ", "").toUpperCase(java.util.Locale.ROOT), updated, items);
    }

    public record PartBRequest(@Size(max = 20) String vehicleNo) {
    }

    public record PartBResponse(UUID tripId, String vehicleNo, int updated, List<EwayBillTripPartB.ItemOutcome> items) {
    }
}
