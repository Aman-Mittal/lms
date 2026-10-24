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
package com.lms.performance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.lms.acceptance.SpineFixtures;
import com.lms.acceptance.TestFixtures;
import com.lms.execution.command.TripCommandService;
import com.lms.masterdata.command.FleetCommandService;
import com.lms.masterdata.command.RegisterTerminalCommand;
import com.lms.masterdata.command.TerminalCommandService;
import com.lms.masterdata.command.domain.BusinessPartner;
import com.lms.masterdata.command.domain.Terminal;
import com.lms.shared.geo.LatLon;
import com.lms.shared.tenant.TenantContext;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * A tenant with one trip on one lorry, built through the real command
 * services -- the anchor that bulk-loaded rows hang their foreign keys on.
 */
public final class TripScaffold {

    public record Built(UUID tenantId, String orgPath, UUID orgUnitId, UUID tripId, UUID vehicleId) {
    }

    /** Who the scaffold acts as. Not a credential: in-process test authentication carries none. */
    private static final String SETUP_PRINCIPAL = "perf-setup";

    private static final List<String> SETUP = List.of(
            "PARTNER_CREATE", "PARTNER_UPDATE", "VEHICLE_CREATE", "TERMINAL_CREATE",
            "ORDER_CREATE", "ORDER_UPDATE", "LOAD_CREATE", "LOAD_ALLOCATE", "TRIP_EXECUTE");

    private TripScaffold() {
    }

    public static Built build(TestFixtures fixtures, SpineFixtures spine, FleetCommandService fleet,
                       TerminalCommandService terminals, TripCommandService trips,
                       double lonOffset) {
        String salt = UUID.randomUUID().toString().substring(0, 8);
        String orgPath = "/perf-" + salt + "/";
        UUID tenantId = fixtures.createTenant("perf-" + salt, "Perf " + salt);
        UUID orgUnitId = fixtures.createOrgUnit(tenantId, orgPath, "HQ", "HQ", null);

        UUID[] vehicle = new UUID[1];
        UUID tripId = as(tenantId, orgPath, () -> {
            UUID customer = fleet.registerPartner(orgUnitId, "C-" + salt, "Customer",
                    BusinessPartner.PartnerType.CUSTOMER, "T-C-" + salt);
            UUID consignee = fleet.registerPartner(orgUnitId, "K-" + salt, "Consignee",
                    BusinessPartner.PartnerType.CUSTOMER, "T-K-" + salt);
            UUID vendor = fleet.registerPartner(orgUnitId, "V-" + salt, "Vendor",
                    BusinessPartner.PartnerType.VENDOR, "T-V-" + salt);
            fleet.transitionPartner(vendor, BusinessPartner.PartnerStatus.PENDING_VERIFICATION);
            fleet.transitionPartner(vendor, BusinessPartner.PartnerStatus.ACTIVE);
            UUID origin = depot(terminals, orgUnitId, "O-" + salt, 19.1, 72.9 + lonOffset);
            UUID destination = depot(terminals, orgUnitId, "D-" + salt, 19.3, 73.1 + lonOffset);
            vehicle[0] = fleet.registerVehicle(orgUnitId, null, "PF-" + salt, "TRUCK", "RIGID",
                    "2-AXLE", new BigDecimal("26000"), new BigDecimal("6000"),
                    new BigDecimal("60"), false, false);
            UUID loadId = spine.awardedLoad(orgUnitId, "L-" + salt, customer, origin, consignee,
                    destination, vehicle[0], vendor, new BigDecimal("5000"), false);
            return trips.raiseTrip("TR-" + salt, loadId, Instant.now());
        });
        return new Built(tenantId, orgPath, orgUnitId, tripId, vehicle[0]);
    }

    private static UUID depot(TerminalCommandService terminals, UUID orgUnitId, String code,
                              double lat, double lon) {
        return terminals.register(new RegisterTerminalCommand.PointRadius(orgUnitId, code, code,
                Terminal.FunctionalCategory.CUSTOMER_SITE, new LatLon(lat, lon), 500, 2, null,
                null, 45, List.of()));
    }

    private static <T> T as(UUID tenantId, String orgPath, Supplier<T> action) {
        Set<SimpleGrantedAuthority> authorities = SETUP.stream()
                .map(SimpleGrantedAuthority::new).collect(Collectors.toSet());
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(SETUP_PRINCIPAL, null, authorities));
        try {
            return TenantContext.callWith(tenantId, orgPath, action);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
