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
package com.lms.e2e;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.lms.client.ApiClient;
import com.lms.client.api.AuthenticationApi;
import com.lms.client.api.DriversApi;
import com.lms.client.api.OrdersApi;
import com.lms.client.api.PartnersApi;
import com.lms.client.api.PlanningApi;
import com.lms.client.api.SourcingApi;
import com.lms.client.api.TerminalsApi;
import com.lms.client.api.TripsApi;
import com.lms.client.api.VehiclesApi;
import com.lms.client.model.AddOrderLineRequest;
import com.lms.client.model.AddVendorRequest;
import com.lms.client.model.AllocateRequest;
import com.lms.client.model.AssignConsignmentRequest;
import com.lms.client.model.AssignTripRequest;
import com.lms.client.model.AttachDocumentRequest;
import com.lms.client.model.AttachTripDocumentRequest;
import com.lms.client.model.AuthResult;
import com.lms.client.model.DefineGuideRequest;
import com.lms.client.model.GateInRequest;
import com.lms.client.model.IdResponse;
import com.lms.client.model.LoginRequest;
import com.lms.client.model.MilestoneRequest;
import com.lms.client.model.OpenLoadRequest;
import com.lms.client.model.PartnerTransitionRequest;
import com.lms.client.model.Point;
import com.lms.client.model.RaiseOrderRequest;
import com.lms.client.model.RaiseTripRequest;
import com.lms.client.model.RegisterDriverRequest;
import com.lms.client.model.RegisterPartnerRequest;
import com.lms.client.model.RegisterPointRadiusTerminalRequest;
import com.lms.client.model.RegisterVehicleRequest;
import com.lms.client.model.VendorResponseRequest;
import com.lms.client.model.WeighingRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the per-mode end-to-end scenarios have in common: a signed-in caller,
 * salted codes, each scenario's own patch of map, and the long walk from
 * "a lorry and a lane" to "a trip carrying a load".
 *
 * <p>Everything here is an HTTP call made with the client generated from the
 * contract. A step class creates one of these per scenario, so nothing leaks
 * from one scenario to the next except the shared demo tenant, which is the
 * point: codes are salted and geography is shifted, exactly as
 * {@code OrderToBillSteps} does for the original walk.
 *
 * <p>Each mode keeps to its own latitude band, so scenarios of different
 * modes can never overlap each other's terminals, and the shift east keeps
 * scenarios of the same mode apart.
 */
final class HttpWorld {

    static final String DEMO_TENANT = "demo";
    static final String DEMO_ADMIN = "admin@demo.local";
    static final String VEHICLE_TYPE = "RIGID";
    static final BigDecimal TARE_KG = new BigDecimal("6000");

    private static final AtomicInteger SCENARIOS = new AtomicInteger();
    private static final BigDecimal LON_STEP = new BigDecimal("0.05");

    private final int port;
    final String salt = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    private final BigDecimal lonShift =
            LON_STEP.multiply(BigDecimal.valueOf(SCENARIOS.getAndIncrement()));
    private AuthResult me;

    HttpWorld(int port) {
        this.port = port;
    }

    // ------------------------------------------------------------ session

    void signInAsDemoAdministrator() {
        me = client(null, AuthenticationApi.class).login(new LoginRequest()
                .tenantCode(DEMO_TENANT).email(DEMO_ADMIN).password(EndToEndContext.DEMO_PASSWORD));
        assertThat(me.getOrgUnitId()).as("login names the caller's unit").isNotNull();
    }

    UUID orgUnitId() {
        return me.getOrgUnitId();
    }

    <T extends ApiClient.Api> T api(Class<T> type) {
        return client(me.getAccessToken(), type);
    }

    <T extends ApiClient.Api> T client(String token, Class<T> type) {
        ApiClient apiClient = new ApiClient().setBasePath("http://localhost:" + port);
        if (token != null) {
            apiClient.getFeignBuilder().requestInterceptor(template ->
                    template.header("Authorization", "Bearer " + token));
        }
        return apiClient.buildClient(type);
    }

    /** Asynchronous consumers (the outbox) settle shortly after the request returns. */
    static <T> T eventually(String what, Supplier<T> attempt) {
        AssertionError last = null;
        for (int i = 0; i < 100; i++) {
            try {
                return attempt.get();
            } catch (AssertionError e) {
                last = e;
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(what + ": interrupted", e);
            }
        }
        throw new AssertionError(what + " did not happen in time: "
                + (last == null ? "" : last.getMessage()), last);
    }

    // -------------------------------------------------------- master data

    String code(String prefix) {
        return prefix + "-" + salt;
    }

    UUID partner(String code, RegisterPartnerRequest.PartnerTypeEnum type) {
        return api(PartnersApi.class).registerPartner(new RegisterPartnerRequest()
                .orgUnitId(me.getOrgUnitId())
                .code(code + "-" + salt)
                .legalName(code + " " + salt + " Pvt Ltd")
                .partnerType(type)
                .taxId("TAX-" + code + "-" + salt), null).getId();
    }

    UUID activeVendor(String code) {
        UUID vendorId = partner(code, RegisterPartnerRequest.PartnerTypeEnum.VENDOR);
        PartnersApi partners = api(PartnersApi.class);
        partners.transitionPartner(vendorId, new PartnerTransitionRequest()
                .target(PartnerTransitionRequest.TargetEnum.PENDING_VERIFICATION), null);
        partners.transitionPartner(vendorId, new PartnerTransitionRequest()
                .target(PartnerTransitionRequest.TargetEnum.ACTIVE), null);
        return vendorId;
    }

    /** A 500 m geofence; the scenario's own east shift is applied to the longitude. */
    UUID terminal(RegisterPointRadiusTerminalRequest.CategoryEnum category, String code,
                  double lat, double lon) {
        return api(TerminalsApi.class).registerPointRadiusTerminal(
                new RegisterPointRadiusTerminalRequest()
                        .orgUnitId(me.getOrgUnitId())
                        .code(code + "-" + salt)
                        .name(code)
                        .category(category)
                        .centre(new Point().lat(BigDecimal.valueOf(lat))
                                .lon(BigDecimal.valueOf(lon).add(lonShift)))
                        .radiusMetres(new BigDecimal("500"))
                        .dockCount(2)
                        .avgDwellMinutes(45), null).getId();
    }

    UUID lorry(String suffix, int payloadKg) {
        VehiclesApi vehicles = api(VehiclesApi.class);
        UUID vehicleId = vehicles.registerVehicle(new RegisterVehicleRequest()
                .orgUnitId(me.getOrgUnitId())
                .registrationNo("MH-" + suffix + "-" + salt)
                .category("TRUCK")
                .vehicleType(VEHICLE_TYPE)
                .axleConfig("2-AXLE")
                .grossWeightKg(TARE_KG.add(BigDecimal.valueOf(payloadKg)))
                .tareWeightKg(TARE_KG)
                .maxVolumeM3(new BigDecimal("60"))
                .hazmatCertified(false)
                .reeferCapable(false), null).getId();
        vehicles.attachVehicleDocument(vehicleId, new AttachDocumentRequest()
                .documentType("INSURANCE").documentNo("INS-" + suffix + "-" + salt)
                .issuingAuthority("IRDAI")
                .issuedOn(LocalDate.now(ZoneOffset.UTC).minusYears(1))
                .expiresOn(LocalDate.now(ZoneOffset.UTC).plusYears(1)), null);
        return vehicleId;
    }

    UUID driver(String suffix) {
        return api(DriversApi.class).registerDriver(new RegisterDriverRequest()
                .orgUnitId(me.getOrgUnitId())
                .fullName("Driver " + suffix)
                .phone("+910000000001")
                .licenceNo("DL-" + suffix + "-" + salt)
                .licenceClass("HEAVY")
                .licenceAuthority("RTO")
                .licenceExpiresOn(LocalDate.now(ZoneOffset.UTC).plusYears(1))
                .hazmatEndorsed(false), null).getId();
    }

    // ------------------------------------------------------ a trip to run

    /**
     * An order from one terminal to another, planned onto a lorry, awarded to
     * the vendor, and raised as a trip assigned to the lorry and a driver --
     * everything short of the trip leaving. The trip is not yet dispatched.
     */
    UUID assignedTrip(String tag, UUID customer, UUID consignee, UUID origin, UUID destination,
                      UUID vendor, UUID vehicle, UUID driver, int weightKg) {
        SourcingApi sourcing = api(SourcingApi.class);
        UUID guideId = sourcing.defineRoutingGuide(new DefineGuideRequest()
                .orgUnitId(me.getOrgUnitId())
                .originTerminalId(origin)
                .destinationTerminalId(destination)
                .vehicleType(VEHICLE_TYPE), null).getId();
        sourcing.addRoutingGuideVendor(guideId, new AddVendorRequest()
                .vendorPartnerId(vendor).rankNo(1).responseSlaMinutes(60)
                .agreedRate(new BigDecimal("20000")), null);

        OrdersApi orders = api(OrdersApi.class);
        UUID orderId = orders.raiseOrder(new RaiseOrderRequest()
                .orgUnitId(me.getOrgUnitId())
                .customerPartnerId(customer)
                .orderNo("SO-" + tag + "-" + salt)
                .originTerminalId(origin)
                .requestedPickupAt(OffsetDateTime.now(ZoneOffset.UTC).plusHours(4))
                .source(RaiseOrderRequest.SourceEnum.API), null).getId();
        orders.addOrderLine(orderId, new AddOrderLineRequest()
                .lineNo(1)
                .materialCode("GOODS")
                .materialDescription("General goods")
                .materialClass("GENERAL")
                .quantity(new BigDecimal("10"))
                .uom("CASE")
                .deadWeightKg(BigDecimal.valueOf(weightKg))
                .consigneePartnerId(consignee)
                .destinationTerminalId(destination), null);
        orders.validateOrder(orderId, null);

        PlanningApi planning = api(PlanningApi.class);
        List<IdResponse> consignments = planning.generateConsignments(orderId, null);
        assertThat(consignments).hasSize(1);
        UUID loadId = planning.openLoad(new OpenLoadRequest()
                .orgUnitId(me.getOrgUnitId())
                .loadNo("LD-" + tag + "-" + salt)
                .originTerminalId(origin)
                .vehicleId(vehicle), null).getId();
        planning.assignConsignment(loadId, new AssignConsignmentRequest()
                .consignmentId(consignments.get(0).getId()), null);
        planning.planLoad(loadId, null);

        UUID allocationId = sourcing.allocateLoad(new AllocateRequest().loadId(loadId), null).getId();
        sourcing.acceptOffer(allocationId, new VendorResponseRequest().vendorPartnerId(vendor), null);
        assertThat(sourcing.getAllocation(allocationId).getStatus()).isEqualTo("AWARDED");

        TripsApi trips = api(TripsApi.class);
        UUID tripId = trips.raiseTrip(new RaiseTripRequest().tripNo("TR-" + tag + "-" + salt)
                .loadId(loadId).plannedStartAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(60)),
                null).getId();
        trips.assignTrip(tripId, new AssignTripRequest().vehicleId(vehicle).driverId(driver), null);
        return tripId;
    }

    /** Gate in, weigh, load, attach the documents and dispatch, at the given moment. */
    void dispatch(UUID tripId, UUID origin, int weightKg, OffsetDateTime dispatchedAt) {
        TripsApi trips = api(TripsApi.class);
        OffsetDateTime gateInAt = dispatchedAt.minusMinutes(60);
        trips.gateIn(tripId, new GateInRequest().terminalId(origin).at(gateInAt), null);
        trips.recordWeighing(tripId, new WeighingRequest().terminalId(origin)
                .type(WeighingRequest.TypeEnum.TARE).weightKg(TARE_KG).at(gateInAt), null);
        trips.recordWeighing(tripId, new WeighingRequest().terminalId(origin)
                .type(WeighingRequest.TypeEnum.GROSS)
                .weightKg(TARE_KG.add(BigDecimal.valueOf(weightKg))).at(gateInAt), null);
        trips.markLoaded(tripId, new MilestoneRequest().at(dispatchedAt.minusMinutes(1)), null);
        trips.attachTripDocument(tripId, new AttachTripDocumentRequest()
                .documentType("CONSIGNMENT_NOTE").documentRef("LR-" + tripId), null);
        trips.attachTripDocument(tripId, new AttachTripDocumentRequest()
                .documentType("EWAY_BILL").documentRef("EW-" + tripId), null);
        trips.dispatchTrip(tripId, new MilestoneRequest().at(dispatchedAt), null);
    }

    /** Arrival and proof of delivery at the given moment. */
    void deliver(UUID tripId, OffsetDateTime deliveredAt) {
        TripsApi trips = api(TripsApi.class);
        trips.beginTransit(tripId, null);
        trips.recordArrival(tripId, new MilestoneRequest().at(deliveredAt.minusMinutes(10)), null);
        trips.completeTrip(tripId, new MilestoneRequest().at(deliveredAt), null);
    }
}
