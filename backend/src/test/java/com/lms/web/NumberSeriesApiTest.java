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
import java.time.Duration;
import java.time.Instant;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.lms.acceptance.TestFixtures;
import com.lms.client.ApiClient;
import com.lms.client.api.NumberSeriesApi;
import com.lms.client.api.OrdersApi;
import com.lms.client.api.PlanningApi;
import com.lms.client.model.AddOrderLineRequest;
import com.lms.client.model.CreateNumberSeriesRequest;
import com.lms.client.model.NextNumberPreview;
import com.lms.client.model.NumberFormatPreview;
import com.lms.client.model.NumberRelease;
import com.lms.client.model.NumberSeries;
import com.lms.client.model.NumberVerification;
import com.lms.client.model.NumberYearCode;
import com.lms.client.model.NumberedDocumentType;
import com.lms.client.model.RaiseOrderRequest;
import com.lms.client.model.ReleaseNumberRequest;
import com.lms.client.model.ReviseNumberSeriesRequest;
import com.lms.identity.command.NumberIssuer;
import com.lms.identity.numbering.DocumentType;
import com.lms.identity.security.TokenService;
import com.lms.masterdata.command.FleetCommandService;
import com.lms.masterdata.command.RegisterTerminalCommand;
import com.lms.masterdata.command.TerminalCommandService;
import com.lms.masterdata.command.domain.BusinessPartner;
import com.lms.masterdata.command.domain.Terminal;
import com.lms.shared.geo.LatLon;
import com.lms.shared.tenant.TenantContext;
import com.lms.web.ApiTestKit.Tenant;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Document number series over HTTP, through the client generated from the
 * contract: administering series, the most specific one winning, atomic
 * issue under concurrency, the released-number pool, verification, the
 * financial-year rollover, and orders and lorry receipts drawing from a
 * series when -- and only when -- the tenant configured one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NumberSeriesApiTest {

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

    private static final String MANAGE = "NUMBER_SERIES_MANAGE";
    private static final String[] DESK = {"ORDER_CREATE", "ORDER_READ", "ORDER_UPDATE", "LOAD_CREATE",
            "LOAD_READ"};
    private static final List<String> SETUP = List.of("PARTNER_CREATE", "PARTNER_UPDATE", "TERMINAL_CREATE");

    @LocalServerPort
    private int port;

    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private TokenService tokens;
    @Autowired
    private FleetCommandService fleet;
    @Autowired
    private TerminalCommandService terminals;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private NumberIssuer issuer;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private ApiTestKit kit;
    private Tenant acme;
    private UUID north;
    private UUID northBranch;
    private NumberSeriesApi admin;

    @BeforeEach
    void aTenantWithRegionAndBranch() {
        kit = new ApiTestKit(fixtures, tokens, port);
        acme = kit.newTenant("acme");
        north = fixtures.createOrgUnit(acme.tenantId(), acme.orgPath() + "north/", "North", "REGION",
                acme.orgUnitId());
        northBranch = fixtures.createOrgUnit(acme.tenantId(), acme.orgPath() + "north/delhi/", "Delhi",
                "BRANCH", north);
        admin = client(acme, NumberSeriesApi.class, MANAGE);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a series is created, previewed, revised while unused, and its format is fixed once it issues")
    void administeringASeries() {
        NumberFormatPreview suggestion = admin.previewNumberFormat("GP", NumberYearCode.SPAN, "/", 4, 4,
                "Asia/Kolkata");
        int year = suggestion.getFinancialYear();
        assertThat(suggestion.getSample()).isEqualTo("GP/%02d%02d/0001".formatted(year % 100, (year + 1) % 100));
        assertThat(suggestion.getNextYearSample())
                .isEqualTo("GP/%02d%02d/0001".formatted((year + 1) % 100, (year + 2) % 100));
        assertStatus(() -> admin.previewNumberFormat("gp", null, null, null, null, null), 400);

        UUID id = admin.createNumberSeries(series("gate", NumberedDocumentType.GATE_PASS, null, "GP")
                .yearCode(NumberYearCode.SPAN).separator("/").counterLength(4).timeZone("Asia/Kolkata"),
                null).getId();
        NumberSeries created = admin.getNumberSeries(id);
        assertThat(created.getActive()).isTrue();
        assertThat(created.getReuseReleased()).isFalse();
        assertThat(created.getYearStartMonth()).isEqualTo(4);
        assertThat(created.getIssuedThisYear()).isZero();
        assertThat(created.getLastIssued()).isNull();
        assertThat(admin.previewNextNumber(id).getNumber()).isEqualTo(created.getSample());

        // Unused: the format may change.
        admin.reviseNumberSeries(id, new ReviseNumberSeriesRequest().prefix("GPS")
                .yearCode(NumberYearCode.START_YEAR).separator("-").counterLength(5).reuseReleased(false));
        String first = admin.issueNumber(id, null).getNumber();
        assertThat(first).isEqualTo("GPS-%d-00001".formatted(admin.getNumberSeries(id).getFinancialYear()));
        assertThat(admin.getNumberSeries(id).getLastIssued()).isEqualTo(first);

        // Used: the format is fixed and the series cannot be deleted, only deactivated.
        assertConflict(() -> admin.reviseNumberSeries(id, new ReviseNumberSeriesRequest().prefix("GPX")
                .yearCode(NumberYearCode.START_YEAR).reuseReleased(false)), "number-series-in-use");
        admin.reviseNumberSeries(id, new ReviseNumberSeriesRequest().prefix("GPS")
                .yearCode(NumberYearCode.START_YEAR).separator("-").counterLength(5).reuseReleased(true)
                .description("Reuse from now on"));
        assertThat(admin.getNumberSeries(id).getReuseReleased()).isTrue();
        assertConflict(() -> admin.deleteNumberSeries(id), "number-series-in-use");
        admin.deactivateNumberSeries(id, null);
        assertConflict(() -> admin.issueNumber(id, null), "number-series-inactive");

        // An unused series can be deleted.
        UUID spare = admin.createNumberSeries(series("spare", NumberedDocumentType.GATE_PASS, null, "SP"), null)
                .getId();
        admin.deleteNumberSeries(spare);
        assertStatus(() -> admin.getNumberSeries(spare), 404);

        assertThat(auditCount("NUMBER_SERIES_CREATED")).isEqualTo(2);
        assertThat(auditCount("NUMBER_ISSUED")).isEqualTo(1);
        assertThat(auditCount("NUMBER_SERIES_DEACTIVATED")).isEqualTo(1);
    }

    @Test
    @DisplayName("codes, prefixes and active scopes are unique; a foreign unit is not found; the right is required")
    void refusals() {
        admin.createNumberSeries(series("lr-all", NumberedDocumentType.CONSIGNMENT_NOTE, null, "LR"), null);
        assertConflict(() -> admin.createNumberSeries(
                series("lr-all", NumberedDocumentType.DOCKET, null, "DK"), null), "number-series-code-taken");
        assertConflict(() -> admin.createNumberSeries(
                series("lr-north", NumberedDocumentType.CONSIGNMENT_NOTE, north, "LR"), null),
                "number-series-prefix-taken");
        assertConflict(() -> admin.createNumberSeries(
                series("lr-again", NumberedDocumentType.CONSIGNMENT_NOTE, null, "LRA"), null),
                "number-series-scope-taken");
        // Inactive, it may wait alongside; activating it is refused while the other is active.
        UUID waiting = admin.createNumberSeries(series("lr-next", NumberedDocumentType.CONSIGNMENT_NOTE, null,
                "LRN").active(false), null).getId();
        assertConflict(() -> admin.activateNumberSeries(waiting, null), "number-series-scope-taken");

        Tenant globex = kit.newTenant("globex");
        assertStatus(() -> client(globex, NumberSeriesApi.class, MANAGE).getNumberSeries(waiting), 404);
        assertStatus(() -> client(globex, NumberSeriesApi.class, MANAGE).createNumberSeries(
                series("theirs", NumberedDocumentType.ORDER, north, "X"), null), 404);

        assertStatus(() -> client(acme, NumberSeriesApi.class, "ORDER_READ").listNumberSeries(null, null, null),
                403);
        assertStatus(() -> admin.createNumberSeries(series("bad", NumberedDocumentType.ORDER, null, "ORD")
                .separator("#"), null), 400);
        assertStatus(() -> admin.createNumberSeries(series("bad", NumberedDocumentType.ORDER, null, "ORD")
                .timeZone("Mars/Olympus"), null), 400);
    }

    @Test
    @DisplayName("the series of the deepest unit at or above the document's wins, else the tenant's")
    void mostSpecificWins() {
        UUID tenantWide = admin.createNumberSeries(series("lr", NumberedDocumentType.CONSIGNMENT_NOTE, null, "LR"),
                null).getId();
        UUID region = admin.createNumberSeries(series("lr-n", NumberedDocumentType.CONSIGNMENT_NOTE, north,
                "LRN"), null).getId();

        assertThat(admin.getEffectiveNumberSeries(NumberedDocumentType.CONSIGNMENT_NOTE, null).getId())
                .isEqualTo(tenantWide);
        assertThat(admin.getEffectiveNumberSeries(NumberedDocumentType.CONSIGNMENT_NOTE, acme.orgUnitId())
                .getId()).isEqualTo(tenantWide);
        assertThat(admin.getEffectiveNumberSeries(NumberedDocumentType.CONSIGNMENT_NOTE, northBranch).getId())
                .isEqualTo(region);

        UUID branch = admin.createNumberSeries(series("lr-d", NumberedDocumentType.CONSIGNMENT_NOTE,
                northBranch, "LRD"), null).getId();
        assertThat(admin.getEffectiveNumberSeries(NumberedDocumentType.CONSIGNMENT_NOTE, northBranch).getId())
                .isEqualTo(branch);
        assertThat(admin.getEffectiveNumberSeries(NumberedDocumentType.CONSIGNMENT_NOTE, north).getId())
                .isEqualTo(region);
        NumberSeries shown = admin.getNumberSeries(branch);
        assertThat(shown.getOrgUnitName()).isEqualTo("Delhi");
        assertThat(shown.getOrgUnitPath()).isEqualTo(acme.orgPath() + "north/delhi/");

        admin.deactivateNumberSeries(branch, null);
        admin.deactivateNumberSeries(region, null);
        assertThat(admin.getEffectiveNumberSeries(NumberedDocumentType.CONSIGNMENT_NOTE, northBranch).getId())
                .isEqualTo(tenantWide);
        assertStatus(() -> admin.getEffectiveNumberSeries(NumberedDocumentType.DOCKET, northBranch), 404);

        assertThat(admin.listNumberSeriesScopes(null, null).getItems())
                .extracting(s -> s.getPath())
                .containsExactly(acme.orgPath(), acme.orgPath() + "north/", acme.orgPath() + "north/delhi/");
        assertThat(admin.listNumberSeries(NumberedDocumentType.CONSIGNMENT_NOTE, null, 2).getItems())
                .extracting(NumberSeries::getCode).containsExactly("lr", "lr-d");
    }

    @Test
    @DisplayName("parallel issues from one series get distinct, consecutive numbers")
    void concurrentIssue() {
        UUID id = admin.createNumberSeries(series("inv", NumberedDocumentType.INVOICE, null, "INV")
                .yearCode(NumberYearCode.NONE).counterLength(4), null).getId();
        int parallel = 12;
        List<Callable<String>> calls = new ArrayList<>();
        for (int i = 0; i < parallel; i++) {
            calls.add(() -> client(acme, NumberSeriesApi.class, MANAGE).issueNumber(id, null).getNumber());
        }
        List<String> numbers = ApiTestKit.race(calls, Duration.ofSeconds(60));

        List<String> expected = new ArrayList<>();
        for (int i = 1; i <= parallel; i++) {
            expected.add("INV-%04d".formatted(i));
        }
        assertThat(numbers).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected);
        assertThat(admin.getNumberSeries(id).getIssuedThisYear()).isEqualTo(parallel);
    }

    @Test
    @DisplayName("a released number is logged; reused first only by a series that reuses; and can be verified")
    void releasedNumbers() {
        UUID keep = admin.createNumberSeries(series("ld", NumberedDocumentType.LOAD, null, "LD")
                .yearCode(NumberYearCode.NONE).counterLength(3), null).getId();
        UUID reuse = admin.createNumberSeries(series("tr", NumberedDocumentType.TRIP, null, "TR")
                .yearCode(NumberYearCode.NONE).counterLength(3).reuseReleased(true), null).getId();
        for (int i = 0; i < 3; i++) {
            admin.issueNumber(keep, null);
            admin.issueNumber(reuse, null);
        }

        admin.releaseNumber(keep, release("LD-002", "pass cancelled"), null);
        admin.releaseNumber(reuse, release("TR-002", "trip cancelled"), null);
        admin.releaseNumber(reuse, release("TR-001", "trip cancelled"), null);
        assertConflict(() -> admin.releaseNumber(keep, release("LD-002", "again"), null),
                "number-already-released");
        assertConflict(() -> admin.releaseNumber(keep, release("LD-009", "never issued"), null),
                "number-not-issued");
        assertConflict(() -> admin.releaseNumber(keep, release("TR-001", "wrong series"), null),
                "number-not-in-series");
        assertStatus(() -> admin.releaseNumber(keep, new ReleaseNumberRequest().number("LD-001"), null), 400);

        // Never reuse: the counter carries on.
        assertThat(admin.previewNextNumber(keep).getFromPool()).isFalse();
        assertThat(admin.issueNumber(keep, null).getNumber()).isEqualTo("LD-004");
        // Reuse: the lowest released number first, then the counter.
        NextNumberPreview fromPool = admin.previewNextNumber(reuse);
        assertThat(fromPool.getNumber()).isEqualTo("TR-001");
        assertThat(fromPool.getFromPool()).isTrue();
        assertThat(admin.getNumberSeries(reuse).getReleasedAvailable()).isEqualTo(2);
        assertThat(admin.issueNumber(reuse, null).getNumber()).isEqualTo("TR-001");
        assertThat(admin.issueNumber(reuse, null).getNumber()).isEqualTo("TR-002");
        assertThat(admin.issueNumber(reuse, null).getNumber()).isEqualTo("TR-004");
        // A reused number can be released again.
        admin.releaseNumber(reuse, release("TR-001", "cancelled a second time"), null);

        List<NumberRelease> log = admin.listNumberReleases(reuse, null, null).getItems();
        assertThat(log).extracting(NumberRelease::getNumber).containsExactly("TR-001", "TR-002");
        assertThat(log.get(0).getReusedAt()).isNull();
        assertThat(log.get(1).getReusedAt()).isNotNull();
        assertThat(admin.listNumberReleases(keep, null, null).getItems())
                .extracting(NumberRelease::getReason).containsExactly("pass cancelled");
        assertThat(auditCount("NUMBER_RELEASED")).isEqualTo(4);

        NumberVerification issued = admin.verifyDocumentNumber(NumberedDocumentType.LOAD, "LD-003");
        assertThat(issued.getBelongs()).isTrue();
        assertThat(issued.getSeriesId()).isEqualTo(keep);
        assertThat(issued.getCounter()).isEqualTo(3L);
        assertThat(issued.getStatus()).isEqualTo(NumberVerification.StatusEnum.ISSUED);
        assertThat(admin.verifyDocumentNumber(NumberedDocumentType.LOAD, "LD-002").getStatus())
                .isEqualTo(NumberVerification.StatusEnum.RELEASED);
        assertThat(admin.verifyDocumentNumber(NumberedDocumentType.LOAD, "LD-005").getStatus())
                .isEqualTo(NumberVerification.StatusEnum.NOT_ISSUED);
        assertThat(admin.verifyDocumentNumber(NumberedDocumentType.TRIP, "TR-002").getStatus())
                .isEqualTo(NumberVerification.StatusEnum.ISSUED);
        NumberVerification stranger = admin.verifyDocumentNumber(NumberedDocumentType.LOAD, "LD-03");
        assertThat(stranger.getBelongs()).isFalse();
        assertThat(stranger.getStatus()).isNull();
        // Another tenant's series does not vouch for the number.
        Tenant globex = kit.newTenant("globex");
        assertThat(client(globex, NumberSeriesApi.class, MANAGE)
                .verifyDocumentNumber(NumberedDocumentType.LOAD, "LD-003").getBelongs()).isFalse();
    }

    @Test
    @DisplayName("the counter restarts when the financial year rolls over, and each year's pool stays its own")
    void yearRollover() {
        UUID id = admin.createNumberSeries(series("lr", NumberedDocumentType.CONSIGNMENT_NOTE, null, "LR")
                .yearCode(NumberYearCode.START_YEAR).reuseReleased(true), null).getId();
        Instant lastDay = Instant.parse("2027-03-31T12:00:00Z");
        Instant firstDay = Instant.parse("2027-04-01T00:00:00Z");

        assertThat(draw(lastDay)).contains("LR-2026-00001");
        assertThat(draw(lastDay)).contains("LR-2026-00002");
        assertThat(draw(firstDay)).contains("LR-2027-00001");
        assertThat(draw(lastDay)).contains("LR-2026-00003");

        inTenant(() -> issuer.release(DocumentType.CONSIGNMENT_NOTE, "LR-2026-00002", "cancelled"));
        // The 2026 release is not handed out in 2027.
        assertThat(draw(firstDay)).contains("LR-2027-00002");
        assertThat(draw(lastDay)).contains("LR-2026-00002");
        assertThat(admin.verifyDocumentNumber(NumberedDocumentType.CONSIGNMENT_NOTE, "LR-2027-00002")
                .getFinancialYear()).isEqualTo(2027);
        assertThat(id).isNotNull();
    }

    @Test
    @DisplayName("without a series an order keeps ORD-<year>-<6 digits>; with one it takes the series' number")
    void ordersDrawFromTheSeries() {
        Network network = network();
        OrdersApi orders = client(acme, OrdersApi.class, DESK);
        int year = Year.now(ZoneOffset.UTC).getValue();

        UUID plain = orders.raiseOrder(raise(network, acme.orgUnitId()), null).getId();
        assertThat(orders.getOrder(plain).getOrderNo()).isEqualTo("ORD-%d-000001".formatted(year));

        admin.createNumberSeries(series("so", NumberedDocumentType.ORDER, null, "SO")
                .yearCode(NumberYearCode.NONE).counterLength(4), null);
        admin.createNumberSeries(series("so-delhi", NumberedDocumentType.ORDER, northBranch, "DEL")
                .yearCode(NumberYearCode.NONE).counterLength(4), null);
        UUID head = orders.raiseOrder(raise(network, acme.orgUnitId()), null).getId();
        UUID delhi = orders.raiseOrder(raise(network, northBranch), null).getId();
        assertThat(orders.getOrder(head).getOrderNo()).isEqualTo("SO-0001");
        assertThat(orders.getOrder(delhi).getOrderNo()).isEqualTo("DEL-0001");

        // A number the series would give next, already used by hand, is skipped.
        orders.raiseOrder(raise(network, acme.orgUnitId()).orderNo("SO-0002"), null);
        UUID next = orders.raiseOrder(raise(network, acme.orgUnitId()), null).getId();
        assertThat(orders.getOrder(next).getOrderNo()).isEqualTo("SO-0003");
    }

    @Test
    @DisplayName("lorry receipts stay random until the tenant opts into a consignment-note series")
    void consignmentNotesOptIn() {
        Network network = network();
        OrdersApi orders = client(acme, OrdersApi.class, DESK);
        PlanningApi planning = client(acme, PlanningApi.class, DESK);

        String random = consignmentRef(orders, planning, network);
        assertThat(random).startsWith("LR-").hasSize(13);
        assertThat(admin.verifyDocumentNumber(NumberedDocumentType.CONSIGNMENT_NOTE, random).getBelongs())
                .isFalse();

        admin.createNumberSeries(series("cn", NumberedDocumentType.CONSIGNMENT_NOTE, null, "CN")
                .yearCode(NumberYearCode.NONE).counterLength(6), null);
        String numbered = consignmentRef(orders, planning, network);
        assertThat(numbered).isEqualTo("CN-000001");
        assertThat(admin.verifyDocumentNumber(NumberedDocumentType.CONSIGNMENT_NOTE, numbered).getStatus())
                .isEqualTo(NumberVerification.StatusEnum.ISSUED);
    }

    // ------------------------------------------------------------- helpers

    /** A tenant network to raise orders in. */
    private record Network(UUID customer, UUID consignee, UUID origin, UUID destination) {
    }

    private Network network() {
        return asSetup(() -> new Network(partner("CUST"), partner("CONS"),
                depot("ORG", 19.10, 72.90), depot("DST", 28.40, 77.00)));
    }

    private String consignmentRef(OrdersApi orders, PlanningApi planning, Network network) {
        UUID order = orders.raiseOrder(raise(network, acme.orgUnitId()), null).getId();
        orders.addOrderLine(order, new AddOrderLineRequest().lineNo(1).materialCode("MAT-1")
                .materialDescription("General cargo").materialClass("GENERAL").quantity(BigDecimal.ONE)
                .uom("EA").deadWeightKg(new BigDecimal("1000")).consigneePartnerId(network.consignee())
                .destinationTerminalId(network.destination()), null);
        orders.validateOrder(order, null);
        return planning.getConsignment(planning.generateConsignments(order, null).getFirst().getId())
                .getTrackingRef();
    }

    private static RaiseOrderRequest raise(Network network, UUID orgUnitId) {
        return new RaiseOrderRequest().orgUnitId(orgUnitId).customerPartnerId(network.customer())
                .originTerminalId(network.origin());
    }

    private static CreateNumberSeriesRequest series(String code, NumberedDocumentType type, UUID unit,
                                                    String prefix) {
        return new CreateNumberSeriesRequest().code(code).documentType(type).orgUnitId(unit).prefix(prefix)
                .yearCode(NumberYearCode.START_YEAR);
    }

    private static ReleaseNumberRequest release(String number, String reason) {
        return new ReleaseNumberRequest().number(number).reason(reason);
    }

    private Optional<String> draw(Instant at) {
        return inTenant(() -> issuer.next(DocumentType.CONSIGNMENT_NOTE, null, at));
    }

    private <T> T inTenant(Supplier<T> action) {
        return TenantContext.callWith(acme.tenantId(), acme.orgPath(),
                () -> new TransactionTemplate(transactionManager).execute(status -> action.get()));
    }

    private UUID partner(String code) {
        return fleet.registerPartner(acme.orgUnitId(), code, code, BusinessPartner.PartnerType.CUSTOMER,
                "TAX-" + code + "-" + acme.tenantId().toString().substring(0, 8));
    }

    private UUID depot(String code, double lat, double lon) {
        return terminals.register(new RegisterTerminalCommand.PointRadius(acme.orgUnitId(), code, code,
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
            return TenantContext.callWith(acme.tenantId(), acme.orgPath(), action);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private <T extends ApiClient.Api> T client(Tenant tenant, Class<T> api, String... permissions) {
        String token = kit.token(tenant, permissions);
        ApiClient apiClient = new ApiClient().setBasePath("http://localhost:" + port);
        apiClient.getFeignBuilder().requestInterceptor(template ->
                template.header("Authorization", "Bearer " + token));
        return apiClient.buildClient(api);
    }

    private long auditCount(String action) {
        return jdbc.sql("SELECT count(*) FROM audit_log WHERE tenant_id = :t AND action = :a")
                .param("t", acme.tenantId()).param("a", action).query(Long.class).single();
    }

    private static void assertConflict(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(FeignException.class, e -> {
            assertThat(e.status()).isEqualTo(409);
            assertThat(e.contentUTF8()).contains("\"" + code + "\"");
        });
    }

    private static void assertStatus(Runnable call, int status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(FeignException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }
}
