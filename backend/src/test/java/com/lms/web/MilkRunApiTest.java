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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.lms.acceptance.SpineFixtures;
import com.lms.acceptance.TestFixtures;
import com.lms.client.ApiClient;
import com.lms.client.api.MilkRunsApi;
import com.lms.client.api.SettlementApi;
import com.lms.client.model.AssignCircuitTripRequest;
import com.lms.client.model.CircuitPickupRequest;
import com.lms.client.model.CircuitRun;
import com.lms.client.model.CircuitRunSlice;
import com.lms.client.model.CircuitRunStop;
import com.lms.client.model.CircuitSkipRequest;
import com.lms.client.model.FreightBill;
import com.lms.client.model.FreightBillLine;
import com.lms.client.model.GenerateCircuitRunsRequest;
import com.lms.client.model.MatchRequest;
import com.lms.client.model.MatchResponse;
import com.lms.client.model.MilestoneRequest;
import com.lms.client.model.MilkRunRoute;
import com.lms.client.model.MilkRunRouteSlice;
import com.lms.client.model.MilkRunRouteStop;
import com.lms.client.model.MilkRunStopPlan;
import com.lms.client.model.PublishMilkRunRouteRequest;
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
 * Milk runs over HTTP, through a client generated from the contract.
 *
 * <p>Plans a route, generates its circuit, hands it to a trip and records the
 * whole circuit by hand -- the path a site without trackers takes. The
 * geofence-driven path is covered by {@code milkrun.feature}; what this proves
 * is that the documented contract is the one the server keeps.
 *
 * <p>Setup goes through the real command services in-process, as in
 * {@code ControlTowerApiTest}: building a trip over HTTP would test sourcing,
 * not milk runs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MilkRunApiTest {

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

    private static final List<String> MILK_RUNS = List.of(
            "MILKRUN_READ", "MILKRUN_PLAN", "MILKRUN_EXECUTE");

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
    private UUID supplierA;
    private UUID supplierB;
    private UUID supplierC;
    private UUID plant;
    private UUID tripId;

    @BeforeEach
    void aNetworkAndATrip() {
        String salt = UUID.randomUUID().toString().substring(0, 8);
        orgPath = "/acme-" + salt + "/";
        UUID tenantId = fixtures.createTenant("acme-" + salt, "Acme Logistics");
        UUID orgUnitId = fixtures.createOrgUnit(tenantId, orgPath, "Acme HQ", "HQ", null);
        planner = user(tenantId, orgUnitId, "planner@acme.test");

        asSetup(tenantId, () -> {
            supplierA = depot("SUP-A-" + salt, orgUnitId, 19.10, 72.90);
            supplierB = depot("SUP-B-" + salt, orgUnitId, 19.15, 73.00);
            supplierC = depot("SUP-C-" + salt, orgUnitId, 19.20, 73.00);
            plant = depot("PLANT-" + salt, orgUnitId, 19.30, 73.10);

            UUID customer = fleet.registerPartner(orgUnitId, "CUST-" + salt, "Customer",
                    BusinessPartner.PartnerType.CUSTOMER, "TAX-C-" + salt);
            UUID consignee = fleet.registerPartner(orgUnitId, "CONS-" + salt, "Consignee",
                    BusinessPartner.PartnerType.CUSTOMER, "TAX-K-" + salt);
            UUID vendor = fleet.registerPartner(orgUnitId, "VEND-" + salt, "Vendor",
                    BusinessPartner.PartnerType.VENDOR, "TAX-V-" + salt);
            fleet.transitionPartner(vendor, BusinessPartner.PartnerStatus.PENDING_VERIFICATION);
            fleet.transitionPartner(vendor, BusinessPartner.PartnerStatus.ACTIVE);
            UUID vehicle = fleet.registerVehicle(orgUnitId, null, "MH-01-" + salt, "TRUCK",
                    "RIGID", "2-AXLE", new BigDecimal("26000"), new BigDecimal("6000"),
                    new BigDecimal("60"), false, false);

            UUID loadId = spine.awardedLoad(orgUnitId, "LOAD-" + salt, customer, supplierA,
                    consignee, plant, vehicle, vendor, new BigDecimal("5000"), false);
            tripId = trips.raiseTrip("TRIP-" + salt, loadId, Instant.now());
            return null;
        });
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a circuit is planned, run by hand and priced through the generated client")
    void circuitOverHttp() {
        MilkRunsApi api = client(planner, MILK_RUNS);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        UUID routeId = api.publishMilkRunRoute(route("MR-1", today.minusDays(1)), null).getId();

        MilkRunRoute route = api.getMilkRunRoute(routeId);
        assertThat(route.getDaysOfWeek()).isEqualTo("MON,TUE,WED,THU,FRI,SAT,SUN");
        assertThat(route.getDepartureTime()).startsWith("00:00");
        List<MilkRunRouteStop> plan = api.getMilkRunRouteStops(routeId);
        assertThat(plan).extracting(MilkRunRouteStop::getTerminalId)
                .containsExactly(supplierA, supplierB, supplierC);

        MilkRunRouteSlice inForce = api.listMilkRunRoutes(null, null);
        assertThat(inForce.getItems()).extracting(MilkRunRoute::getId).containsExactly(routeId);

        // Twice, because the second time must plan nothing.
        GenerateCircuitRunsRequest window = new GenerateCircuitRunsRequest().fromDate(today).days(1);
        int first = api.generateCircuitRuns(null, window).getGenerated();
        int second = api.generateCircuitRuns(null, window).getGenerated();
        assertThat(first + second).isLessThanOrEqualTo(1);

        CircuitRunSlice planned = api.listCircuitRuns(today, "MR-1", null, null, null);
        assertThat(planned.getItems()).hasSize(1);
        CircuitRun run = planned.getItems().get(0);
        assertThat(run.getStatus()).isEqualTo(CircuitRun.StatusEnum.PLANNED);
        UUID runId = run.getId();
        OffsetDateTime departs = run.getPlannedDeparture();

        api.assignCircuitTrip(runId, new AssignCircuitTripRequest().tripId(tripId), null);

        api.recordCircuitStopArrival(runId, 1, null, at(departs, 10));
        api.recordCircuitStopPickup(runId, 1, new CircuitPickupRequest()
                .quantityKg(new BigDecimal("950.5")), null);
        api.recordCircuitStopDeparture(runId, 1, null, at(departs, 25));
        api.skipCircuitStop(runId, 2, new CircuitSkipRequest().reason("Supplier closed"), null);
        api.recordCircuitStopArrival(runId, 3, null, at(departs, 165));
        api.completeCircuitRun(runId, null, at(departs, 200));

        CircuitRun done = api.getCircuitRun(runId);
        assertThat(done.getStatus()).isEqualTo(CircuitRun.StatusEnum.COMPLETED);
        assertThat(done.getCompletionSource()).isEqualTo(CircuitRun.CompletionSourceEnum.MANUAL);
        assertThat(done.getStopsVisited()).isEqualTo(2);
        assertThat(done.getStopsSkipped()).isEqualTo(1);
        // 5000 for the run, 750 for each of the two suppliers visited.
        assertThat(done.getChargeAmount()).isEqualByComparingTo("6500.00");
        assertThat(done.getTotalPickedUpKg()).isEqualByComparingTo("950.5");

        List<CircuitRunStop> stops = api.getCircuitRunStops(runId);
        assertThat(stops).extracting(CircuitRunStop::getStatus).containsExactly(
                CircuitRunStop.StatusEnum.DEPARTED, CircuitRunStop.StatusEnum.SKIPPED,
                CircuitRunStop.StatusEnum.ARRIVED);
        assertThat(stops.get(0).getMinutesLate()).isZero();
        // The third window closed 150 minutes in; reaching it at 165 is late.
        assertThat(stops.get(2).getMinutesLate()).isEqualTo(15);
        assertThat(stops.get(1).getSkipReason()).isEqualTo("Supplier closed");

        // Finance heard the completion and raised the carrier's payable: on the
        // same listing as trip bills, and matched by the same rule.
        SettlementApi settlement = settlementClient();
        FreightBill bill = settlement.listFreightBills(null, null, null).getItems().stream()
                .filter(candidate -> runId.equals(candidate.getCircuitRunId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("The circuit raised no payable"));
        assertThat(bill.getSource()).isEqualTo(FreightBill.SourceEnum.MILK_RUN);
        assertThat(bill.getRouteCode()).isEqualTo("MR-1");
        assertThat(bill.getTripId()).isEqualTo(tripId);
        assertThat(bill.getStatus()).isEqualTo(FreightBill.StatusEnum.DRAFT);
        assertThat(bill.getComputedAmount()).isEqualByComparingTo("6500.00");
        assertThat(bill.getTariffCode()).isNull();
        List<FreightBillLine> lines = settlement.getFreightBillLines(bill.getId());
        assertThat(lines).extracting(FreightBillLine::getChargeType).containsExactly(
                FreightBillLine.ChargeTypeEnum.BASE_FREIGHT,
                FreightBillLine.ChargeTypeEnum.COLLECTION_STOP);
        assertThat(lines.get(0).getAmount()).isEqualByComparingTo("5000.00");
        assertThat(lines.get(1).getAmount()).isEqualByComparingTo("1500.00");

        MatchResponse matched = settlement.matchFreightBill(bill.getId(),
                new MatchRequest().claimedAmount(new BigDecimal("6560")), null);
        assertThat(matched.getStatus()).isEqualTo(MatchResponse.StatusEnum.APPROVED_FOR_PAYMENT);
        assertThat(settlement.getFreightBill(bill.getId()).getVarianceAmount())
                .isEqualByComparingTo("60.00");
    }

    @Test
    @DisplayName("refusals arrive as the documented statuses")
    void refusals() {
        MilkRunsApi api = client(planner, MILK_RUNS);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        api.publishMilkRunRoute(route("MR-2", today.minusDays(1)), null);
        api.generateCircuitRuns(null, new GenerateCircuitRunsRequest().fromDate(today).days(1));
        UUID runId = api.listCircuitRuns(today, "MR-2", null, null, null).getItems().get(0).getId();

        // Not under way yet: recording a stop is a business-rule conflict.
        assertStatus(() -> api.recordCircuitStopArrival(runId, 1, null, new MilestoneRequest()),
                409, "circuit-run-not-in-progress");
        // A successor that does not start after the version in force.
        assertStatus(() -> api.publishMilkRunRoute(route("MR-2", today.minusDays(1)), null),
                409, "milk-run-route-backdated");
        // A pickup with no quantity fails validation before reaching the domain.
        assertStatus(() -> api.recordCircuitStopPickup(runId, 1, new CircuitPickupRequest(), null),
                400, null);
        assertStatus(() -> api.getCircuitRun(UUID.randomUUID()), 404, null);
        // Somebody who may read circuits may not plan them.
        MilkRunsApi readOnly = client(planner, List.of("MILKRUN_READ"));
        assertStatus(() -> readOnly.publishMilkRunRoute(route("MR-3", today), null), 403, null);
        assertStatus(() -> readOnly.assignCircuitTrip(runId,
                new AssignCircuitTripRequest().tripId(tripId), null), 403, null);
    }

    @Test
    @DisplayName("a retried publish with the same idempotency key is one route")
    void idempotentPublish() {
        MilkRunsApi api = client(planner, MILK_RUNS);
        PublishMilkRunRouteRequest request = route("MR-4", LocalDate.now(ZoneOffset.UTC));
        String key = "publish-" + UUID.randomUUID();

        UUID first = api.publishMilkRunRoute(request, key).getId();
        UUID retry = api.publishMilkRunRoute(request, key).getId();

        assertThat(retry).isEqualTo(first);
        assertThat(api.listMilkRunRoutes(null, null).getItems()).hasSize(1);
    }

    // --------------------------------------------------------------- helpers

    private PublishMilkRunRouteRequest route(String code, LocalDate effectiveFrom) {
        return new PublishMilkRunRouteRequest()
                .code(code)
                .name(code + " circuit")
                .plantTerminalId(plant)
                .effectiveFrom(effectiveFrom)
                .days(List.of(PublishMilkRunRouteRequest.DaysEnum.values()))
                .departureTime("00:00")
                .timeZone("UTC")
                .runRate(new BigDecimal("5000.00"))
                .perStopFee(new BigDecimal("750.00"))
                .stops(List.of(
                        stop(supplierA, 0, 30),
                        stop(supplierB, 45, 90),
                        stop(supplierC, 105, 150)));
    }

    private static MilkRunStopPlan stop(UUID terminal, int opens, int closes) {
        return new MilkRunStopPlan().terminalId(terminal).windowOpenMin(opens)
                .windowCloseMin(closes).plannedDwellMin(15);
    }

    private static MilestoneRequest at(OffsetDateTime departure, int minutes) {
        return new MilestoneRequest().at(departure.plusMinutes(minutes));
    }

    private static void assertStatus(Runnable call, int status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(FeignException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            if (code != null) {
                assertThat(e.contentUTF8()).contains(code);
            }
        });
    }

    private MilkRunsApi client(AppUser who, List<String> permissions) {
        String token = tokens.issueAccessToken(who, orgPath, permissions).value();
        ApiClient apiClient = new ApiClient().setBasePath("http://localhost:" + port);
        apiClient.getFeignBuilder().requestInterceptor(template ->
                template.header("Authorization", "Bearer " + token));
        return apiClient.buildClient(MilkRunsApi.class);
    }

    private SettlementApi settlementClient() {
        String token = tokens.issueAccessToken(planner, orgPath,
                List.of("INVOICE_READ", "INVOICE_APPROVE")).value();
        ApiClient apiClient = new ApiClient().setBasePath("http://localhost:" + port);
        apiClient.getFeignBuilder().requestInterceptor(template ->
                template.header("Authorization", "Bearer " + token));
        return apiClient.buildClient(SettlementApi.class);
    }

    private AppUser user(UUID tenantId, UUID orgUnitId, String email) {
        UUID id = fixtures.createUser(tenantId, orgUnitId, orgPath, email, "not-used-here");
        return new AppUser(id, tenantId, orgUnitId, email, "not-used", email,
                AppUser.UserStatus.ACTIVE, null, 0, null, null, null, Instant.now(), Instant.now());
    }

    private UUID depot(String code, UUID orgUnitId, double lat, double lon) {
        return terminals.register(new RegisterTerminalCommand.PointRadius(orgUnitId, code, code,
                Terminal.FunctionalCategory.CUSTOMER_SITE, new LatLon(lat, lon), 500, 2, null,
                null, 45, List.of()));
    }

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
