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

import com.lms.acceptance.TestFixtures;
import com.lms.client.ApiClient;
import com.lms.client.api.ClientBillingApi;
import com.lms.client.model.ClientBillingRunRequest;
import com.lms.client.model.ClientChargeSlice;
import com.lms.client.model.ClientContract;
import com.lms.client.model.ClientContractServiceRate;
import com.lms.client.model.ClientInvoice;
import com.lms.client.model.ClientInvoiceLine;
import com.lms.client.model.ClientMargin;
import com.lms.client.model.ClientPortalUserRequest;
import com.lms.client.model.ClientServiceType;
import com.lms.client.model.PublishClientContractRequest;
import com.lms.client.model.ServiceUsageRequest;
import com.lms.identity.command.domain.AppUser;
import com.lms.identity.security.TokenService;
import com.lms.masterdata.command.FleetCommandService;
import com.lms.masterdata.command.domain.BusinessPartner;
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
 * 3PL client billing over HTTP, through a client generated from the contract.
 *
 * <p>Clients are registered in-process -- partner registration is not what is
 * under test -- and everything after that crosses the wire: publishing the
 * contract, recording warehouse usage, running and re-running the month,
 * reading the invoice, and the portal user's confinement.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClientBillingApiTest {

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

    /** What the 3PL's own billing desk holds. */
    private static final List<String> DESK = List.of(
            "CLIENT_CONTRACT_MANAGE", "CLIENT_BILLING_RUN", "CLIENT_BILLING_READ",
            "CLIENT_MARGIN_READ");

    /** What a client portal role holds; the binding does the rest. */
    private static final List<String> PORTAL = List.of("CLIENT_BILLING_READ", "CLIENT_MARGIN_READ");

    private static final LocalDate LAST_MONTH =
            LocalDate.now(ZoneOffset.UTC).minusMonths(1).withDayOfMonth(1);

    @LocalServerPort
    private int port;

    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private FleetCommandService fleet;
    @Autowired
    private TokenService tokens;

    private String orgPath;
    private UUID orgUnitId;
    private AppUser desk;
    private AppUser portalUser;
    private UUID clientA;
    private UUID clientB;

    @BeforeEach
    void twoClients() {
        String salt = UUID.randomUUID().toString().substring(0, 8);
        orgPath = "/acme-" + salt + "/";
        UUID tenantId = fixtures.createTenant("acme-" + salt, "Acme Logistics");
        orgUnitId = fixtures.createOrgUnit(tenantId, orgPath, "Acme HQ", "HQ", null);
        desk = user(tenantId, orgUnitId, "billing@acme.test");
        portalUser = user(tenantId, orgUnitId, "accounts@client-a.test");

        clientA = asSetup(tenantId, () -> fleet.registerPartner(orgUnitId, "CLA-" + salt,
                "Client A", BusinessPartner.PartnerType.CUSTOMER, "TAX-A-" + salt));
        clientB = asSetup(tenantId, () -> fleet.registerPartner(orgUnitId, "CLB-" + salt,
                "Client B", BusinessPartner.PartnerType.CUSTOMER, "TAX-B-" + salt));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a month of warehouse usage is billed once, with its lines, and rerunning it changes nothing")
    void monthlyRunOverHttp() {
        ClientBillingApi api = client(desk, DESK);
        publishContract(api, clientA, "A");

        record(api, clientA, ClientServiceType.STORAGE, null, "300", 5, "snap-1");
        record(api, clientA, ClientServiceType.HANDLING_IN, null, "40", 2, "grn-1");
        record(api, clientA, ClientServiceType.VALUE_ADDED, "LABEL", "200", 21, "vas-1");
        // A retried feed: the same key is the same charge.
        UUID first = record(api, clientA, ClientServiceType.HANDLING_OUT, null, "35", 20, "gdn-1");
        UUID retry = record(api, clientA, ClientServiceType.HANDLING_OUT, null, "35", 20, "gdn-1");
        assertThat(retry).isEqualTo(first);

        ClientChargeSlice charges = api.listClientCharges(LAST_MONTH, clientA, null, null);
        assertThat(charges.getItems()).hasSize(4);

        UUID invoiceId = api.runClientBilling(clientA,
                new ClientBillingRunRequest().period(LAST_MONTH.plusDays(9)), null).getId();
        UUID rerun = api.runClientBilling(clientA,
                new ClientBillingRunRequest().period(LAST_MONTH), null).getId();
        assertThat(rerun).isEqualTo(invoiceId);

        ClientInvoice invoice = api.getClientInvoice(invoiceId);
        assertThat(invoice.getPeriodStart()).isEqualTo(LAST_MONTH);
        assertThat(invoice.getStatus()).isEqualTo(ClientInvoice.StatusEnum.ISSUED);
        assertThat(invoice.getLineCount()).isEqualTo(4);
        // 300 x 12 + 40 x 25 + 35 x 30 + 200 x 2
        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("6050");

        List<ClientInvoiceLine> lines = api.getClientInvoiceLines(invoiceId);
        assertThat(lines).extracting(ClientInvoiceLine::getServiceType).containsExactly(
                ClientServiceType.HANDLING_IN, ClientServiceType.STORAGE,
                ClientServiceType.HANDLING_OUT, ClientServiceType.VALUE_ADDED);
        assertThat(lines).allSatisfy(line ->
                assertThat(line.getContractCode()).isEqualTo("A-CONTRACT"));
        assertThat(api.listClientInvoices(clientA, null, null).getItems()).hasSize(1);

        // The month is closed: a late charge goes to this month, and the
        // invoice already sent is untouched.
        record(api, clientA, ClientServiceType.STORAGE, null, "10", 28, "snap-late");
        ClientChargeSlice thisMonth = api.listClientCharges(
                LocalDate.now(ZoneOffset.UTC), clientA, null, null);
        assertThat(thisMonth.getItems()).hasSize(1);
        assertThat(thisMonth.getItems().get(0).getLate()).isTrue();
        assertThat(api.getClientInvoice(invoiceId).getTotalAmount()).isEqualByComparingTo("6050");

        // Warehouse only: nothing owed to carriers, so the margin is the whole
        // receivable.
        List<ClientMargin> margins = api.listClientMargins(LAST_MONTH, null, null).getItems();
        assertThat(margins).hasSize(1);
        assertThat(margins.get(0).getReceivable()).isEqualByComparingTo("6050");
        assertThat(margins.get(0).getWarehouseReceivable()).isEqualByComparingTo("6050");
        assertThat(margins.get(0).getCarrierPayable()).isEqualByComparingTo("0");
        assertThat(margins.get(0).getMargin()).isEqualByComparingTo("6050");
    }

    @Test
    @DisplayName("a client portal user sees only their own client")
    void portalIsolationOverHttp() {
        ClientBillingApi api = client(desk, DESK);
        publishContract(api, clientA, "A");
        publishContract(api, clientB, "B");
        record(api, clientA, ClientServiceType.STORAGE, null, "100", 5, "a-1");
        record(api, clientB, ClientServiceType.STORAGE, null, "100", 5, "b-1");
        UUID invoiceA = api.runClientBilling(clientA,
                new ClientBillingRunRequest().period(LAST_MONTH), null).getId();
        UUID invoiceB = api.runClientBilling(clientB,
                new ClientBillingRunRequest().period(LAST_MONTH), null).getId();

        api.grantClientPortalAccess(clientA,
                new ClientPortalUserRequest().userId(portalUser.id()), null);
        ClientBillingApi portal = client(portalUser, PORTAL);

        assertThat(portal.listClientInvoices(clientA, null, null).getItems())
                .extracting(ClientInvoice::getId).containsExactly(invoiceA);
        assertThat(portal.getClientInvoice(invoiceA).getClientPartnerId()).isEqualTo(clientA);
        List<ClientContract> own = portal.listClientContracts(clientA);
        assertThat(own).hasSize(1);
        assertThat(own.get(0).getValueAddedServices())
                .extracting(ClientContractServiceRate::getServiceCode).containsExactly("LABEL");

        assertStatus(() -> portal.listClientInvoices(clientB, null, null), 403);
        assertStatus(() -> portal.listClientContracts(clientB), 403);
        assertStatus(() -> portal.listClientCharges(LAST_MONTH, clientB, null, null), 403);
        // Another client's invoice is indistinguishable from no invoice.
        assertStatus(() -> portal.getClientInvoice(invoiceB), 404);
        assertThat(portal.getClientInvoiceLines(invoiceB)).isEmpty();
        // The margin is the 3PL's own number, whatever the token carries.
        assertStatus(() -> portal.listClientMargins(LAST_MONTH, null, null), 403);
        // And a read-only role cannot bill.
        assertStatus(() -> portal.runClientBilling(clientA,
                new ClientBillingRunRequest().period(LAST_MONTH), null), 403);
    }

    @Test
    @DisplayName("refusals arrive as the documented statuses")
    void refusals() {
        ClientBillingApi api = client(desk, DESK);

        // No contract yet: there is nothing to charge the client under.
        assertStatus(() -> api.recordClientServiceUsage(clientA, usage(
                ClientServiceType.STORAGE, null, "1", 3, "x-1"), null), 409);

        publishContract(api, clientA, "A");
        // A month still running cannot be closed.
        assertStatus(() -> api.runClientBilling(clientA, new ClientBillingRunRequest()
                .period(LocalDate.now(ZoneOffset.UTC)), null), 409);
        // Transport comes from trips, not from a hand-entered record.
        assertStatus(() -> api.recordClientServiceUsage(clientA, usage(
                ClientServiceType.TRANSPORT, null, "100", 3, "x-2"), null), 409);
        // A backdated successor would put two contracts in force on one day.
        assertStatus(() -> api.publishClientContract(contractRequest(clientA, "A-OLD",
                LAST_MONTH.minusMonths(6)), null), 409);
        // A missing source key fails validation before reaching the domain.
        assertStatus(() -> api.recordClientServiceUsage(clientA, new ServiceUsageRequest()
                .serviceType(ClientServiceType.STORAGE).quantity(BigDecimal.ONE)
                .occurredOn(LAST_MONTH), null), 400);
        // A user without the contract permission cannot publish one.
        ClientBillingApi readOnly = client(desk, List.of("CLIENT_BILLING_READ"));
        assertStatus(() -> readOnly.publishClientContract(
                contractRequest(clientB, "B", LAST_MONTH.minusMonths(1)), null), 403);
    }

    // --------------------------------------------------------------- helpers

    private void publishContract(ClientBillingApi api, UUID client, String prefix) {
        api.publishClientContract(contractRequest(client, prefix + "-CONTRACT",
                LAST_MONTH.minusMonths(2)), null);
    }

    private PublishClientContractRequest contractRequest(UUID client, String code,
                                                         LocalDate from) {
        return new PublishClientContractRequest()
                .orgUnitId(orgUnitId)
                .code(code)
                .clientPartnerId(client)
                .effectiveFrom(from)
                .transportRatePerKg(new BigDecimal("5.00"))
                .transportMinimumCharge(BigDecimal.ZERO)
                .storageRatePerPalletDay(new BigDecimal("12"))
                .handlingInRatePerPallet(new BigDecimal("25"))
                .handlingOutRatePerPallet(new BigDecimal("30"))
                .valueAddedServices(List.of(new ClientContractServiceRate()
                        .serviceCode("LABEL").unit("UNIT").rate(new BigDecimal("2.00"))));
    }

    private UUID record(ClientBillingApi api, UUID client, ClientServiceType type, String code,
                        String quantity, int dayOfLastMonth, String key) {
        return api.recordClientServiceUsage(client,
                usage(type, code, quantity, dayOfLastMonth, key), null).getId();
    }

    private static ServiceUsageRequest usage(ClientServiceType type, String code,
                                             String quantity, int dayOfLastMonth, String key) {
        return new ServiceUsageRequest()
                .serviceType(type)
                .serviceCode(code)
                .quantity(new BigDecimal(quantity))
                .occurredOn(LAST_MONTH.withDayOfMonth(dayOfLastMonth))
                .reference("WMS " + key)
                .sourceKey(key);
    }

    private static void assertStatus(Runnable call, int status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(FeignException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    private ClientBillingApi client(AppUser who, List<String> permissions) {
        String token = tokens.issueAccessToken(who, orgPath, permissions).value();
        ApiClient apiClient = new ApiClient().setBasePath("http://localhost:" + port);
        apiClient.getFeignBuilder().requestInterceptor(template ->
                template.header("Authorization", "Bearer " + token));
        return apiClient.buildClient(ClientBillingApi.class);
    }

    private AppUser user(UUID tenantId, UUID orgUnitId, String email) {
        UUID id = fixtures.createUser(tenantId, orgUnitId, orgPath, email, "not-used-here");
        return new AppUser(id, tenantId, orgUnitId, email, "not-used", email,
                AppUser.UserStatus.ACTIVE, null, 0, null, null, null, Instant.now(), Instant.now());
    }

    private <T> T asSetup(UUID tenantId, Supplier<T> action) {
        Set<SimpleGrantedAuthority> authorities = List.of("PARTNER_CREATE").stream()
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
