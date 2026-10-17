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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.lms.acceptance.SpineFixtures;
import com.lms.acceptance.TestFixtures;
import com.lms.client.ApiClient;
import com.lms.client.api.ControlTowerApi;
import com.lms.client.model.AssignExceptionRequest;
import com.lms.client.model.ExceptionActivity;
import com.lms.client.model.ExceptionCategory;
import com.lms.client.model.ExceptionNoteRequest;
import com.lms.client.model.ExceptionSlice;
import com.lms.client.model.ExceptionSummary;
import com.lms.client.model.RaiseExceptionRequest;
import com.lms.client.model.ReopenExceptionRequest;
import com.lms.client.model.ResolveExceptionRequest;
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
 * The control tower over HTTP, through a client generated from the contract.
 *
 * <p>{@code OpenApiContractTest} proves every served path is documented and the
 * reverse. This proves the documentation is <em>usable</em>: the Feign client
 * is generated from {@code api/openapi.yaml} at build time, so a schema that
 * disagrees with what the server actually sends -- a renamed field, an enum
 * value the spec does not list, a 204 documented as a body -- fails here, in
 * the same way it would fail for anybody generating a client from the spec.
 *
 * <p>Setup goes through the real command services in-process; only the
 * behaviour under test crosses the wire. Building a trip over HTTP would need
 * a routing guide, which has no endpoint, and would test sourcing rather than
 * the control tower.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ControlTowerApiTest {

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

    private static final List<String> CONTROL_TOWER = List.of(
            "EXCEPTION_READ", "EXCEPTION_RAISE", "EXCEPTION_MANAGE", "EXCEPTION_CLOSE");

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
    private AppUser desk;
    private AppUser manager;
    private UUID tripId;

    @BeforeEach
    void aTripToRaiseAgainst() {
        // Salted per test: the container is shared, and tests isolate
        // themselves by tenant rather than by wiping the database.
        String salt = UUID.randomUUID().toString().substring(0, 8);
        orgPath = "/acme-" + salt + "/";
        UUID tenantId = fixtures.createTenant("acme-" + salt, "Acme Logistics");
        UUID orgUnitId = fixtures.createOrgUnit(tenantId, orgPath, "Acme HQ", "HQ", null);

        desk = user(tenantId, orgUnitId, "desk@acme.test");
        manager = user(tenantId, orgUnitId, "manager@acme.test");

        tripId = asSetup(tenantId, () -> {
            UUID customer = fleet.registerPartner(orgUnitId, "CUST-" + salt, "Customer",
                    BusinessPartner.PartnerType.CUSTOMER, "TAX-C-" + salt);
            UUID consignee = fleet.registerPartner(orgUnitId, "CONS-" + salt, "Consignee",
                    BusinessPartner.PartnerType.CUSTOMER, "TAX-K-" + salt);
            UUID vendor = fleet.registerPartner(orgUnitId, "VEND-" + salt, "Vendor",
                    BusinessPartner.PartnerType.VENDOR, "TAX-V-" + salt);
            fleet.transitionPartner(vendor, BusinessPartner.PartnerStatus.PENDING_VERIFICATION);
            fleet.transitionPartner(vendor, BusinessPartner.PartnerStatus.ACTIVE);

            UUID origin = depot("ORIGIN-" + salt, orgUnitId, 19.1, 72.9);
            UUID destination = depot("DEST-" + salt, orgUnitId, 19.3, 73.1);
            UUID vehicle = fleet.registerVehicle(orgUnitId, null, "MH-01-" + salt, "TRUCK",
                    "RIGID", "2-AXLE", new BigDecimal("26000"), new BigDecimal("6000"),
                    new BigDecimal("60"), false, false);

            UUID loadId = spine.awardedLoad(orgUnitId, "LOAD-" + salt, customer, origin,
                    consignee, destination, vehicle, vendor, new BigDecimal("5000"), false);
            return trips.raiseTrip("TRIP-" + salt, loadId, Instant.now());
        });
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a ticket walks its whole lifecycle through the generated client")
    void lifecycleOverHttp() {
        ControlTowerApi asDesk = client(desk, CONTROL_TOWER);
        ControlTowerApi asManager = client(manager, CONTROL_TOWER);

        UUID id = asDesk.raiseException(new RaiseExceptionRequest()
                .tripId(tripId)
                .category(ExceptionCategory.THEFT)
                .description("Seal broken at a fuel stop")
                .lat(new BigDecimal("19.200000"))
                .lon(new BigDecimal("73.000000")), null).getId();

        com.lms.client.model.Exception raised = asDesk.getException(id);
        assertThat(raised.getStatus()).isEqualTo(com.lms.client.model.Exception.StatusEnum.RAISED);
        assertThat(raised.getSeverity()).isEqualTo(com.lms.client.model.Exception.SeverityEnum.P1);
        assertThat(raised.getSuspendsSettlement()).isTrue();
        // The trip has no vehicle yet, so there is no fix to prefer: the
        // reporter's coordinates are what the ticket captured.
        assertThat(raised.getPositionSource())
                .isEqualTo(com.lms.client.model.Exception.PositionSourceEnum.REPORTED);
        assertThat(raised.getLat()).isEqualByComparingTo("19.2");

        asDesk.assignException(id, new AssignExceptionRequest().assigneeUserId(desk.id()), null);
        asDesk.investigateException(id, null, new ExceptionNoteRequest());
        asDesk.resolveException(id, new ResolveExceptionRequest()
                .resolution("Load counted at destination; nothing missing"), null);

        // The desk resolved it, so the desk may not close it: closing a
        // critical ticket releases money and needs a second person.
        assertThatThrownBy(() -> asDesk.closeException(id, null, new ExceptionNoteRequest()))
                .isInstanceOfSatisfying(FeignException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.contentUTF8()).contains("exception-close-needs-second-person");
                });

        asManager.closeException(id, null, new ExceptionNoteRequest());

        com.lms.client.model.Exception closed = asManager.getException(id);
        assertThat(closed.getStatus()).isEqualTo(com.lms.client.model.Exception.StatusEnum.CLOSED);
        assertThat(closed.getClosedBy()).isEqualTo(manager.id().toString());
        assertThat(closed.getAssigneeName()).isEqualTo("desk@acme.test");

        List<ExceptionActivity> history = asManager.getExceptionHistory(id);
        assertThat(history).extracting(ExceptionActivity::getAction).containsExactly(
                ExceptionActivity.ActionEnum.RAISED, ExceptionActivity.ActionEnum.ASSIGNED,
                ExceptionActivity.ActionEnum.INVESTIGATION_STARTED,
                ExceptionActivity.ActionEnum.RESOLVED, ExceptionActivity.ActionEnum.CLOSED);
    }

    @Test
    @DisplayName("the board pages through the generated client and the summary counts what is open")
    void boardAndSummary() {
        ControlTowerApi api = client(desk, CONTROL_TOWER);
        UUID accident = raise(api, ExceptionCategory.ACCIDENT);
        UUID delay = raise(api, ExceptionCategory.ETA_DELAY);

        ExceptionSlice first = api.listExceptions(null, null, null, 1);
        assertThat(first.getItems()).hasSize(1);
        assertThat(first.getHasMore()).isTrue();
        ExceptionSlice second = api.listExceptions(null, null, first.getNextCursor(), 1);
        assertThat(second.getItems()).hasSize(1);
        assertThat(second.getHasMore()).isFalse();
        assertThat(List.of(first.getItems().get(0).getId(), second.getItems().get(0).getId()))
                .containsExactly(delay, accident);

        ExceptionSlice p3Only = api.listExceptions(null, "P3", null, null);
        assertThat(p3Only.getItems()).extracting(com.lms.client.model.Exception::getId)
                .containsExactly(delay);

        ExceptionSummary summary = api.summariseExceptions();
        assertThat(summary.getOpenP1()).isEqualTo(1);
        assertThat(summary.getOpenP3()).isEqualTo(1);
        assertThat(summary.getUnassigned()).isEqualTo(2);
        assertThat(summary.getSettlementHolds()).isEqualTo(1);
    }

    @Test
    @DisplayName("refusals arrive as the documented statuses")
    void refusals() {
        ControlTowerApi api = client(desk, CONTROL_TOWER);
        UUID id = raise(api, ExceptionCategory.BREAKDOWN);

        // An illegal transition is a business-rule conflict.
        assertStatus(() -> api.investigateException(id, null, new ExceptionNoteRequest()), 409,
                "exception-illegal-transition");
        // A reopen with no reason fails validation before reaching the domain.
        assertStatus(() -> api.reopenException(id, new ReopenExceptionRequest(), null), 400, null);
        // An unknown ticket is a 404, not an empty body.
        assertStatus(() -> api.getException(UUID.randomUUID()), 404, null);
        // A token without the closing permission cannot close.
        ControlTowerApi readOnly = client(manager, List.of("EXCEPTION_READ"));
        assertStatus(() -> readOnly.closeException(id, null, new ExceptionNoteRequest()), 403, null);
        assertStatus(() -> readOnly.raiseException(new RaiseExceptionRequest()
                .tripId(tripId).category(ExceptionCategory.THEFT).description("x"), null), 403, null);
    }

    @Test
    @DisplayName("a retried raise with the same idempotency key is one ticket")
    void idempotentRaise() {
        ControlTowerApi api = client(desk, CONTROL_TOWER);
        RaiseExceptionRequest request = new RaiseExceptionRequest()
                .tripId(tripId).category(ExceptionCategory.ACCIDENT).description("Rolled over");
        String key = "raise-" + UUID.randomUUID();

        UUID first = api.raiseException(request, key).getId();
        UUID retry = api.raiseException(request, key).getId();

        assertThat(retry).isEqualTo(first);
        assertThat(api.listExceptions(null, null, null, null).getItems()).hasSize(1);
    }

    // --------------------------------------------------------------- helpers

    private UUID raise(ControlTowerApi api, ExceptionCategory category) {
        return api.raiseException(new RaiseExceptionRequest()
                .tripId(tripId).category(category).description(category + " reported"), null)
                .getId();
    }

    private static void assertStatus(Runnable call, int status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(FeignException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            if (code != null) {
                assertThat(e.contentUTF8()).contains(code);
            }
        });
    }

    /**
     * A client for one signed-in user, built the way any consumer of the spec
     * would build it.
     *
     * <p>Endpoints whose body the contract marks optional are still called with
     * an empty one: Feign refuses a null body argument, so that is what a
     * generated client actually sends.
     */
    private ControlTowerApi client(AppUser who, List<String> permissions) {
        String token = tokens.issueAccessToken(who, orgPath, permissions).value();
        ApiClient apiClient = new ApiClient().setBasePath("http://localhost:" + port);
        apiClient.getFeignBuilder().requestInterceptor(template ->
                template.header("Authorization", "Bearer " + token));
        return apiClient.buildClient(ControlTowerApi.class);
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
