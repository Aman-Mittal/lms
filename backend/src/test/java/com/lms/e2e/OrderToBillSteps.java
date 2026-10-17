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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.lms.client.ApiClient;
import com.lms.client.api.AuthenticationApi;
import com.lms.client.api.ControlTowerApi;
import com.lms.client.api.DriversApi;
import com.lms.client.api.OrdersApi;
import com.lms.client.api.PartnersApi;
import com.lms.client.api.PlanningApi;
import com.lms.client.api.SettlementApi;
import com.lms.client.api.SourcingApi;
import com.lms.client.api.TelematicsApi;
import com.lms.client.api.TerminalsApi;
import com.lms.client.api.TripsApi;
import com.lms.client.api.VehiclesApi;
import com.lms.client.model.AddOrderLineRequest;
import com.lms.client.model.AddSlabRequest;
import com.lms.client.model.AddVendorRequest;
import com.lms.client.model.AllocateRequest;
import com.lms.client.model.AssignConsignmentRequest;
import com.lms.client.model.AssignExceptionRequest;
import com.lms.client.model.AssignTripRequest;
import com.lms.client.model.AttachDocumentRequest;
import com.lms.client.model.AttachTripDocumentRequest;
import com.lms.client.model.AuthResult;
import com.lms.client.model.DefineGuideRequest;
import com.lms.client.model.ExceptionCategory;
import com.lms.client.model.ExceptionNoteRequest;
import com.lms.client.model.FreightBill;
import com.lms.client.model.GateInRequest;
import com.lms.client.model.IdResponse;
import com.lms.client.model.LoginRequest;
import com.lms.client.model.MatchRequest;
import com.lms.client.model.MilestoneRequest;
import com.lms.client.model.OpenLoadRequest;
import com.lms.client.model.PartnerTransitionRequest;
import com.lms.client.model.Ping;
import com.lms.client.model.PingBatchRequest;
import com.lms.client.model.Point;
import com.lms.client.model.PublishTariffRequest;
import com.lms.client.model.RaiseExceptionRequest;
import com.lms.client.model.RaiseOrderRequest;
import com.lms.client.model.RaiseTripRequest;
import com.lms.client.model.RegisterDriverRequest;
import com.lms.client.model.RegisterPartnerRequest;
import com.lms.client.model.RegisterPointRadiusTerminalRequest;
import com.lms.client.model.RegisterVehicleRequest;
import com.lms.client.model.ResolveExceptionRequest;
import com.lms.client.model.WeighingRequest;
import com.lms.shared.tenant.TenantContext;
import feign.FeignException;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Step definitions for the end-to-end features: every call goes over HTTP.
 *
 * <p>One class rather than one per context. These steps are a single caller's
 * journey through the API, and splitting it would mean passing a session and a
 * dozen identifiers between classes for no reader's benefit.
 *
 * <p>Every code a scenario writes is salted: the demo tenant is shared by every
 * scenario in the run, exactly as a real deployment's tenant is shared by
 * everybody using it.
 */
public class OrderToBillSteps {

    private static final String DEMO_TENANT = "demo";
    private static final String DEMO_ADMIN = "admin@demo.local";
    private static final String VEHICLE_TYPE = "RIGID";
    private static final BigDecimal TARE_KG = new BigDecimal("6000");

    @Value("${local.server.port}")
    private int port;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * Each scenario's own patch of map.
     *
     * <p>The demo tenant is shared, and terminals of one category may not
     * overlap -- a rule the platform enforces and these scenarios must not
     * dodge. So every scenario shifts its whole geography east by a couple of
     * kilometres: depots and pings together, so the journey is the same shape
     * wherever it lands.
     */
    private static final AtomicInteger SCENARIOS = new AtomicInteger();
    private static final BigDecimal LON_STEP = new BigDecimal("0.05");

    private final Map<String, UUID> ids = new HashMap<>();
    private String salt;
    private BigDecimal lonShift;
    private AuthResult me;

    private UUID tripId;
    private UUID ticketId;
    private UUID billId;
    private BigDecimal loadWeightKg;
    private FeignException refusal;

    @Before
    public void freshSalt() {
        salt = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        lonShift = LON_STEP.multiply(BigDecimal.valueOf(SCENARIOS.getAndIncrement()));
    }

    // ----------------------------------------------------------- sign in

    @Given("I am signed in as the demo administrator")
    public void signInAsDemoAdministrator() {
        me = signIn(DEMO_ADMIN, EndToEndContext.DEMO_PASSWORD);
        assertThat(me.getOrgUnitId()).as("login names the caller's unit").isNotNull();
    }

    // ------------------------------------------------------- master data

    @Given("a customer, a consignee and an active vendor {string} are registered")
    public void partners(String vendor) {
        ids.put("customer", partner("CUST", RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER));
        ids.put("consignee", partner("CONS", RegisterPartnerRequest.PartnerTypeEnum.CONSIGNEE));
        UUID vendorId = partner(vendor, RegisterPartnerRequest.PartnerTypeEnum.VENDOR);
        PartnersApi partners = api(PartnersApi.class);
        partners.transitionPartner(vendorId, new PartnerTransitionRequest()
                .target(PartnerTransitionRequest.TargetEnum.PENDING_VERIFICATION), null);
        partners.transitionPartner(vendorId, new PartnerTransitionRequest()
                .target(PartnerTransitionRequest.TargetEnum.ACTIVE), null);
        ids.put(vendor, vendorId);
    }

    @Given("depots {string} at {double},{double} and {string} at {double},{double} are registered")
    public void depots(String origin, double originLat, double originLon,
                       String destination, double destinationLat, double destinationLon) {
        ids.put(origin, depot(origin, originLat, originLon));
        ids.put(destination, depot(destination, destinationLat, destinationLon));
    }

    @Given("lorry {string} with {int} kg payload and driver {string} are registered")
    public void lorryAndDriver(String lorry, int payloadKg, String driver) {
        VehiclesApi vehicles = api(VehiclesApi.class);
        UUID vehicleId = vehicles.registerVehicle(new RegisterVehicleRequest()
                .orgUnitId(me.getOrgUnitId())
                .registrationNo("MH-" + salt)
                .category("TRUCK")
                .vehicleType(VEHICLE_TYPE)
                .axleConfig("2-AXLE")
                .grossWeightKg(TARE_KG.add(BigDecimal.valueOf(payloadKg)))
                .tareWeightKg(TARE_KG)
                .maxVolumeM3(new BigDecimal("60"))
                .hazmatCertified(false)
                .reeferCapable(false), null).getId();
        vehicles.attachVehicleDocument(vehicleId, new AttachDocumentRequest()
                .documentType("INSURANCE").documentNo("INS-" + salt).issuingAuthority("IRDAI")
                .issuedOn(LocalDate.now(ZoneOffset.UTC).minusYears(1))
                .expiresOn(LocalDate.now(ZoneOffset.UTC).plusYears(1)), null);
        ids.put(lorry, vehicleId);

        ids.put(driver, api(DriversApi.class).registerDriver(new RegisterDriverRequest()
                .orgUnitId(me.getOrgUnitId())
                .fullName(driver)
                .phone("+910000000001")
                .licenceNo("DL-" + salt)
                .licenceClass("HEAVY")
                .licenceAuthority("RTO")
                .licenceExpiresOn(LocalDate.now(ZoneOffset.UTC).plusYears(1))
                .hazmatEndorsed(false), null).getId());
    }

    @Given("{string} is ranked first on the routing guide from {string} to {string}")
    public void routingGuide(String vendor, String origin, String destination) {
        SourcingApi sourcing = api(SourcingApi.class);
        UUID guideId = sourcing.defineRoutingGuide(new DefineGuideRequest()
                .orgUnitId(me.getOrgUnitId())
                .originTerminalId(ids.get(origin))
                .destinationTerminalId(ids.get(destination))
                .vehicleType(VEHICLE_TYPE), null).getId();
        sourcing.addRoutingGuideVendor(guideId, new AddVendorRequest()
                .vendorPartnerId(ids.get(vendor)).rankNo(1).responseSlaMinutes(60)
                .agreedRate(new BigDecimal("20000")), null);

        assertThat(sourcing.getRoutingGuide(guideId).getEntries())
                .singleElement()
                .satisfies(entry -> assertThat(entry.getVendorPartnerId()).isEqualTo(ids.get(vendor)));
    }

    @Given("{string} charges {double} per kg from {string} to {string}")
    public void rateCard(String vendor, double perKg, String origin, String destination) {
        SettlementApi settlement = api(SettlementApi.class);
        UUID tariffId = settlement.publishTariff(new PublishTariffRequest()
                .orgUnitId(me.getOrgUnitId())
                .code("CARD-" + salt)
                .vendorPartnerId(ids.get(vendor))
                .originTerminalId(ids.get(origin))
                .destinationTerminalId(ids.get(destination))
                .vehicleType(VEHICLE_TYPE)
                .currency("INR")
                .effectiveFrom(LocalDate.now(ZoneOffset.UTC).minusDays(30))
                .detentionFreeHours(new BigDecimal("2"))
                .detentionHourlyRate(BigDecimal.ZERO)
                .additionalDropFee(BigDecimal.ZERO)
                .minimumCharge(BigDecimal.ZERO), null).getId();
        settlement.addTariffSlab(tariffId, new AddSlabRequest()
                .minWeightKg(BigDecimal.ZERO)
                .rateBasis(AddSlabRequest.RateBasisEnum.PER_KG)
                .rateAmount(BigDecimal.valueOf(perKg)), null);
    }

    // ------------------------------------------------------------ demand

    @When("an order for {int} kg from {string} to {string} is raised and validated")
    public void order(int weightKg, String origin, String destination) {
        loadWeightKg = BigDecimal.valueOf(weightKg);
        OrdersApi orders = api(OrdersApi.class);
        UUID orderId = orders.raiseOrder(new RaiseOrderRequest()
                .orgUnitId(me.getOrgUnitId())
                .customerPartnerId(ids.get("customer"))
                .orderNo("SO-" + salt)
                .originTerminalId(ids.get(origin))
                .requestedPickupAt(OffsetDateTime.now(ZoneOffset.UTC).plusHours(4))
                .source(RaiseOrderRequest.SourceEnum.API), null).getId();
        orders.addOrderLine(orderId, new AddOrderLineRequest()
                .lineNo(1)
                .materialCode("STEEL-COIL")
                .materialDescription("Cold-rolled steel coil")
                .materialClass("GENERAL")
                .quantity(new BigDecimal("10"))
                .uom("COIL")
                .deadWeightKg(loadWeightKg)
                .consigneePartnerId(ids.get("consignee"))
                .destinationTerminalId(ids.get(destination)), null);
        orders.validateOrder(orderId, null);
        ids.put("order", orderId);
        ids.put("destination", ids.get(destination));
        ids.put("origin", ids.get(origin));
    }

    @When("its consignment is loaded onto {string} and the load is planned")
    public void load(String lorry) {
        PlanningApi planning = api(PlanningApi.class);
        List<IdResponse> consignments = planning.generateConsignments(ids.get("order"), null);
        assertThat(consignments).hasSize(1);

        UUID loadId = planning.openLoad(new OpenLoadRequest()
                .orgUnitId(me.getOrgUnitId())
                .loadNo("LD-" + salt)
                .originTerminalId(ids.get("origin"))
                .vehicleId(ids.get(lorry)), null).getId();
        planning.assignConsignment(loadId, new AssignConsignmentRequest()
                .consignmentId(consignments.get(0).getId()), null);
        planning.planLoad(loadId, null);
        ids.put("load", loadId);
    }

    @When("the load is offered and {string} accepts it")
    public void source(String vendor) {
        SourcingApi sourcing = api(SourcingApi.class);
        UUID allocationId = sourcing.allocateLoad(new AllocateRequest().loadId(ids.get("load")), null)
                .getId();
        sourcing.acceptOffer(allocationId, new com.lms.client.model.VendorResponseRequest()
                .vendorPartnerId(ids.get(vendor)), null);
        assertThat(sourcing.getAllocation(allocationId).getStatus()).isEqualTo("AWARDED");
    }

    // --------------------------------------------------------- execution

    @When("a trip is gated in, weighed and dispatched with {string} and {string}")
    public void runTrip(String lorry, String driver) {
        TripsApi trips = api(TripsApi.class);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID origin = ids.get("origin");

        tripId = trips.raiseTrip(new RaiseTripRequest().tripNo("TR-" + salt)
                .loadId(ids.get("load")).plannedStartAt(now.minusHours(7)), null).getId();
        trips.assignTrip(tripId, new AssignTripRequest()
                .vehicleId(ids.get(lorry)).driverId(ids.get(driver)), null);
        trips.gateIn(tripId, new GateInRequest().terminalId(origin).at(now.minusHours(6)), null);
        trips.recordWeighing(tripId, new WeighingRequest().terminalId(origin)
                .type(WeighingRequest.TypeEnum.TARE).weightKg(TARE_KG), null);
        trips.recordWeighing(tripId, new WeighingRequest().terminalId(origin)
                .type(WeighingRequest.TypeEnum.GROSS).weightKg(TARE_KG.add(loadWeightKg)), null);
        trips.markLoaded(tripId, new MilestoneRequest().at(now.minusHours(5).minusMinutes(10)), null);
        trips.attachTripDocument(tripId, new AttachTripDocumentRequest()
                .documentType("CONSIGNMENT_NOTE").documentRef("LR-" + salt), null);
        trips.attachTripDocument(tripId, new AttachTripDocumentRequest()
                .documentType("EWAY_BILL").documentRef("EW-" + salt), null);
        trips.dispatchTrip(tripId, new MilestoneRequest().at(now.minusHours(5)), null);

        assertThat(trips.getTrip(tripId).getStatus())
                .isEqualTo(com.lms.client.model.Trip.StatusEnum.DISPATCHED);
    }

    @When("{string} reports positions leaving {string} and reaching {string}")
    public void positions(String lorry, String origin, String destination) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        var result = api(TelematicsApi.class).ingestPings(new PingBatchRequest().pings(List.of(
                ping(lorry, now.minusHours(4), "19.1000", "72.9000"),
                ping(lorry, now.minusHours(3), "19.2000", "73.0000"),
                ping(lorry, now.minusHours(2), "19.3000", "73.1000"))), null);
        assertThat(result.getAccepted()).isEqualTo(3);
    }

    @Then("the trip is {string}")
    public void tripIs(String status) {
        assertThat(api(TripsApi.class).getTrip(tripId).getStatus().getValue()).isEqualTo(status);
    }

    @When("proof of delivery is accepted")
    public void complete() {
        api(TripsApi.class).completeTrip(tripId, new MilestoneRequest(), null);
    }

    /** The whole of the first scenario up to arrival, for scenarios about what happens next. */
    @Given("a trip carrying {int} kg has reached {string}")
    public void deliveredTrip(int weightKg, String destination) {
        order(weightKg, "ORIGIN", destination);
        load("LORRY");
        source("VEND");
        runTrip("LORRY", "Ravi");
        positions("LORRY", "ORIGIN", destination);
        tripIs("AT_DESTINATION");
    }

    // -------------------------------------------------------- settlement

    @Then("a freight bill of {int} is raised for the trip")
    public void billRaised(int amount) {
        FreightBill bill = billForTrip();
        assertThat(bill.getComputedAmount()).isEqualByComparingTo(BigDecimal.valueOf(amount));
        assertThat(bill.getStatus()).isEqualTo(FreightBill.StatusEnum.DRAFT);
    }

    @When("{string} invoices {int}")
    public void invoice(String vendor, int amount) {
        refusal = null;
        try {
            api(SettlementApi.class).matchFreightBill(billForTrip().getId(),
                    new MatchRequest().claimedAmount(BigDecimal.valueOf(amount)), null);
        } catch (FeignException e) {
            refusal = e;
        }
    }

    @Then("the bill is {string}")
    public void billIs(String status) {
        assertThat(refusal).as("the invoice should not have been refused").isNull();
        assertThat(billForTrip().getStatus().getValue()).isEqualTo(status);
    }

    @Then("the invoice is refused with {string}")
    public void invoiceRefused(String code) {
        assertThat(refusal).as("the invoice should have been refused").isNotNull();
        assertThat(refusal.status()).isEqualTo(409);
        assertThat(refusal.contentUTF8()).contains(code);
    }

    // ----------------------------------------------------- control tower

    @When("{string} is reported on the trip")
    public void report(String category) {
        ticketId = api(ControlTowerApi.class).raiseException(new RaiseExceptionRequest()
                .tripId(tripId)
                .category(ExceptionCategory.fromValue(category))
                .description(category + " reported by the driver"), null).getId();
    }

    @When("the ticket is assigned, investigated and resolved by me")
    public void resolveTicket() {
        ControlTowerApi tower = api(ControlTowerApi.class);
        tower.assignException(ticketId, new AssignExceptionRequest().assigneeUserId(me.getUserId()),
                null);
        tower.investigateException(ticketId, null, new ExceptionNoteRequest());
        tower.resolveException(ticketId, new ResolveExceptionRequest()
                .resolution("Seal intact at destination; count matches the consignment note"), null);

        // The resolver may not close a ticket that is holding money.
        assertThatThrownBy(() -> tower.closeException(ticketId, null, new ExceptionNoteRequest()))
                .isInstanceOfSatisfying(FeignException.class,
                        e -> assertThat(e.status()).isEqualTo(409));
    }

    /**
     * The one thing these scenarios provision in-process: a second user.
     *
     * <p>The API has no user-creation endpoint yet -- identity is seeded, not
     * self-served -- so the harness inserts one exactly as the demo seed does,
     * holding the seed's administrator role, and then signs in as them over
     * HTTP like anybody else.
     */
    @When("a second manager closes the ticket")
    public void secondManagerCloses() {
        String email = "manager-" + salt.toLowerCase() + "@demo.local";
        String password = "second manager " + salt;
        provisionAdministrator(email, password);

        AuthResult manager = signIn(email, password);
        client(manager.getAccessToken(), ControlTowerApi.class)
                .closeException(ticketId, null, new ExceptionNoteRequest().note("Agreed"));
    }

    // ----------------------------------------------------------- helpers

    private AuthResult signIn(String email, String password) {
        return client(null, AuthenticationApi.class).login(new LoginRequest()
                .tenantCode(DEMO_TENANT).email(email).password(password));
    }

    private UUID partner(String code, RegisterPartnerRequest.PartnerTypeEnum type) {
        return api(PartnersApi.class).registerPartner(new RegisterPartnerRequest()
                .orgUnitId(me.getOrgUnitId())
                .code(code + "-" + salt)
                .legalName(code + " " + salt + " Pvt Ltd")
                .partnerType(type)
                .taxId("TAX-" + code + "-" + salt), null).getId();
    }

    private UUID depot(String code, double lat, double lon) {
        return api(TerminalsApi.class).registerPointRadiusTerminal(
                new com.lms.client.model.RegisterPointRadiusTerminalRequest()
                        .orgUnitId(me.getOrgUnitId())
                        .code(code + "-" + salt)
                        .name(code)
                        .category(RegisterPointRadiusTerminalRequest.CategoryEnum.CUSTOMER_SITE)
                        .centre(new Point().lat(BigDecimal.valueOf(lat))
                                .lon(BigDecimal.valueOf(lon).add(lonShift)))
                        .radiusMetres(new BigDecimal("500"))
                        .dockCount(2)
                        .avgDwellMinutes(45), null).getId();
    }

    private Ping ping(String lorry, OffsetDateTime at, String lat, String lon) {
        return new Ping().vehicleId(ids.get(lorry)).recordedAt(at)
                .lat(new BigDecimal(lat)).lon(new BigDecimal(lon).add(lonShift))
                .speedKph(new BigDecimal("60")).accuracyM(new BigDecimal("5"))
                .ignitionOn(true).source("DEVICE");
    }

    private FreightBill billForTrip() {
        if (billId == null) {
            billId = api(SettlementApi.class).listFreightBills(null, null, 200).getItems().stream()
                    .filter(bill -> tripId.equals(bill.getTripId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No freight bill was raised for the trip"))
                    .getId();
        }
        return api(SettlementApi.class).getFreightBill(billId);
    }

    private void provisionAdministrator(String email, String password) {
        UUID tenantId = jdbc.sql("SELECT resolve_tenant_by_code(:code)")
                .param("code", DEMO_TENANT).query(UUID.class).single();
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        TenantContext.runWith(tenantId, null, () -> transactions.executeWithoutResult(status -> {
            UUID userId = UUID.randomUUID();
            jdbc.sql("""
                            INSERT INTO app_user (id, tenant_id, org_unit_id, email, password_hash,
                                                  full_name, status)
                            VALUES (:id, :tenantId, :orgUnitId, :email, :hash, 'Second Manager',
                                    'ACTIVE')
                            """)
                    .param("id", userId).param("tenantId", tenantId)
                    .param("orgUnitId", me.getOrgUnitId()).param("email", email)
                    .param("hash", passwordEncoder.encode(password))
                    .update();
            jdbc.sql("""
                            INSERT INTO user_role (tenant_id, user_id, role_id)
                            SELECT :tenantId, :userId, id FROM role WHERE code = 'ADMIN'
                            """)
                    .param("tenantId", tenantId).param("userId", userId)
                    .update();
        }));
    }

    private <T extends ApiClient.Api> T api(Class<T> type) {
        return client(me.getAccessToken(), type);
    }

    private <T extends ApiClient.Api> T client(String token, Class<T> type) {
        ApiClient apiClient = new ApiClient().setBasePath("http://localhost:" + port);
        if (token != null) {
            apiClient.getFeignBuilder().requestInterceptor(template ->
                    template.header("Authorization", "Bearer " + token));
        }
        return apiClient.buildClient(type);
    }
}
