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
import java.time.ZoneOffset;
import java.util.List;
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
import com.lms.client.model.AddOrderLineRequest;
import com.lms.client.model.AmendOrderRequest;
import com.lms.client.model.ClientBillingRunRequest;
import com.lms.client.model.ClientServiceType;
import com.lms.client.model.CreditLimitChange;
import com.lms.client.model.ApprovalPolicyRequest;
import com.lms.client.model.ClientContractDraftRequest;
import com.lms.client.model.CreditLimitChangeRequest;
import com.lms.client.model.CreditLimitDecisionRequest;
import com.lms.client.model.CreditOverrideRequest;
import com.lms.client.model.CreditPosition;
import com.lms.client.model.RaiseOrderRequest;
import com.lms.client.model.ServiceUsageRequest;
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
 * Customer standing and credit limits over HTTP, through the generated client.
 *
 * <p>The limit is set the only way it can be -- one person asks, another
 * approves -- and then bites where it should: order validation, line add,
 * amendment and the client billing run, each refused with
 * {@code credit-limit-exceeded} naming the figures, and each let through once
 * by an override.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CreditApiTest {

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

    /** The order and billing desk: asks for limits and may override them. */
    private static final List<String> DESK = List.of(
            "ORDER_CREATE", "ORDER_READ", "ORDER_UPDATE", "PARTNER_READ",
            "CLIENT_CONTRACT_MANAGE", "CLIENT_BILLING_RUN", "CLIENT_BILLING_READ",
            "CREDIT_LIMIT_REQUEST", "CREDIT_OVERRIDE");

    /** Credit control: approves limits. */
    private static final List<String> CHECKER = List.of("PARTNER_READ", "CREDIT_LIMIT_APPROVE");

    private static final List<String> SETUP = List.of(
            "PARTNER_CREATE", "PARTNER_UPDATE", "TERMINAL_CREATE", "VEHICLE_CREATE",
            "ORDER_CREATE", "ORDER_UPDATE", "LOAD_CREATE", "LOAD_ALLOCATE", "TRIP_EXECUTE");

    private final LocalDate lastMonth =
            LocalDate.now(ZoneOffset.UTC).minusMonths(1).withDayOfMonth(1);

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

    private String salt;
    private String orgPath;
    private UUID tenantId;
    private UUID orgUnitId;
    private AppUser maker;
    private AppUser checker;
    private UUID customer;
    private UUID consignee;
    private UUID origin;
    private UUID destination;

    @BeforeEach
    void aCustomerWithAContract() {
        salt = UUID.randomUUID().toString().substring(0, 8);
        orgPath = "/acme-" + salt + "/";
        tenantId = fixtures.createTenant("acme-" + salt, "Acme Logistics");
        orgUnitId = fixtures.createOrgUnit(tenantId, orgPath, "Acme HQ", "HQ", null);
        maker = user("desk@acme.test");
        checker = user("credit@acme.test");
        asSetup(() -> {
            customer = partner("CUST-" + salt, BusinessPartner.PartnerType.CUSTOMER);
            consignee = partner("CONS-" + salt, BusinessPartner.PartnerType.CONSIGNEE);
            origin = depot("ORG-" + salt, 19.10, 72.90);
            destination = depot("DST-" + salt, 28.40, 77.00);
            return null;
        });
        // 5 per chargeable kg, no minimum, no tax: an order of 100 kg is worth 500.
        // The tenant asks for no verification here: these scenarios are about credit.
        api(ApprovalsApi.class, maker, List.of("APPROVAL_POLICY_MANAGE")).setApprovalPolicy("CONTRACT_ACTIVATION",
                new ApprovalPolicyRequest().required(false), null);
        ClientBillingApi billing = api(ClientBillingApi.class, maker, DESK);
        UUID contract = billing.createClientContractDraft(
                new ClientContractDraftRequest().orgUnitId(orgUnitId).code("C-" + salt)
                        .clientPartnerId(customer).effectiveFrom(lastMonth.minusMonths(2))
                        .transportRatePerKg(new BigDecimal("5.00"))
                        .transportMinimumCharge(BigDecimal.ZERO)
                        .storageRatePerPalletDay(new BigDecimal("12"))
                        .handlingInRatePerPallet(new BigDecimal("25"))
                        .handlingOutRatePerPallet(new BigDecimal("30")), null).getId();
        billing.requestClientContractVerification("\"" + billing.getClientContract(contract).getVersion() + "\"",
                contract, null);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a credit limit changes only when a second person approves it, and every request is logged")
    void limitChangesAreMakerChecker() {
        CreditApi desk = api(CreditApi.class, maker, DESK);
        CreditApi credit = api(CreditApi.class, checker, CHECKER);

        assertThat(desk.getCreditPosition(customer).getLimitState())
                .isEqualTo(CreditPosition.LimitStateEnum.NO_LIMIT);
        assertConflict(() -> desk.requestCreditLimitChange(customer, change("1000", "1200"), null),
                "credit-soft-limit-above-limit");

        UUID first = desk.requestCreditLimitChange(customer, change("1000", "800"), null).getId();
        assertConflict(() -> desk.requestCreditLimitChange(customer, change("2000", null), null),
                "credit-limit-change-pending");
        // Nothing moves while it waits.
        CreditPosition waiting = desk.getCreditPosition(customer);
        assertThat(waiting.getCreditLimit()).isNull();
        assertThat(waiting.getPendingChangeId()).isEqualTo(first);

        // The requester cannot be the approver, even holding the right.
        CreditApi deskWithApproval = api(CreditApi.class, maker,
                List.of("PARTNER_READ", "CREDIT_LIMIT_APPROVE"));
        assertConflict(() -> deskWithApproval.approveCreditLimitChange(first, decision("mine"), null),
                "credit-limit-change-self-approval");
        // An approver whose approval limit is below the requested limit is refused.
        CreditApi junior = api(CreditApi.class, checker, CHECKER, new BigDecimal("500"));
        assertStatus(() -> junior.approveCreditLimitChange(first, decision("too big"), null), 403);

        credit.approveCreditLimitChange(first, decision("agreed with finance"), null);
        CreditPosition approved = desk.getCreditPosition(customer);
        assertThat(approved.getCreditLimit()).isEqualByComparingTo("1000");
        assertThat(approved.getSoftLimit()).isEqualByComparingTo("800");
        assertThat(approved.getPendingChangeId()).isNull();
        assertThat(approved.getHeadroom()).isEqualByComparingTo("1000");
        assertConflict(() -> credit.approveCreditLimitChange(first, decision("again"), null),
                "credit-limit-change-not-pending");

        UUID second = desk.requestCreditLimitChange(customer, change("5000", null), null).getId();
        credit.rejectCreditLimitChange(second, decision("not without a guarantee"), null);
        assertThat(desk.getCreditPosition(customer).getCreditLimit()).isEqualByComparingTo("1000");

        List<CreditLimitChange> log = desk.listPartnerCreditLimitChanges(customer, null, null)
                .getItems();
        assertThat(log).extracting(CreditLimitChange::getStatus).containsExactly(
                CreditLimitChange.StatusEnum.REJECTED, CreditLimitChange.StatusEnum.APPROVED);
        assertThat(log.get(1).getPreviousLimit()).isNull();
        assertThat(log.get(1).getRequestedBy()).isEqualTo(maker.id().toString());
        assertThat(log.get(1).getDecidedBy()).isEqualTo(checker.id().toString());
        assertThat(desk.listCreditLimitChanges("PENDING", null, null).getItems()).isEmpty();
        assertThat(auditCount("CREDIT_LIMIT_CHANGED")).isEqualTo(1);

        assertStatus(() -> desk.getCreditPosition(UUID.randomUUID()), 404);
    }

    @Test
    @DisplayName("an order over the hard limit is refused naming the figures, and an override lets it through once")
    void ordersAreHeldToTheLimit() {
        setLimit("1000", "600");
        OrdersApi orders = api(OrdersApi.class, maker, DESK);
        CreditApi credit = api(CreditApi.class, maker, DESK);

        UUID within = draft(orders, "CR-1", "100");
        orders.validateOrder(within, null);
        assertThat(credit.getCreditPosition(customer).getLimitState())
                .isEqualTo(CreditPosition.LimitStateEnum.WITHIN);
        // A draft counts while it is checked: 500 + 500 reaches the limit exactly, which is allowed.
        UUID over = draft(orders, "CR-3", "100");

        // 500 + 150 = 650: past the soft limit, a warning and not a refusal.
        UUID warned = draft(orders, "CR-2", "30");
        orders.validateOrder(warned, null);
        CreditPosition soft = credit.getCreditPosition(customer);
        assertThat(soft.getLimitState()).isEqualTo(CreditPosition.LimitStateEnum.SOFT_EXCEEDED);
        assertThat(soft.getExposure().getOpenOrders()).isEqualByComparingTo("650");
        assertThat(soft.getExposure().getOpenOrderCount()).isEqualTo(2);
        assertThat(auditCount("CREDIT_SOFT_LIMIT_EXCEEDED")).isPositive();

        // 650 + 500 = 1150: refused, and the order stays a draft.
        assertThatThrownBy(() -> orders.validateOrder(over, null))
                .isInstanceOfSatisfying(FeignException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.contentUTF8()).contains("credit-limit-exceeded")
                            .contains("\"exposure\":\"1150.00\"")
                            .contains("\"creditLimit\":\"1000.00\"");
                });
        assertThat(orders.getOrder(over).getStatus()).isEqualTo("DRAFT");
        // A line added to the draft is refused the same way.
        assertConflict(() -> orders.addOrderLine(over, line(2, "1"), null), "credit-limit-exceeded");

        // An override below what the order needs does not cover it...
        credit.grantCreditOverride(customer, override(over, "1100"), null);
        assertConflict(() -> orders.validateOrder(over, null), "credit-limit-exceeded");
        // ...one that does is used once.
        credit.grantCreditOverride(customer, override(over, "1200"), null);
        orders.validateOrder(over, null);
        assertThat(orders.getOrder(over).getStatus()).isEqualTo("VALIDATED");
        assertThat(auditCount("CREDIT_LIMIT_OVERRIDDEN")).isEqualTo(1);
        CreditPosition hard = credit.getCreditPosition(customer);
        assertThat(hard.getLimitState()).isEqualTo(CreditPosition.LimitStateEnum.HARD_EXCEEDED);
        assertThat(hard.getHeadroom()).isEqualByComparingTo("-150");

        // Growing an order over the limit is refused; shrinking one is how
        // the exposure comes down, so it is allowed.
        assertConflict(() -> orders.amendOrder(versionOf(orders, warned), warned,
                new AmendOrderRequest().lines(List.of(line(1, "60"))), null), "credit-limit-exceeded");
        orders.amendOrder(versionOf(orders, warned), warned,
                new AmendOrderRequest().lines(List.of(line(1, "10"))), null);
        assertThat(credit.getCreditPosition(customer).getExposure().getTotal())
                .isEqualByComparingTo("1050");

        // Overriding needs the right.
        CreditApi withoutRight = api(CreditApi.class, maker, List.of("PARTNER_READ"));
        assertStatus(() -> withoutRight.grantCreditOverride(customer, override(over, "2000"), null),
                403);
    }

    @Test
    @DisplayName("a suspended customer's orders cannot be validated or given new lines")
    void suspendedCustomerIsNotTrading() {
        OrdersApi orders = api(OrdersApi.class, maker, DESK);
        UUID order = draft(orders, "ST-1", "10");
        asSetup(() -> {
            fleet.transitionPartner(customer, BusinessPartner.PartnerStatus.PENDING_VERIFICATION);
            fleet.transitionPartner(customer, BusinessPartner.PartnerStatus.ACTIVE);
            fleet.transitionPartner(customer, BusinessPartner.PartnerStatus.SUSPENDED);
            return null;
        });

        assertConflict(() -> orders.validateOrder(order, null), "customer-not-trading");
        assertConflict(() -> orders.addOrderLine(order, line(2, "5"), null), "customer-not-trading");
        assertThat(api(CreditApi.class, maker, DESK).getCreditPosition(customer).getTrading())
                .isFalse();
        assertThat(orders.getOrder(order).getStatus()).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("the billing run is held to the limit by unbilled charges, and an override for the month lets it run")
    void billingRunIsHeldToTheLimit() {
        ClientBillingApi billing = api(ClientBillingApi.class, maker, DESK);
        CreditApi credit = api(CreditApi.class, maker, DESK);
        // 300 pallet-days at 12: 3600 not yet invoiced.
        billing.recordClientServiceUsage(customer, new ServiceUsageRequest()
                .serviceType(ClientServiceType.STORAGE).quantity(new BigDecimal("300"))
                .occurredOn(lastMonth.withDayOfMonth(5)).reference("WMS snap")
                .sourceKey("snap-" + salt), null);
        setLimit("1000", null);
        assertThat(credit.getCreditPosition(customer).getExposure().getUnbilledCharges())
                .isEqualByComparingTo("3600");

        ClientBillingRunRequest run = new ClientBillingRunRequest().period(lastMonth);
        assertConflict(() -> billing.runClientBilling(customer, run, null), "credit-limit-exceeded");

        credit.grantCreditOverride(customer, new CreditOverrideRequest()
                .subjectType(CreditOverrideRequest.SubjectTypeEnum.CLIENT_INVOICE)
                .period(lastMonth.plusDays(3)).upTo(new BigDecimal("4000"))
                .reason("month-end agreed with the client"), null);
        billing.runClientBilling(customer, run, null);

        CreditPosition after = credit.getCreditPosition(customer);
        assertThat(after.getExposure().getUnbilledCharges()).isEqualByComparingTo("0");
        assertThat(after.getExposure().getOutstandingInvoices()).isEqualByComparingTo("3600");
        assertThat(after.getExposure().getTotal()).isEqualByComparingTo("3600");
    }

    @Test
    @DisplayName("an order whose trip has been charged is counted once, as the charge, not again as an open order")
    void chargedOrderIsCountedOnce() {
        CreditApi credit = api(CreditApi.class, maker, DESK);
        String loadNo = "LD-" + salt;
        UUID[] load = new UUID[1];
        asSetup(() -> {
            UUID vendor = partner("VEN-" + salt, BusinessPartner.PartnerType.VENDOR);
            fleet.transitionPartner(vendor, BusinessPartner.PartnerStatus.PENDING_VERIFICATION);
            fleet.transitionPartner(vendor, BusinessPartner.PartnerStatus.ACTIVE);
            UUID vehicle = fleet.registerVehicle(orgUnitId, null, "CR-" + salt, "TRUCK", "RIGID",
                    "2-AXLE", new BigDecimal("26000"), new BigDecimal("6000"),
                    new BigDecimal("60"), false, false);
            load[0] = spine.awardedLoad(orgUnitId, loadNo, customer, origin, consignee,
                    destination, vehicle, vendor, new BigDecimal("100"), false);
            return null;
        });

        // Planned and on a load, not yet moved: 100 kg x 5 is open order value.
        CreditPosition planned = credit.getCreditPosition(customer);
        assertThat(planned.getExposure().getOpenOrders()).isEqualByComparingTo("500");
        assertThat(planned.getExposure().getOpenOrderCount()).isEqualTo(1);
        assertThat(planned.getExposure().getUnbilledCharges()).isEqualByComparingTo("0");
        assertThat(planned.getExposure().getTotal()).isEqualByComparingTo("500");

        // The trip completes and its transport charge is posted -- what the
        // trip-completed listener does. The order is still open (not yet
        // fulfilled), but its weight is now the charge's, and counts there only.
        asSetup(() -> {
            UUID trip = trips.raiseTrip("TR-" + salt, load[0], Instant.now());
            return clientCharges.captureTransport(trip, "TR-" + salt, load[0], Instant.now(),
                    Instant.now());
        });
        CreditPosition charged = credit.getCreditPosition(customer);
        assertThat(charged.getExposure().getOpenOrders()).isEqualByComparingTo("0");
        assertThat(charged.getExposure().getOpenOrderCount()).isZero();
        assertThat(charged.getExposure().getUnbilledCharges()).isEqualByComparingTo("500");
        assertThat(charged.getExposure().getUnbilledChargeCount()).isEqualTo(1);
        assertThat(charged.getExposure().getTotal()).isEqualByComparingTo("500");
    }

    // ------------------------------------------------------------- helpers

    private void setLimit(String limit, String soft) {
        UUID change = api(CreditApi.class, maker, DESK)
                .requestCreditLimitChange(customer, change(limit, soft), null).getId();
        api(CreditApi.class, checker, CHECKER).approveCreditLimitChange(change, decision("ok"), null);
    }

    private static CreditLimitChangeRequest change(String limit, String soft) {
        return new CreditLimitChangeRequest().creditLimit(new BigDecimal(limit))
                .softLimit(soft == null ? null : new BigDecimal(soft)).reason("annual review");
    }

    private static CreditLimitDecisionRequest decision(String note) {
        return new CreditLimitDecisionRequest().note(note);
    }

    private static CreditOverrideRequest override(UUID orderId, String upTo) {
        return new CreditOverrideRequest().subjectType(CreditOverrideRequest.SubjectTypeEnum.ORDER)
                .orderId(orderId).upTo(new BigDecimal(upTo)).reason("strategic shipment");
    }

    private UUID draft(OrdersApi orders, String orderNo, String weightKg) {
        UUID id = orders.raiseOrder(new RaiseOrderRequest().orgUnitId(orgUnitId)
                .customerPartnerId(customer).orderNo(orderNo + "-" + salt)
                .originTerminalId(origin), null).getId();
        orders.addOrderLine(id, line(1, weightKg), null);
        return id;
    }

    private AddOrderLineRequest line(int lineNo, String weightKg) {
        return new AddOrderLineRequest().lineNo(lineNo).materialCode("MAT-" + lineNo)
                .materialDescription("General cargo").materialClass("GENERAL")
                .quantity(BigDecimal.ONE).uom("EA").deadWeightKg(new BigDecimal(weightKg))
                .consigneePartnerId(consignee).destinationTerminalId(destination);
    }

    private static String versionOf(OrdersApi orders, UUID id) {
        return String.valueOf(orders.getOrder(id).getVersion());
    }

    private long auditCount(String action) {
        return jdbc.sql("SELECT count(*) FROM audit_log WHERE tenant_id = :t AND action = :a")
                .param("t", tenantId).param("a", action).query(Long.class).single();
    }

    private static void assertConflict(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(FeignException.class, e -> {
            assertThat(e.status()).isEqualTo(409);
            assertThat(e.contentUTF8()).contains(code);
        });
    }

    private static void assertStatus(Runnable call, int status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(FeignException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    private <T extends ApiClient.Api> T api(Class<T> type, AppUser who, List<String> permissions) {
        return api(type, who, permissions, null);
    }

    private <T extends ApiClient.Api> T api(Class<T> type, AppUser who, List<String> permissions,
                                            BigDecimal approvalLimit) {
        String token = tokens.issueAccessToken(who, orgPath, permissions,
                new SubjectAttributes(null, null, null, approvalLimit)).value();
        ApiClient apiClient = new ApiClient().setBasePath("http://localhost:" + port);
        apiClient.getFeignBuilder().requestInterceptor(template ->
                template.header("Authorization", "Bearer " + token));
        return apiClient.buildClient(type);
    }

    private AppUser user(String email) {
        UUID id = fixtures.createUser(tenantId, orgUnitId, orgPath, email, "unused");
        return new AppUser(id, tenantId, orgUnitId, email, "unused", email,
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

    private <T> T asSetup(Supplier<T> action) {
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
