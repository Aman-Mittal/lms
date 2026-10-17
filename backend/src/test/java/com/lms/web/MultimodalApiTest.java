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
package com.lms.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.lms.acceptance.SpineFixtures;
import com.lms.acceptance.TestFixtures;
import com.lms.client.ApiClient;
import com.lms.client.api.MultimodalApi;
import com.lms.client.model.Itinerary;
import com.lms.client.model.ItineraryLeg;
import com.lms.client.model.ItinerarySlice;
import com.lms.client.model.PlanItineraryLegRequest;
import com.lms.client.model.PlanItineraryRequest;
import com.lms.client.model.RailMilestoneRequest;
import com.lms.execution.command.TripCommandService;
import com.lms.identity.command.domain.AppUser;
import com.lms.identity.security.TokenService;
import com.lms.masterdata.command.FleetCommandService;
import com.lms.masterdata.command.RegisterTerminalCommand;
import com.lms.masterdata.command.TerminalCommandService;
import com.lms.masterdata.command.domain.BusinessPartner;
import com.lms.masterdata.command.domain.Terminal;
import com.lms.shared.geo.LatLon;
import com.lms.shared.tenant.TenantContext;
import feign.FeignException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Multimodal itineraries over HTTP, through a client generated from the
 * contract.
 *
 * <p>The rail leg goes first here so that the whole walk -- plan, terminal
 * events, a refused departure, a missed connection -- crosses the wire. A
 * road leg's progress comes from its trip's events, which
 * {@code multimodal.feature} drives through real dispatches; repeating that
 * here would test execution's gate rather than this contract.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MultimodalApiTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.7-alpine")
            .withDatabaseName("lms").withUsername("lms").withPassword("lms");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final List<String> MULTIMODAL = List.of(
            "MULTIMODAL_READ", "MULTIMODAL_PLAN", "MULTIMODAL_TRACK");

    /** What the in-process setup needs to build a trip. Never put in a token. */
    private static final List<String> SETUP = List.of(
            "PARTNER_CREATE", "PARTNER_UPDATE", "VEHICLE_CREATE", "DRIVER_CREATE",
            "TERMINAL_CREATE", "ORDER_CREATE", "ORDER_UPDATE", "LOAD_CREATE", "LOAD_ALLOCATE",
            "TRIP_EXECUTE");

    @LocalServerPort
    private int port;

    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private SpineFixtures spine;
    @Autowired
    private FleetCommandService fleet;
    @Autowired
    private TerminalCommandService terminals;
    @Autowired
    private TripCommandService trips;
    @Autowired
    private TokenService tokens;

    private String orgPath;
    private AppUser planner;
    private UUID railheadA;
    private UUID railheadB;
    private UUID customerSite;
    private UUID railCarrier;
    private UUID roadCarrier;
    private UUID tripId;
    private Instant now;

    @BeforeEach
    void aRouteToPlan() {
        String salt = UUID.randomUUID().toString().substring(0, 8);
        orgPath = "/acme-" + salt + "/";
        UUID tenantId = fixtures.createTenant("acme-" + salt, "Acme Logistics");
        UUID orgUnitId = fixtures.createOrgUnit(tenantId, orgPath, "Acme HQ", "HQ", null);
        planner = user(tenantId, orgUnitId, "planner@acme.test");
        now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        asSetup(tenantId, () -> {
            UUID customer = fleet.registerPartner(orgUnitId, "CUST-" + salt, "Customer",
                    BusinessPartner.PartnerType.CUSTOMER, "TAX-C-" + salt);
            UUID consignee = fleet.registerPartner(orgUnitId, "CONS-" + salt, "Consignee",
                    BusinessPartner.PartnerType.CUSTOMER, "TAX-K-" + salt);
            railCarrier = vendor("RAIL-" + salt, orgUnitId);
            roadCarrier = vendor("ROAD-" + salt, orgUnitId);

            railheadA = terminal("RH-A-" + salt, orgUnitId, Terminal.FunctionalCategory.HUB,
                    21.15, 79.09);
            railheadB = terminal("RH-B-" + salt, orgUnitId, Terminal.FunctionalCategory.HUB,
                    28.64, 77.22);
            customerSite = terminal("SITE-" + salt, orgUnitId,
                    Terminal.FunctionalCategory.CUSTOMER_SITE, 28.50, 77.30);
            UUID vehicle = fleet.registerVehicle(orgUnitId, null, "DL-01-" + salt, "TRUCK",
                    "RIGID", "2-AXLE", new BigDecimal("26000"), new BigDecimal("6000"),
                    new BigDecimal("60"), false, false);

            UUID loadId = spine.awardedLoad(orgUnitId, "LOAD-" + salt, customer, railheadB,
                    consignee, customerSite, vehicle, roadCarrier, new BigDecimal("5000"), false);
            tripId = trips.raiseTrip("TRIP-" + salt, loadId, Instant.now());
            return null;
        });
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a rail-then-road itinerary is planned, tracked and costed through the generated client")
    void railThenRoadOverHttp() {
        MultimodalApi api = client(MULTIMODAL);

        UUID id = api.planItinerary(railThenRoad(), null).getId();

        Itinerary planned = api.getItinerary(id);
        assertThat(planned.getStatus()).isEqualTo(Itinerary.StatusEnum.PLANNED);
        assertThat(planned.getLegs()).extracting(ItineraryLeg::getMode).containsExactly(
                ItineraryLeg.ModeEnum.RAIL, ItineraryLeg.ModeEnum.ROAD);
        assertThat(planned.getTotalCost()).isEqualByComparingTo("94000");
        assertThat(planned.getCostByCarrier()).hasSize(2);
        assertThat(planned.getLegs().get(1).getTripId()).isEqualTo(tripId);

        milestone(api, id, 1, RailMilestoneRequest.MilestoneEnum.PLACED, 40);
        milestone(api, id, 1, RailMilestoneRequest.MilestoneEnum.LOADED, 38);
        milestone(api, id, 1, RailMilestoneRequest.MilestoneEnum.DEPARTED, 36);
        // The same report again -- a retry that never saw the first answer.
        milestone(api, id, 1, RailMilestoneRequest.MilestoneEnum.DEPARTED, 36);
        // Arrives eight hours late: the lorry was booked for twelve hours
        // ago, and with two hours to unload it cannot go before eight.
        milestone(api, id, 1, RailMilestoneRequest.MilestoneEnum.ARRIVED, 10);

        Itinerary tracked = api.getItinerary(id);
        assertThat(tracked.getStatus()).isEqualTo(Itinerary.StatusEnum.IN_PROGRESS);
        assertThat(tracked.getConnectionMissed()).isTrue();
        ItineraryLeg rail = tracked.getLegs().get(0);
        assertThat(rail.getStatus()).isEqualTo(ItineraryLeg.StatusEnum.ARRIVED);
        assertThat(rail.getDepartedAt().toInstant()).isEqualTo(hoursAgo(36));
        ItineraryLeg road = tracked.getLegs().get(1);
        assertThat(road.getConnectionMissedAt()).isNotNull();
        assertThat(road.getEarliestStartAt().toInstant()).isEqualTo(hoursAgo(8));

        // A road leg moves with its trip, never by terminal event.
        assertStatus(() -> milestone(api, id, 2, RailMilestoneRequest.MilestoneEnum.PLACED, 1),
                409, "leg-not-rail");

        ItinerarySlice page = api.listItineraries(null, 1);
        assertThat(page.getItems()).hasSize(1);
        assertThat(page.getItems().get(0).getId()).isEqualTo(id);
        assertThat(page.getItems().get(0).getConnectionMissed()).isTrue();
    }

    @Test
    @DisplayName("refusals arrive as the documented statuses")
    void refusals() {
        MultimodalApi api = client(MULTIMODAL);
        UUID id = api.planItinerary(railThenRoad(), null).getId();

        // Skipping a milestone is a business-rule conflict.
        assertStatus(() -> milestone(api, id, 1, RailMilestoneRequest.MilestoneEnum.DEPARTED, 30),
                409, "leg-illegal-transition");
        // An itinerary with no legs fails validation before reaching the domain.
        assertStatus(() -> api.planItinerary(new PlanItineraryRequest().shipmentRef("X")
                .legs(List.of()), null), 400, null);
        // The same trip cannot run a second leg.
        assertStatus(() -> api.planItinerary(railThenRoad(), null), 409,
                "trip-already-on-itinerary");
        // An unknown itinerary is a 404.
        assertStatus(() -> api.getItinerary(UUID.randomUUID()), 404, null);
        // Reading is not planning, and neither is tracking.
        MultimodalApi readOnly = client(List.of("MULTIMODAL_READ"));
        assertStatus(() -> readOnly.planItinerary(railThenRoad(), null), 403, null);
        assertStatus(() -> milestone(readOnly, id, 1, RailMilestoneRequest.MilestoneEnum.PLACED,
                30), 403, null);
    }

    @Test
    @DisplayName("a retried plan with the same idempotency key is one itinerary")
    void idempotentPlan() {
        MultimodalApi api = client(MULTIMODAL);
        String key = "plan-" + UUID.randomUUID();

        UUID first = api.planItinerary(railThenRoad(), key).getId();
        UUID retry = api.planItinerary(railThenRoad(), key).getId();

        assertThat(retry).isEqualTo(first);
        assertThat(api.listItineraries(null, null).getItems()).hasSize(1);
    }

    // --------------------------------------------------------------- helpers

    /**
     * Rail from railhead A to railhead B, 36 to 14 hours ago; then the trip
     * from B to the customer, 12 to 6 hours ago, after two hours to unload.
     */
    private PlanItineraryRequest railThenRoad() {
        return new PlanItineraryRequest()
                .shipmentRef("SHP-API")
                .legs(List.of(
                        new PlanItineraryLegRequest()
                                .mode(PlanItineraryLegRequest.ModeEnum.RAIL)
                                .fromTerminalId(railheadA).toTerminalId(railheadB)
                                .plannedDepartureAt(at(36)).plannedArrivalAt(at(14))
                                .unitType(PlanItineraryLegRequest.UnitTypeEnum.CONTAINER)
                                .unitCount(4).bookingRef("RR-API-1").trainNo("CONCOR-9")
                                .carrierPartnerId(railCarrier).costAmount(new BigDecimal("85000")),
                        new PlanItineraryLegRequest()
                                .mode(PlanItineraryLegRequest.ModeEnum.ROAD)
                                .fromTerminalId(railheadB).toTerminalId(customerSite)
                                .plannedDepartureAt(at(12)).plannedArrivalAt(at(6))
                                .transferDwellMinutes(120)
                                .tripId(tripId)
                                .carrierPartnerId(roadCarrier).costAmount(new BigDecimal("9000"))));
    }

    private void milestone(MultimodalApi api, UUID id, int seq,
                           RailMilestoneRequest.MilestoneEnum milestone, int hours) {
        api.recordRailMilestone(id, seq, new RailMilestoneRequest()
                .milestone(milestone).at(at(hours)), null);
    }

    private Instant hoursAgo(int hours) {
        return now.minus(hours, ChronoUnit.HOURS);
    }

    private OffsetDateTime at(int hoursAgo) {
        return hoursAgo(hoursAgo).atOffset(ZoneOffset.UTC);
    }

    private static void assertStatus(Runnable call, int status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(FeignException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            if (code != null) {
                assertThat(e.contentUTF8()).contains(code);
            }
        });
    }

    private MultimodalApi client(List<String> permissions) {
        String token = tokens.issueAccessToken(planner, orgPath, permissions).value();
        ApiClient apiClient = new ApiClient().setBasePath("http://localhost:" + port);
        apiClient.getFeignBuilder().requestInterceptor(template ->
                template.header("Authorization", "Bearer " + token));
        return apiClient.buildClient(MultimodalApi.class);
    }

    private AppUser user(UUID tenantId, UUID orgUnitId, String email) {
        UUID id = fixtures.createUser(tenantId, orgUnitId, orgPath, email, "not-used-here");
        return new AppUser(id, tenantId, orgUnitId, email, "not-used", email,
                AppUser.UserStatus.ACTIVE, null, 0, null, null, null, Instant.now(), Instant.now());
    }

    private UUID vendor(String code, UUID orgUnitId) {
        UUID id = fleet.registerPartner(orgUnitId, code, code + " Carriers",
                BusinessPartner.PartnerType.VENDOR, "TAX-" + code);
        fleet.transitionPartner(id, BusinessPartner.PartnerStatus.PENDING_VERIFICATION);
        fleet.transitionPartner(id, BusinessPartner.PartnerStatus.ACTIVE);
        return id;
    }

    private UUID terminal(String code, UUID orgUnitId, Terminal.FunctionalCategory category,
                          double lat, double lon) {
        return terminals.register(new RegisterTerminalCommand.PointRadius(orgUnitId, code, code,
                category, new LatLon(lat, lon), 500, 2, null, null, 45, List.of()));
    }

    /** Runs setup scoped to the tenant and signed in with what the setup needs. */
    private <T> T asSetup(UUID tenantId, Supplier<T> action) {
        Set<SimpleGrantedAuthority> authorities = SETUP.stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toSet());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("setup", "n/a", authorities));
        try {
            return TenantContext.callWith(tenantId, orgPath, action);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
