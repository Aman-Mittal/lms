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
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.lms.acceptance.TestFixtures;
import com.lms.client.ApiClient;
import com.lms.client.api.ApprovalsApi;
import com.lms.client.api.ClientBillingApi;
import com.lms.client.model.ApprovalPolicyRequest;
import com.lms.client.model.ClientContract;
import com.lms.client.model.ClientContractDraftRequest;
import com.lms.client.model.ClientContractLaneRequest;
import com.lms.client.model.LaneRateBasis;
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
import com.lms.web.ApiTestKit.Outcome;
import com.lms.web.ApiTestKit.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the enquiry and bulk intake API tests share: a tenant of its own per
 * scenario (a unit, a customer, a consignee, three depots), a caller with
 * chosen permissions, and plain HTTP with an {@code If-Match} where one is
 * wanted.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class OrderIntakeApiBase {

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

    static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    static final String[] DESK = {"ORDER_CREATE", "ORDER_UPDATE", "ORDER_READ", "PARTNER_READ"};

    private static final List<String> SETUP = List.of("PARTNER_CREATE", "PARTNER_UPDATE", "TERMINAL_CREATE",
            "ORDER_CREATE", "ORDER_UPDATE");

    private static final List<String> CONTRACT_DESK = List.of("ORDER_CREATE", "ORDER_READ", "ORDER_UPDATE",
            "PARTNER_READ", "CLIENT_CONTRACT_MANAGE", "CLIENT_BILLING_READ", "APPROVAL_POLICY_MANAGE");

    @LocalServerPort
    int port;
    @Autowired
    TestFixtures fixtures;
    @Autowired
    FleetCommandService fleet;
    @Autowired
    TerminalCommandService terminals;
    @Autowired
    TokenService tokens;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactionManager;

    ApiTestKit kit;
    String salt;
    String orgPath;
    UUID tenantId;
    UUID orgUnitId;
    Tenant caller;
    UUID client;
    UUID consignee;
    UUID origin;
    UUID destination;
    UUID elsewhere;
    UUID otherOrigin;

    @BeforeEach
    void aTenantWithADesk() {
        kit = new ApiTestKit(fixtures, tokens, port);
        salt = UUID.randomUUID().toString().substring(0, 8);
        orgPath = "/acme-" + salt + "/";
        tenantId = fixtures.createTenant("acme-" + salt, "Acme Logistics");
        orgUnitId = fixtures.createOrgUnit(tenantId, orgPath, "Acme HQ", "HQ", null);
        caller = kit.tenantOf(tenantId, orgUnitId, orgPath);
        asSetup(tenantId, orgPath, () -> {
            client = fleet.registerPartner(orgUnitId, "CL-" + salt, "CL-" + salt,
                    BusinessPartner.PartnerType.CUSTOMER, "TAX-CL-" + salt);
            consignee = fleet.registerPartner(orgUnitId, "CONS-" + salt, "CONS-" + salt,
                    BusinessPartner.PartnerType.CONSIGNEE, "TAX-CONS-" + salt);
            origin = depot("ORG-" + salt, 19.10, 72.90);
            destination = depot("DST-" + salt, 28.40, 77.00);
            elsewhere = depot("ELS-" + salt, 12.97, 77.59);
            otherOrigin = depot("OTH-" + salt, 22.57, 88.36);
            return null;
        });
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- data

    UUID depot(String code, double lat, double lon) {
        return terminals.register(new RegisterTerminalCommand.PointRadius(orgUnitId, code, code,
                Terminal.FunctionalCategory.CUSTOMER_SITE, new LatLon(lat, lon), 500, 2, null, null, 45, List.of()));
    }

    <T> T asSetup(UUID forTenant, String path, Supplier<T> action) {
        Set<SimpleGrantedAuthority> authorities = SETUP.stream().map(SimpleGrantedAuthority::new)
                .collect(Collectors.toSet());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("setup", "n/a", authorities));
        try {
            return TenantContext.callWith(forTenant, path, action);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** An active contract of the customer with one PER_KG lane from the origin to the destination. */
    void activeContractWithLane(String code, String ratePerKg) {
        AppUser deskUser = new AppUser(fixtures.createUser(tenantId, orgUnitId, orgPath,
                "desk-" + salt + "@acme.test", "unused"), tenantId, orgUnitId, "desk-" + salt + "@acme.test",
                "unused", "Desk", AppUser.UserStatus.ACTIVE, null, 0, null, null, null, Instant.now(), Instant.now());
        String token = tokens.issueAccessToken(deskUser, orgPath, CONTRACT_DESK,
                new SubjectAttributes(null, null, null, null)).value();
        ApprovalsApi approvals = client(ApprovalsApi.class, token);
        approvals.setApprovalPolicy("CONTRACT_ACTIVATION", new ApprovalPolicyRequest().required(false)
                .decidePermissions(List.of("CLIENT_CONTRACT_APPROVE")), null);
        ClientBillingApi billing = client(ClientBillingApi.class, token);
        UUID id = billing.createClientContractDraft(new ClientContractDraftRequest().orgUnitId(orgUnitId).code(code)
                .clientPartnerId(client).effectiveFrom(TODAY.minusDays(30))
                .transportRatePerKg(new BigDecimal("5.00")).transportMinimumCharge(BigDecimal.ZERO)
                .storageRatePerPalletDay(new BigDecimal("12")).handlingInRatePerPallet(new BigDecimal("25"))
                .handlingOutRatePerPallet(new BigDecimal("30")).directOrdersAllowed(true)
                .lanes(List.of(new ClientContractLaneRequest().laneCode("MUM-DEL").originTerminalId(origin)
                        .destinationTerminalId(destination).rateBasis(LaneRateBasis.PER_KG)
                        .rate(new BigDecimal(ratePerKg)))), null).getId();
        ClientContract draft = billing.getClientContract(id);
        billing.requestClientContractVerification("\"" + draft.getVersion() + "\"", id, null);
        assertThat(billing.getClientContract(id).getStatus()).isEqualTo(ClientContract.StatusEnum.ACTIVE);
    }

    private <T extends ApiClient.Api> T client(Class<T> type, String token) {
        ApiClient apiClient = new ApiClient().setBasePath("http://localhost:" + port);
        apiClient.getFeignBuilder().requestInterceptor(template -> template.header("Authorization", "Bearer " + token));
        return apiClient.buildClient(type);
    }

    // ---------------------------------------------------------------- HTTP

    Outcome call(Tenant who, String method, String path, String body, String ifMatch, String... permissions) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + kit.token(who, permissions));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return kit.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build());
    }

    Outcome call(String method, String path, String body, String ifMatch) {
        return call(caller, method, path, body, ifMatch, DESK);
    }

    JsonNode ok(Outcome outcome, int status) {
        assertThat(outcome.status()).as(outcome.body()).isEqualTo(status);
        return outcome.body() == null || outcome.body().isBlank() ? null : kit.parse(outcome);
    }

    static void refused(Outcome outcome, int status, String code) {
        assertThat(outcome.status()).as(outcome.body()).isEqualTo(status);
        if (code != null) {
            assertThat(outcome.code()).as(outcome.body()).isEqualTo(code);
        }
    }

    long audited(String action) {
        return jdbc.sql("SELECT count(*) FROM audit_log WHERE action = :a AND tenant_id = :t")
                .param("a", action).param("t", tenantId).query(Long.class).single();
    }

    static boolean absent(JsonNode node, String field) {
        return !node.has(field) || node.get(field).isNull();
    }
}
