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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.lms.acceptance.SpineFixtures;
import com.lms.acceptance.TestFixtures;
import com.lms.client.ApiClient;
import com.lms.client.api.ApprovalsApi;
import com.lms.client.api.ClientBillingApi;
import com.lms.client.api.CreditApi;
import com.lms.client.api.OrdersApi;
import com.lms.client.api.SettingsApi;
import com.lms.client.model.AddOrderLineRequest;
import com.lms.client.model.ApprovalPolicyRequest;
import com.lms.client.model.ClientContract;
import com.lms.client.model.ClientContractChargeAdditionRequest;
import com.lms.client.model.ClientContractDraftRequest;
import com.lms.client.model.ClientContractLane;
import com.lms.client.model.ClientContractLaneRequest;
import com.lms.client.model.ClientContractLaneSlab;
import com.lms.client.model.CreateSettingProfileRequest;
import com.lms.client.model.EffectiveSetting;
import com.lms.client.model.LaneRateBasis;
import com.lms.client.model.Order;
import com.lms.client.model.RaiseOrderRequest;
import com.lms.client.model.ReviseClientContractRequest;
import com.lms.client.model.SettingScopeType;
import com.lms.clientbilling.command.ClientChargeService;
import com.lms.execution.command.TripCommandService;
import com.lms.identity.access.SubjectAttributes;
import com.lms.identity.command.domain.AppUser;
import com.lms.identity.security.TokenService;
import com.lms.masterdata.command.FleetCommandService;
import com.lms.masterdata.command.RegisterTerminalCommand;
import com.lms.masterdata.command.TerminalCommandService;
import com.lms.masterdata.command.domain.BusinessPartner;
import com.lms.masterdata.command.domain.Terminal;
import com.lms.order.command.OrderSettingDefinitions;
import com.lms.settings.api.Settings;
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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract lanes and choosing the contract at order entry, over HTTP.
 *
 * <p>Lanes are part of a contract version: written and replaced while it is a
 * draft, frozen the moment it is not, copied into a charge-add successor. An
 * order then names the version in force on its date and the lane it travels on,
 * or is refused by name; a transport charge is priced from that lane; a
 * contract-scoped setting follows the contract across its versions. Every
 * scenario is its own tenant, so what one writes the others never see.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContractLanesApiTest {

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

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    /** The desk: drafts and activates contracts, raises orders, reads credit and settings. */
    private static final List<String> DESK = List.of(
            "ORDER_CREATE", "ORDER_READ", "ORDER_UPDATE", "PARTNER_READ", "CLIENT_CONTRACT_MANAGE",
            "CLIENT_BILLING_READ", "CLIENT_BILLING_RUN", "SETTINGS_MANAGE", "SETTINGS_READ",
            "APPROVAL_POLICY_MANAGE");

    private static final List<String> SETUP = List.of(
            "PARTNER_CREATE", "PARTNER_UPDATE", "TERMINAL_CREATE", "VEHICLE_CREATE",
            "ORDER_CREATE", "ORDER_UPDATE", "LOAD_CREATE", "LOAD_ALLOCATE", "TRIP_EXECUTE");

    @LocalServerPort
    private int port;

    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private FleetCommandService fleet;
    @Autowired
    private TerminalCommandService terminals;
    @Autowired
    private TokenService tokens;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private SpineFixtures spine;
    @Autowired
    private TripCommandService trips;
    @Autowired
    private ClientChargeService clientCharges;
    @Autowired
    private Settings settings;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private String salt;
    private String orgPath;
    private UUID tenantId;
    private UUID orgUnitId;
    private AppUser desk;
    private UUID client;
    private UUID consignee;
    private UUID otherConsignee;
    private UUID origin;
    private UUID destination;
    private UUID elsewhere;

    @BeforeEach
    void aClientWithTerminals() {
        salt = UUID.randomUUID().toString().substring(0, 8);
        orgPath = "/acme-" + salt + "/";
        tenantId = fixtures.createTenant("acme-" + salt, "Acme Logistics");
        orgUnitId = fixtures.createOrgUnit(tenantId, orgPath, "Acme HQ", "HQ", null);
        desk = user(tenantId, orgUnitId, orgPath, "desk-" + salt + "@acme.test");
        asSetup(tenantId, orgPath, () -> {
            client = partner("CL-" + salt, BusinessPartner.PartnerType.CUSTOMER);
            consignee = partner("CONS-" + salt, BusinessPartner.PartnerType.CONSIGNEE);
            otherConsignee = partner("OTHER-" + salt, BusinessPartner.PartnerType.CONSIGNEE);
            origin = depot("ORG-" + salt, 19.10, 72.90);
            destination = depot("DST-" + salt, 28.40, 77.00);
            elsewhere = depot("ELS-" + salt, 12.97, 77.59);
            return null;
        });
        // These scenarios are about lanes, not about who verifies a contract.
        ApprovalsApi approvals = api(ApprovalsApi.class, desk);
        approvals.setApprovalPolicy("CONTRACT_ACTIVATION", new ApprovalPolicyRequest().required(false)
                .decidePermissions(java.util.List.of("CLIENT_CONTRACT_APPROVE")), null);
        approvals.setApprovalPolicy("CONTRACT_CHARGE_ADD", new ApprovalPolicyRequest().required(false)
                .decidePermissions(java.util.List.of("CLIENT_CONTRACT_APPROVE")), null);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------ lane CRUD

    @Test
    @DisplayName("lanes are written and replaced while the version is a draft, frozen once it is active, and copied into a charge-add successor")
    void lanesFollowTheVersion() {
        ClientBillingApi billing = api(ClientBillingApi.class, desk);
        UUID id = billing.createClientContractDraft(draft("L-1", TODAY.minusDays(30))
                .directOrdersAllowed(false).allowedLoadTypes(List.of("32ft", "open"))
                .lanes(List.of(lane("MUM-DEL", PER_TONNE, "9000").transitDays(3)
                        .slabs(List.of(slab("0", "1000", LaneRateBasis.PER_KG, "12"),
                                slab("1000", null, LaneRateBasis.PER_TONNE, "8000")))
                        .consigneeIds(List.of(consignee)).loadType("32ft")
                        .freightMode(ClientContractLaneRequest.FreightModeEnum.FTL))), null).getId();

        ClientContract read = billing.getClientContract(id);
        assertThat(read.getDirectOrdersAllowed()).isFalse();
        assertThat(read.getAllowedLoadTypes()).containsExactly("32FT", "OPEN");
        assertThat(read.getFamilyId()).isEqualTo(id);
        assertThat(read.getLanes()).hasSize(1);
        ClientContractLane stored = read.getLanes().get(0);
        assertThat(stored.getLaneCode()).isEqualTo("MUM-DEL");
        assertThat(stored.getOriginTerminalId()).isEqualTo(origin);
        assertThat(stored.getDestinationTerminalId()).isEqualTo(destination);
        assertThat(stored.getRateBasis()).isEqualTo(LaneRateBasis.PER_TONNE);
        assertThat(stored.getLoadType()).isEqualTo("32FT");
        assertThat(stored.getTransitDays()).isEqualTo(3);
        assertThat(stored.getSlabs()).extracting(slab -> slab.getMinWeightKg().intValue())
                .containsExactly(0, 1000);
        assertThat(stored.getConsigneeIds()).containsExactly(consignee);
        assertThat(audited("CLIENT_CONTRACT_DRAFTED", id)).isEqualTo(1);
        assertThat(auditAfter("CLIENT_CONTRACT_DRAFTED", id)).contains("MUM-DEL");

        // The checklist offers the lanes rule, which the default does not require.
        assertThat(billing.getClientContractChecklist(id)).filteredOn(i -> i.getCode().getValue().equals("LANES"))
                .singleElement().satisfies(i -> {
                    assertThat(i.getRequired()).isFalse();
                    assertThat(i.getPassed()).isTrue();
                });

        // A repeated code, and an end that is both a terminal and a zone, are refused.
        assertProblem(() -> billing.reviseClientContractDraft(etag(read), id, revise("L-1", TODAY.minusDays(30))
                .lanes(List.of(lane("A", PER_KG, "5"), lane("a", PER_KG, "6"))), null), 409,
                "client-contract-lane-duplicated");
        assertProblem(() -> billing.reviseClientContractDraft(etag(read), id, revise("L-1", TODAY.minusDays(30))
                .lanes(List.of(lane("A", PER_KG, "5").originZone("NORTH"))), null), 400, "invalid-argument");
        assertProblem(() -> billing.reviseClientContractDraft(etag(read), id, revise("L-1", TODAY.minusDays(30))
                .lanes(List.of(lane("A", PER_KG, null))), null), 400, "invalid-argument");

        // Replaced whole; left out, they are kept.
        billing.reviseClientContractDraft(etag(read), id, revise("L-1", TODAY.minusDays(30))
                .lanes(List.of(lane("MUM-DEL", PER_KG, "6.5"), lane("MUM-ZONE", PER_KG, "7")
                        .destinationTerminalId(null).destinationZone("north"))), null);
        ClientContract replaced = billing.getClientContract(id);
        assertThat(replaced.getLanes()).extracting(ClientContractLane::getLaneCode)
                .containsExactly("MUM-DEL", "MUM-ZONE");
        assertThat(replaced.getLanes().get(1).getDestinationZone()).isEqualTo("NORTH");
        // The allowances were not restated, so they stand.
        assertThat(replaced.getDirectOrdersAllowed()).isFalse();
        billing.reviseClientContractDraft(etag(replaced), id, revise("L-1B", TODAY.minusDays(30)), null);
        assertThat(billing.getClientContract(id).getLanes()).hasSize(2);

        // In force, the lanes are frozen: the API refuses, and so does the database.
        billing.requestClientContractVerification(etag(billing.getClientContract(id)), id, null);
        ClientContract active = billing.getClientContract(id);
        assertThat(active.getStatus()).isEqualTo(ClientContract.StatusEnum.ACTIVE);
        assertProblem(() -> billing.reviseClientContractDraft(etag(active), id, revise("L-1B", TODAY.minusDays(30))
                .lanes(List.of()), null), 409, "client-contract-not-draft");
        assertThatThrownBy(() -> jdbc.sql("UPDATE client_contract_lane SET rate = 1 WHERE contract_id = :id")
                .param("id", id).update()).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("cannot be changed");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM client_contract_lane WHERE contract_id = :id")
                .param("id", id).update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO client_contract_lane (id, tenant_id, contract_id, lane_code, origin_terminal_id,
                    destination_terminal_id, rate_basis, rate)
                VALUES (gen_random_uuid(), :tenant, :id, 'SNEAK', :origin, :destination, 'PER_KG', 1)
                """).param("tenant", tenantId).param("id", id).param("origin", origin)
                .param("destination", destination).update()).isInstanceOf(DataAccessException.class);
        // Nor can the allowances change underneath.
        assertThatThrownBy(() -> jdbc.sql("UPDATE client_contract SET direct_orders_allowed = true WHERE id = :id")
                .param("id", id).update()).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("cannot be edited");

        // A charge-add successor carries the lanes, under new identities, in the same family.
        billing.requestClientContractChargeAddition(id, new ClientContractChargeAdditionRequest()
                .serviceCode("PACK").unit("UNIT").rate(BigDecimal.ONE).effectiveFrom(TODAY.plusDays(10))
                .reason("packing from next week"), null);
        List<ClientContract> versions = billing.listClientContracts(client);
        assertThat(versions).hasSize(2);
        ClientContract successor = versions.stream().filter(v -> !v.getId().equals(id)).findFirst().orElseThrow();
        assertThat(successor.getFamilyId()).isEqualTo(id);
        assertThat(successor.getDirectOrdersAllowed()).isFalse();
        assertThat(successor.getAllowedLoadTypes()).containsExactly("32FT", "OPEN");
        assertThat(successor.getLanes()).extracting(ClientContractLane::getLaneCode)
                .containsExactly("MUM-DEL", "MUM-ZONE");
        assertThat(successor.getLanes()).extracting(ClientContractLane::getId)
                .doesNotContainAnyElementsOf(active.getLanes().stream().map(ClientContractLane::getId).toList());
    }

    @Test
    @DisplayName("a lane may name only terminals and partners the tenant can see, and another tenant never sees its lanes")
    void lanesStayInsideTheTenant() {
        UUID[] foreign = new UUID[1];
        String otherSalt = UUID.randomUUID().toString().substring(0, 8);
        UUID otherTenant = fixtures.createTenant("globex-" + otherSalt, "Globex");
        UUID otherUnit = fixtures.createOrgUnit(otherTenant, "/globex-" + otherSalt + "/", "Globex HQ", "HQ", null);
        asSetup(otherTenant, "/globex-" + otherSalt + "/", () -> {
            foreign[0] = terminals.register(new RegisterTerminalCommand.PointRadius(otherUnit, "GX-" + otherSalt,
                    "GX", Terminal.FunctionalCategory.CUSTOMER_SITE, new LatLon(10, 70), 500, 2, null, null, 45,
                    List.of()));
            return null;
        });
        ClientBillingApi billing = api(ClientBillingApi.class, desk);
        assertProblem(() -> billing.createClientContractDraft(draft("X-1", TODAY.minusDays(5))
                .lanes(List.of(lane("F", PER_KG, "5").originTerminalId(foreign[0]))), null), 404, null);
        assertProblem(() -> billing.createClientContractDraft(draft("X-2", TODAY.minusDays(5))
                .lanes(List.of(lane("F", PER_KG, "5").consigneeIds(List.of(UUID.randomUUID())))), null), 404, null);
        // Nothing was half-written by the refused attempts.
        assertThat(count("SELECT count(*) FROM client_contract_lane WHERE tenant_id = :id", tenantId)).isZero();

        // A contract of this tenant's, and another tenant's desk and orders never see its lanes.
        UUID id = activeContract("T-1", List.of(lane("OWN", PER_KG, "5")), true, null);
        AppUser outsider = user(otherTenant, otherUnit, "/globex-" + otherSalt + "/", "desk-" + otherSalt + "@globex.test");
        ClientBillingApi theirs = clientWithToken(ClientBillingApi.class, tokens.issueAccessToken(outsider,
                "/globex-" + otherSalt + "/", DESK).value());
        assertThat(theirs.listClientContractLanes(client, TODAY)).isEmpty();
        assertProblem(() -> theirs.getClientContract(id), 404, null);
        assertThat(billing.listClientContractLanes(client, TODAY)).extracting(ClientContractLane::getLaneCode)
                .containsExactly("OWN");
    }

    // -------------------------------------------------------- order entry

    @Test
    @DisplayName("an order names the contract in force and the lane it travels on, chosen at the desk or found at validation")
    void ordersResolveContractAndLane() {
        UUID contract = activeContract("O-1", List.of(
                lane("MUM-DEL", PER_KG, "5").transitDays(3).loadType("32ft")), true, null);
        OrdersApi orders = api(OrdersApi.class, desk);
        UUID laneId = api(ClientBillingApi.class, desk).listClientContractLanes(client, TODAY).get(0).getId();

        // Chosen at entry: recorded at once, with the promised days.
        UUID chosen = orders.raiseOrder(raise("CH-1").contractLaneId(laneId).loadType("32ft"), null).getId();
        Order read = orders.getOrder(chosen);
        assertThat(read.getContractId()).isEqualTo(contract);
        assertThat(read.getContractCode()).isEqualTo("O-1");
        assertThat(read.getContractLaneId()).isEqualTo(laneId);
        assertThat(read.getContractLaneCode()).isEqualTo("MUM-DEL");
        assertThat(read.getLoadType()).isEqualTo("32FT");
        assertThat(read.getTransitDays()).isEqualTo(3);
        orders.addOrderLine(chosen, line(1, "100", consignee, destination), null);
        orders.validateOrder(chosen, null);
        Order validated = orders.getOrder(chosen);
        assertThat(validated.getStatus()).isEqualTo("VALIDATED");
        assertThat(validated.getContractLaneId()).isEqualTo(laneId);

        // Found at validation, from the lines.
        UUID found = orders.raiseOrder(raise("FD-1"), null).getId();
        assertThat(orders.getOrder(found).getContractId()).isEqualTo(contract);
        assertThat(orders.getOrder(found).getContractLaneId()).isNull();
        orders.addOrderLine(found, line(1, "100", consignee, destination), null);
        orders.validateOrder(found, null);
        assertThat(orders.getOrder(found).getContractLaneCode()).isEqualTo("MUM-DEL");
        assertThat(auditAfter("ORDER_VALIDATED", found)).contains(laneId.toString());

        // A lane that is not one of the contract's is refused when it is chosen.
        assertProblem(() -> orders.raiseOrder(raise("BAD-1").contractLaneId(UUID.randomUUID()), null), 409,
                "order-outside-contract-lanes");

        // A customer with no contract in force is raised for and validated as before.
        UUID plainCustomer = asSetup(tenantId, orgPath, () -> partner("PLAIN-" + salt,
                BusinessPartner.PartnerType.CUSTOMER));
        UUID plain = orders.raiseOrder(raise("PL-1").customerPartnerId(plainCustomer), null).getId();
        orders.addOrderLine(plain, line(1, "10", consignee, elsewhere), null);
        orders.validateOrder(plain, null);
        Order unpriced = orders.getOrder(plain);
        assertThat(unpriced.getStatus()).isEqualTo("VALIDATED");
        assertThat(unpriced.getContractId()).isNull();
        assertThat(unpriced.getContractLaneId()).isNull();
        // A lane chosen for a customer with no contract is refused: there is nothing to choose it from.
        assertProblem(() -> orders.raiseOrder(raise("PL-2").customerPartnerId(plainCustomer).contractLaneId(laneId),
                null), 409, "order-outside-contract-lanes");
    }

    @Test
    @DisplayName("an order outside the lanes, with a load type or a consignee the contract does not allow, is refused by name")
    void refusalsByName() {
        activeContract("R-1", List.of(lane("MUM-DEL", PER_KG, "5").consigneeIds(List.of(consignee))),
                false, List.of("32ft"));
        OrdersApi orders = api(OrdersApi.class, desk);

        // Load type: refused when the order is raised.
        assertProblem(() -> orders.raiseOrder(raise("LT-1").loadType("reefer"), null), 409,
                "order-load-type-not-allowed");
        UUID listed = orders.raiseOrder(raise("LT-2").loadType("32FT"), null).getId();
        assertThat(orders.getOrder(listed).getLoadType()).isEqualTo("32FT");

        // Outside the lanes: a destination no lane runs to.
        UUID outside = orders.raiseOrder(raise("OUT-1"), null).getId();
        orders.addOrderLine(outside, line(1, "100", consignee, elsewhere), null);
        assertProblem(() -> orders.validateOrder(outside, null), 409, "order-outside-contract-lanes");
        assertThat(orders.getOrder(outside).getStatus()).isEqualTo("DRAFT");

        // A consignee the lane does not list.
        UUID wrongConsignee = orders.raiseOrder(raise("CN-1"), null).getId();
        orders.addOrderLine(wrongConsignee, line(1, "100", otherConsignee, destination), null);
        assertProblem(() -> orders.validateOrder(wrongConsignee, null), 409, "order-consignee-not-allowed");

        // The listed one passes.
        UUID fine = orders.raiseOrder(raise("OK-1"), null).getId();
        orders.addOrderLine(fine, line(1, "100", consignee, destination), null);
        orders.validateOrder(fine, null);
        assertThat(orders.getOrder(fine).getContractLaneCode()).isEqualTo("MUM-DEL");

        // A chosen lane is held to: the order's lines must fit it.
        UUID laneId = api(ClientBillingApi.class, desk).listClientContractLanes(client, TODAY).get(0).getId();
        UUID chosen = orders.raiseOrder(raise("CHO-1").contractLaneId(laneId), null).getId();
        orders.addOrderLine(chosen, line(1, "100", consignee, elsewhere), null);
        assertProblem(() -> orders.validateOrder(chosen, null), 409, "order-outside-contract-lanes");
    }

    @Test
    @DisplayName("a contract that allows direct orders accepts an order no lane serves, and records no lane")
    void directOrdersOverride() {
        UUID contract = activeContract("D-1", List.of(lane("MUM-DEL", PER_KG, "5")), true, null);
        OrdersApi orders = api(OrdersApi.class, desk);
        UUID id = orders.raiseOrder(raise("DIR-1"), null).getId();
        orders.addOrderLine(id, line(1, "100", consignee, elsewhere), null);
        orders.validateOrder(id, null);
        Order order = orders.getOrder(id);
        assertThat(order.getStatus()).isEqualTo("VALIDATED");
        assertThat(order.getContractId()).isEqualTo(contract);
        assertThat(order.getContractLaneId()).isNull();
    }

    @Test
    @DisplayName("an amendment judges the order again against the lanes")
    void amendmentIsJudgedAgain() {
        activeContract("A-1", List.of(lane("MUM-DEL", PER_KG, "5")), false, null);
        OrdersApi orders = api(OrdersApi.class, desk);
        UUID id = orders.raiseOrder(raise("AM-1"), null).getId();
        orders.addOrderLine(id, line(1, "100", consignee, destination), null);
        orders.validateOrder(id, null);
        Order before = orders.getOrder(id);

        com.lms.client.model.AmendOrderRequest toElsewhere = new com.lms.client.model.AmendOrderRequest()
                .lines(List.of(line(1, "100", consignee, elsewhere)));
        assertProblem(() -> orders.amendOrder(String.valueOf(before.getVersion()), id, toElsewhere, null), 409,
                "order-outside-contract-lanes");
        assertThat(orders.getOrder(id).getContractLaneCode()).isEqualTo("MUM-DEL");
    }

    // ------------------------------------------------------------ pricing

    @Test
    @DisplayName("a transport charge is priced from the lane the order was raised on, not from the per-kilo rate")
    void pricingFromTheLane() {
        // 100 kg on a lane at 9000 a tonne is 900; the contract's own per-kilo rate would make it 500.
        UUID contract = activeContract("P-1", List.of(lane("MUM-DEL", PER_TONNE, "9000")), true, null);
        UUID load = asSetup(tenantId, orgPath, () -> {
            UUID vendor = partner("VEN-" + salt, BusinessPartner.PartnerType.VENDOR);
            fleet.transitionPartner(vendor, BusinessPartner.PartnerStatus.PENDING_VERIFICATION);
            fleet.transitionPartner(vendor, BusinessPartner.PartnerStatus.ACTIVE);
            UUID vehicle = fleet.registerVehicle(orgUnitId, null, "LN-" + salt, "TRUCK", "RIGID", "2-AXLE",
                    new BigDecimal("26000"), new BigDecimal("6000"), new BigDecimal("60"), false, false);
            return spine.awardedLoad(orgUnitId, "LD-" + salt, client, origin, consignee, destination, vehicle,
                    vendor, new BigDecimal("100"), false);
        });
        assertThat(count("SELECT count(*) FROM sales_order WHERE tenant_id = :id AND contract_lane_id IS NOT NULL",
                tenantId)).isEqualTo(1);

        asSetup(tenantId, orgPath, () -> {
            UUID trip = trips.raiseTrip("TR-" + salt, load, Instant.now());
            return clientCharges.captureTransport(trip, "TR-" + salt, load, Instant.now(), Instant.now());
        });
        assertThat(jdbc.sql("SELECT contract_lane_id FROM client_charge WHERE tenant_id = :id AND service_type = 'TRANSPORT'")
                .param("id", tenantId).query(UUID.class).single())
                .isEqualTo(api(ClientBillingApi.class, desk).getClientContract(contract).getLanes().get(0).getId());
        CreditApi credit = api(CreditApi.class, desk);
        assertThat(credit.getCreditPosition(client).getExposure().getUnbilledCharges()).isEqualByComparingTo("900");
    }

    // ------------------------------------------------------- transit days

    @Test
    @DisplayName("transit days are the lane's, else the distance over the tenant's kilometres per day, and give the expected delivery date")
    void transitDays() {
        OrdersApi orders = api(OrdersApi.class, desk);
        OffsetDateTime pickup = OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).withHour(10);

        // No contract: the straight-line distance (about 1,150 km) over the default 400 km a day.
        UUID byDistance = orders.raiseOrder(raise("TD-1").requestedPickupAt(pickup), null).getId();
        assertThat(orders.getOrder(byDistance).getTransitDays()).isNull();
        orders.addOrderLine(byDistance, line(1, "100", consignee, destination), null);
        orders.validateOrder(byDistance, null);
        Order three = orders.getOrder(byDistance);
        assertThat(three.getTransitDays()).isEqualTo(3);
        assertThat(three.getExpectedDeliveryDate()).isEqualTo(pickup.toLocalDate().plusDays(3));

        // The tenant moves freight faster: 1,000 km a day makes it two days.
        // The port joins the caller's transaction (ADR 0006), so the setup opens one, scope first.
        asSetup(tenantId, orgPath, () -> new org.springframework.transaction.support.TransactionTemplate(
                transactionManager).execute(status -> {
                    settings.setOrgDefaults(Map.of(OrderSettingDefinitions.TRANSIT_KM_PER_DAY, 1000));
                    return null;
                }));
        UUID faster = orders.raiseOrder(raise("TD-2").requestedPickupAt(pickup), null).getId();
        orders.addOrderLine(faster, line(1, "100", consignee, destination), null);
        orders.validateOrder(faster, null);
        assertThat(orders.getOrder(faster).getTransitDays()).isEqualTo(2);

        // A lane that names days wins over distance.
        activeContract("TD-C", List.of(lane("MUM-DEL", PER_KG, "5").transitDays(5)), true, null);
        UUID onLane = orders.raiseOrder(raise("TD-3").requestedPickupAt(pickup), null).getId();
        orders.addOrderLine(onLane, line(1, "100", consignee, destination), null);
        orders.validateOrder(onLane, null);
        Order five = orders.getOrder(onLane);
        assertThat(five.getTransitDays()).isEqualTo(5);
        assertThat(five.getExpectedDeliveryDate()).isEqualTo(pickup.toLocalDate().plusDays(5));
    }

    // ------------------------------------------------------ settings scope

    @Test
    @DisplayName("a contract-scoped setting names the contract family, beats the customer's and survives a successor")
    void contractScope() {
        UUID id = activeContract("S-1", List.of(lane("MUM-DEL", PER_KG, "5")), true, null);
        SettingsApi settingsApi = api(SettingsApi.class, desk);
        UUID family = api(ClientBillingApi.class, desk).getClientContract(id).getFamilyId();

        settingsApi.createSettingProfile(new CreateSettingProfileRequest().scopeType(SettingScopeType.CUSTOMER)
                .scopeId(client).name("Customer terms").values(Map.of("order.close.tolerancePercent", 3)), null);
        settingsApi.createSettingProfile(new CreateSettingProfileRequest().scopeType(SettingScopeType.CONTRACT)
                .scopeId(family).name("Contract terms").values(Map.of("order.close.tolerancePercent", 7)), null);

        assertThat(tolerance(settingsApi, null)).isEqualByComparingTo("3");
        assertThat(tolerance(settingsApi, family)).isEqualByComparingTo("7");
        EffectiveSetting entry = settingsApi.getEffectiveSettings(null, client, null, null, null, null, null, null,
                family).getItems().stream().filter(e -> e.getKey().equals("order.close.tolerancePercent"))
                .findFirst().orElseThrow();
        assertThat(entry.getSource().getScopeType()).isEqualTo(SettingScopeType.CONTRACT);
        assertThat(entry.getSource().getLabel()).isEqualTo("Contract: Contract terms");

        // A contract that does not exist is not found, and a contract has one profile.
        assertProblem(() -> settingsApi.createSettingProfile(new CreateSettingProfileRequest()
                .scopeType(SettingScopeType.CONTRACT).scopeId(UUID.randomUUID()).name("Nowhere")
                .values(Map.of("order.close.tolerancePercent", 1)), null), 404, null);
        assertProblem(() -> settingsApi.createSettingProfile(new CreateSettingProfileRequest()
                .scopeType(SettingScopeType.CONTRACT).scopeId(family).name("Second")
                .values(Map.of("order.close.autoFulfil", false)), null), 409, "setting-profile-scope-taken");

        // A new version of the contract is still the same family, so the profile still applies.
        api(ClientBillingApi.class, desk).requestClientContractChargeAddition(id,
                new ClientContractChargeAdditionRequest().serviceCode("PACK").unit("UNIT").rate(BigDecimal.ONE)
                        .effectiveFrom(TODAY.plusDays(5)).reason("packing"), null);
        UUID successor = api(ClientBillingApi.class, desk).listClientContracts(client).stream()
                .filter(v -> !v.getId().equals(id)).findFirst().orElseThrow().getId();
        assertThat(api(ClientBillingApi.class, desk).getClientContract(successor).getFamilyId()).isEqualTo(family);
        assertThat(tolerance(settingsApi, family)).isEqualByComparingTo("7");

        // An order raised under the contract records its family, which is what the close policy reads.
        OrdersApi orders = api(OrdersApi.class, desk);
        UUID order = orders.raiseOrder(raise("SC-1"), null).getId();
        assertThat(jdbc.sql("SELECT contract_family_id FROM sales_order WHERE id = :id").param("id", order)
                .query(UUID.class).single()).isEqualTo(family);
    }

    // ------------------------------------------------------------ helpers

    private static final LaneRateBasis PER_KG = LaneRateBasis.PER_KG;
    private static final LaneRateBasis PER_TONNE = LaneRateBasis.PER_TONNE;

    private BigDecimal tolerance(SettingsApi settingsApi, UUID contract) {
        return settingsApi.getEffectiveSettings(null, client, null, null, null, null, null, null, contract).getItems()
                .stream().filter(e -> e.getKey().equals("order.close.tolerancePercent")).findFirst().orElseThrow()
                .getValue() instanceof Number n ? new BigDecimal(n.toString()) : BigDecimal.ZERO;
    }

    private ClientContractLaneRequest lane(String code, LaneRateBasis basis, String rate) {
        return new ClientContractLaneRequest().laneCode(code).originTerminalId(origin)
                .destinationTerminalId(destination).rateBasis(basis)
                .rate(rate == null ? null : new BigDecimal(rate));
    }

    private static ClientContractLaneSlab slab(String min, String max, LaneRateBasis basis, String rate) {
        return new ClientContractLaneSlab().minWeightKg(new BigDecimal(min))
                .maxWeightKg(max == null ? null : new BigDecimal(max)).rateBasis(basis).rate(new BigDecimal(rate));
    }

    private ClientContractDraftRequest draft(String code, LocalDate from) {
        return new ClientContractDraftRequest().orgUnitId(orgUnitId).code(code).clientPartnerId(client)
                .effectiveFrom(from).transportRatePerKg(new BigDecimal("5.00")).transportMinimumCharge(BigDecimal.ZERO)
                .storageRatePerPalletDay(new BigDecimal("12")).handlingInRatePerPallet(new BigDecimal("25"))
                .handlingOutRatePerPallet(new BigDecimal("30"));
    }

    private ReviseClientContractRequest revise(String code, LocalDate from) {
        return new ReviseClientContractRequest().orgUnitId(orgUnitId).code(code).effectiveFrom(from)
                .transportRatePerKg(new BigDecimal("5.00")).transportMinimumCharge(BigDecimal.ZERO)
                .storageRatePerPalletDay(new BigDecimal("12")).handlingInRatePerPallet(new BigDecimal("25"))
                .handlingOutRatePerPallet(new BigDecimal("30"))
                // The generated client starts every list empty, which the API reads as "replace with
                // none"; a revision that leaves lanes and load types out says so, as a browser does.
                .allowedLoadTypes(null).lanes(null);
    }

    /** A contract in force since a month ago, with the lanes, direct-order flag and load types given. */
    private UUID activeContract(String code, List<ClientContractLaneRequest> lanes, boolean direct,
                                List<String> loadTypes) {
        return activate(client, code, lanes, direct, loadTypes);
    }

    private UUID activate(UUID forClient, String code, List<ClientContractLaneRequest> lanes, boolean direct,
                          List<String> loadTypes) {
        ClientBillingApi billing = api(ClientBillingApi.class, desk);
        UUID id = billing.createClientContractDraft(draft(code, TODAY.minusDays(30)).clientPartnerId(forClient)
                .directOrdersAllowed(direct).allowedLoadTypes(loadTypes).lanes(lanes), null).getId();
        billing.requestClientContractVerification(etag(billing.getClientContract(id)), id, null);
        assertThat(billing.getClientContract(id).getStatus()).isEqualTo(ClientContract.StatusEnum.ACTIVE);
        return id;
    }

    private RaiseOrderRequest raise(String orderNo) {
        return new RaiseOrderRequest().orgUnitId(orgUnitId).customerPartnerId(client)
                .orderNo(orderNo + "-" + salt).originTerminalId(origin);
    }

    private static AddOrderLineRequest line(int lineNo, String weightKg, UUID forConsignee, UUID to) {
        return new AddOrderLineRequest().lineNo(lineNo).materialCode("MAT-" + lineNo)
                .materialDescription("General cargo").materialClass("GENERAL").quantity(BigDecimal.ONE).uom("EA")
                .deadWeightKg(new BigDecimal(weightKg)).consigneePartnerId(forConsignee).destinationTerminalId(to);
    }

    private static String etag(ClientContract contract) {
        return "\"" + contract.getVersion() + "\"";
    }

    private long audited(String action, UUID resourceId) {
        return jdbc.sql("SELECT count(*) FROM audit_log WHERE action = :a AND resource_id = :id")
                .param("a", action).param("id", resourceId).query(Long.class).single();
    }

    private String auditAfter(String action, UUID resourceId) {
        return jdbc.sql("SELECT after_state::text FROM audit_log WHERE action = :a AND resource_id = :id")
                .param("a", action).param("id", resourceId).query(String.class).list().stream()
                .collect(Collectors.joining(" "));
    }

    private long count(String sql, UUID id) {
        return jdbc.sql(sql).param("id", id).query(Long.class).single();
    }

    private static void assertProblem(Runnable call, int status, String code) {
        FeignException e = catchFeign(call);
        assertThat(e.status()).as(e.contentUTF8()).isEqualTo(status);
        if (code != null) {
            assertThat(e.contentUTF8()).contains("\"code\":\"" + code + "\"");
        }
    }

    private static FeignException catchFeign(Runnable call) {
        try {
            call.run();
        } catch (FeignException e) {
            return e;
        }
        throw new AssertionError("Expected the call to be refused");
    }

    private <T extends ApiClient.Api> T api(Class<T> type, AppUser who) {
        return clientWithToken(type, tokens.issueAccessToken(who, orgPath, DESK,
                new SubjectAttributes(null, null, null, null)).value());
    }

    private <T extends ApiClient.Api> T clientWithToken(Class<T> type, String token) {
        ApiClient apiClient = new ApiClient().setBasePath("http://localhost:" + port);
        apiClient.getFeignBuilder().requestInterceptor(template ->
                template.header("Authorization", "Bearer " + token));
        return apiClient.buildClient(type);
    }

    private AppUser user(UUID forTenant, UUID unit, String path, String email) {
        UUID id = fixtures.createUser(forTenant, unit, path, email, "unused");
        return new AppUser(id, forTenant, unit, email, "unused", email,
                AppUser.UserStatus.ACTIVE, null, 0, null, null, null, Instant.now(), Instant.now());
    }

    private UUID partner(String code, BusinessPartner.PartnerType type) {
        return fleet.registerPartner(orgUnitId, code, code, type, "TAX-" + code);
    }

    private UUID depot(String code, double lat, double lon) {
        return terminals.register(new RegisterTerminalCommand.PointRadius(orgUnitId, code, code,
                Terminal.FunctionalCategory.CUSTOMER_SITE, new LatLon(lat, lon), 500, 2, null,
                null, 45, List.of()));
    }

    private <T> T asSetup(UUID forTenant, String path, Supplier<T> action) {
        Set<SimpleGrantedAuthority> authorities = SETUP.stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toSet());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("setup", "n/a", authorities));
        try {
            return TenantContext.callWith(forTenant, path, action);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
