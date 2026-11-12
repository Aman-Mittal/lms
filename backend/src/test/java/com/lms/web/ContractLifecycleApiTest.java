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
import java.util.UUID;
import java.util.function.Supplier;

import com.lms.acceptance.TestFixtures;
import com.lms.client.ApiClient;
import com.lms.client.api.ApprovalsApi;
import com.lms.client.api.ClientBillingApi;
import com.lms.client.api.NumberSeriesApi;
import com.lms.client.model.ApprovalTicket;
import com.lms.client.model.ApproveRequest;
import com.lms.client.model.ClientContract;
import com.lms.client.model.ClientContractChargeAdditionRequest;
import com.lms.client.model.ClientContractChecklistItem;
import com.lms.client.model.ClientContractDraftRequest;
import com.lms.client.model.ClientContractExtensionRequest;
import com.lms.client.model.ClientContractServiceRate;
import com.lms.client.model.ClientServiceType;
import com.lms.client.model.CreateNumberSeriesRequest;
import com.lms.client.model.NumberYearCode;
import com.lms.client.model.NumberedDocumentType;
import com.lms.client.model.RejectApprovalRequest;
import com.lms.client.model.ReviseClientContractRequest;
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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The client contract lifecycle over HTTP: draft, checklist, verification
 * through approvals, end dates and expiry, extension and charge additions.
 *
 * <p>Two people, because the point of verification is that the maker is not
 * the checker: the maker holds {@code CLIENT_CONTRACT_MANAGE}, the approver
 * {@code CLIENT_CONTRACT_APPROVE}, and the default policy refuses
 * self-approval. Every transition is checked against its audit row.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContractLifecycleApiTest {

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

    private static final List<String> MAKER = List.of("CLIENT_CONTRACT_MANAGE", "CLIENT_BILLING_READ",
            "CLIENT_BILLING_RUN", "APPROVAL_READ");
    private static final List<String> CHECKER = List.of("CLIENT_CONTRACT_APPROVE", "APPROVAL_READ");
    private static final String SETUP_PRINCIPAL = "setup";
    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    @LocalServerPort
    private int port;

    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private FleetCommandService fleet;
    @Autowired
    private TokenService tokens;
    @Autowired
    private JdbcClient jdbc;

    private String orgPath;
    private UUID tenantId;
    private UUID orgUnitId;
    private AppUser maker;
    private AppUser checker;
    private UUID client;
    private UUID otherClient;

    @BeforeEach
    void aTenantWithTwoPeopleAndAClient() {
        String salt = UUID.randomUUID().toString().substring(0, 8);
        orgPath = "/acme-" + salt + "/";
        tenantId = fixtures.createTenant("acme-" + salt, "Acme Logistics");
        orgUnitId = fixtures.createOrgUnit(tenantId, orgPath, "Acme HQ", "HQ", null);
        maker = user("maker-" + salt + "@acme.test");
        checker = user("checker-" + salt + "@acme.test");
        client = asSetup(() -> fleet.registerPartner(orgUnitId, "CLA-" + salt, "Client A",
                BusinessPartner.PartnerType.CUSTOMER, "TAX-A-" + salt));
        otherClient = asSetup(() -> fleet.registerPartner(orgUnitId, "CLB-" + salt, "Client B",
                BusinessPartner.PartnerType.CUSTOMER, "TAX-B-" + salt));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------ draft

    @Test
    @DisplayName("a draft is edited and deleted with If-Match, and nothing about it applies to billing")
    void draftCrud() {
        ClientBillingApi desk = api(ClientBillingApi.class, maker, MAKER);
        UUID id = desk.createClientContractDraft(draft(client, "D-1", TODAY.minusDays(30), null), null).getId();

        ClientContract read = desk.getClientContract(id);
        assertThat(read.getStatus()).isEqualTo(ClientContract.StatusEnum.DRAFT);
        assertThat(read.getState()).isEqualTo(ClientContract.StateEnum.DRAFT);
        assertThat(read.getCode()).isEqualTo("D-1");
        assertThat(read.getValidTill()).isNull();

        // A draft is no contract: the client has nothing to be charged under.
        assertProblem(() -> desk.recordClientServiceUsage(client, usage("d-1"), null), 409, null);

        assertProblem(() -> desk.reviseClientContractDraft("\"99\"", id, revise("D-1B", TODAY.minusDays(30)), null),
                409, "concurrent-modification");
        assertThatThrownBy(() -> desk.reviseClientContractDraft("", id, revise("D-1B", TODAY.minusDays(30)), null))
                .isInstanceOfSatisfying(FeignException.class, e -> assertThat(e.status()).isEqualTo(400));

        desk.reviseClientContractDraft(etag(read), id, revise("D-1B", TODAY.minusDays(20))
                .transportRatePerKg(new BigDecimal("7.50")).valueAddedServices(List.of(
                        new ClientContractServiceRate().serviceCode("PACK").unit("UNIT").rate(BigDecimal.ONE))), null);
        ClientContract revised = desk.getClientContract(id);
        assertThat(revised.getCode()).isEqualTo("D-1B");
        assertThat(revised.getTransportRatePerKg()).isEqualByComparingTo("7.50");
        assertThat(revised.getEffectiveFrom()).isEqualTo(TODAY.minusDays(20));
        assertThat(revised.getValueAddedServices()).extracting(ClientContractServiceRate::getServiceCode)
                .containsExactly("PACK");
        assertThat(revised.getVersion()).isGreaterThan(read.getVersion());
        assertThat(audited("CLIENT_CONTRACT_DRAFTED", id)).isEqualTo(1);
        assertThat(audited("CLIENT_CONTRACT_DRAFT_REVISED", id)).isEqualTo(1);
        assertThat(auditAfter("CLIENT_CONTRACT_DRAFT_REVISED", id)).contains("\"code\": \"D-1B\"");
        assertThat(auditBefore("CLIENT_CONTRACT_DRAFT_REVISED", id)).contains("\"code\": \"D-1\"");

        // The stale version from before the edit is stale for a delete too.
        assertProblem(() -> desk.deleteClientContractDraft(etag(read), id), 409, "concurrent-modification");
        desk.deleteClientContractDraft(etag(revised), id);
        assertStatus(() -> desk.getClientContract(id), 404);
        assertThat(audited("CLIENT_CONTRACT_DRAFT_DELETED", id)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM client_contract_service_rate WHERE contract_id = :id", id)).isZero();
    }

    @Test
    @DisplayName("an end date before the start is refused, and an unnumbered draft takes the CONTRACT series number")
    void validityAndNumbering() {
        ClientBillingApi desk = api(ClientBillingApi.class, maker, MAKER);
        assertProblem(() -> desk.createClientContractDraft(
                draft(client, "BAD", TODAY, TODAY.minusDays(1)), null), 409, "client-contract-validity-invalid");

        // Without a series and without a code there is nothing to call it.
        assertThatThrownBy(() -> desk.createClientContractDraft(
                draft(client, null, TODAY.minusDays(5), null), null))
                .isInstanceOfSatisfying(FeignException.class, e -> assertThat(e.status()).isEqualTo(400));

        api(NumberSeriesApi.class, maker, List.of("NUMBER_SERIES_MANAGE")).createNumberSeries(
                new CreateNumberSeriesRequest().code("contracts").documentType(NumberedDocumentType.CONTRACT)
                        .prefix("CTR").yearCode(NumberYearCode.NONE), null);
        UUID numbered = desk.createClientContractDraft(draft(client, null, TODAY.minusDays(5), null), null).getId();
        assertThat(desk.getClientContract(numbered).getCode()).startsWith("CTR");

        // Deleting the draft gives the number back to the series.
        desk.deleteClientContractDraft(etag(desk.getClientContract(numbered)), numbered);
        assertThat(count("SELECT count(*) FROM number_series_release WHERE tenant_id = :id", tenantId)).isEqualTo(1);
    }

    // -------------------------------------------------------------- checklist

    @Test
    @DisplayName("verification is refused until the checklist passes, and the refusal lists every failing rule")
    void checklistRefusal() {
        ClientBillingApi desk = api(ClientBillingApi.class, maker, MAKER);
        // No transport price, and an end date that has already gone by.
        UUID id = desk.createClientContractDraft(draft(client, "CL-1", TODAY.minusDays(20), TODAY.minusDays(2))
                .transportRatePerKg(BigDecimal.ZERO).transportMinimumCharge(BigDecimal.ZERO), null).getId();

        List<ClientContractChecklistItem> items = desk.getClientContractChecklist(id);
        assertThat(items).extracting(i -> i.getCode().getValue())
                .containsExactly("CUSTOMER_TRADING", "EFFECTIVE_DATES", "TRANSPORT_RATE", "TAX_RATES", "ORG_UNIT");
        assertThat(items).filteredOn(i -> !i.getPassed()).extracting(i -> i.getCode().getValue())
                .containsExactly("EFFECTIVE_DATES", "TRANSPORT_RATE");
        assertThat(items).allMatch(ClientContractChecklistItem::getRequired);

        FeignException refused = catchFeign(() -> desk.requestClientContractVerification(
                etag(desk.getClientContract(id)), id, null));
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.contentUTF8()).contains("contract-checklist-incomplete")
                .contains("EFFECTIVE_DATES").contains("TRANSPORT_RATE").doesNotContain("TAX_RATES\":");
        assertThat(desk.getClientContract(id).getStatus()).isEqualTo(ClientContract.StatusEnum.DRAFT);

        // Corrected, it passes and is submitted.
        desk.reviseClientContractDraft(etag(desk.getClientContract(id)), id,
                revise("CL-1", TODAY.minusDays(20)).transportRatePerKg(new BigDecimal("4")), null);
        assertThat(desk.getClientContractChecklist(id)).allMatch(ClientContractChecklistItem::getPassed);
        assertThat(desk.requestClientContractVerification(etag(desk.getClientContract(id)), id, null)).isNotNull();
    }

    // ----------------------------------------------------------- verification

    @Test
    @DisplayName("verification freezes the draft; a second person approving puts it in force and closes the incumbent")
    void approveActivates() {
        ClientBillingApi desk = api(ClientBillingApi.class, maker, MAKER);
        UUID first = activate("V-1", TODAY.minusDays(60));

        UUID id = desk.createClientContractDraft(draft(client, "V-2", TODAY.minusDays(10), null), null).getId();
        ApprovalTicket ticket = desk.requestClientContractVerification(etag(desk.getClientContract(id)), id, null);
        assertThat(ticket.getSubjectType()).isEqualTo("CONTRACT_ACTIVATION");
        assertThat(ticket.getAction()).isEqualTo("VERIFY");
        ClientContract pending = desk.getClientContract(id);
        assertThat(pending.getStatus()).isEqualTo(ClientContract.StatusEnum.PENDING_VERIFICATION);
        assertThat(audited("CLIENT_CONTRACT_VERIFICATION_REQUESTED", id)).isEqualTo(1);

        // Frozen: not editable, not deletable, not submitted twice; the incumbent is untouched.
        assertProblem(() -> desk.reviseClientContractDraft(etag(pending), id, revise("V-2", TODAY.minusDays(10)), null),
                409, "client-contract-not-draft");
        assertProblem(() -> desk.deleteClientContractDraft(etag(pending), id), 409, "client-contract-not-draft");
        assertProblem(() -> desk.requestClientContractVerification(etag(pending), id, null), 409,
                "client-contract-not-draft");
        assertThat(desk.getClientContract(first).getEffectiveTo()).isNull();
        // Nothing is in force on its start day yet but the incumbent.
        assertProblem(() -> api(ApprovalsApi.class, maker, MAKER).approveApproval(
                "\"" + ticket.getVersion() + "\"", ticket.getId(), null, new ApproveRequest()), 409,
                "approval-self-decision");
        assertProblem(() -> api(ApprovalsApi.class, checker, List.of("APPROVAL_READ")).approveApproval(
                "\"" + ticket.getVersion() + "\"", ticket.getId(), null, new ApproveRequest()), 403, null);

        api(ApprovalsApi.class, checker, CHECKER).approveApproval(
                "\"" + ticket.getVersion() + "\"", ticket.getId(), null, new ApproveRequest());

        ClientContract active = desk.getClientContract(id);
        assertThat(active.getStatus()).isEqualTo(ClientContract.StatusEnum.ACTIVE);
        assertThat(active.getEffectiveTo()).isNull();
        assertThat(desk.getClientContract(first).getEffectiveTo()).isEqualTo(TODAY.minusDays(11));
        assertThat(audited("CLIENT_CONTRACT_ACTIVATED", id)).isEqualTo(1);
        assertThat(auditAfter("CLIENT_CONTRACT_ACTIVATED", id)).contains("\"status\": \"ACTIVE\"");
        assertThat(auditBefore("CLIENT_CONTRACT_ACTIVATED", id)).contains("\"status\": \"PENDING_VERIFICATION\"");

        // In force now: it is edited no more, and the database says so too.
        assertThatThrownBy(() -> jdbc.sql("UPDATE client_contract SET transport_rate_per_kg = 1 WHERE id = :id")
                .param("id", id).update()).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("cannot be edited");
        assertProblem(() -> desk.deleteClientContractDraft(etag(active), id), 409, "client-contract-not-draft");
    }

    @Test
    @DisplayName("a rejection sends the draft back with the reason; it can be corrected and asked again")
    void rejectReturnsToDraft() {
        ClientBillingApi desk = api(ClientBillingApi.class, maker, MAKER);
        UUID id = desk.createClientContractDraft(draft(client, "R-1", TODAY.minusDays(10), null), null).getId();
        ApprovalTicket ticket = desk.requestClientContractVerification(etag(desk.getClientContract(id)), id, null);

        api(ApprovalsApi.class, checker, CHECKER).rejectApproval("\"" + ticket.getVersion() + "\"", ticket.getId(),
                new RejectApprovalRequest().reason("Rate sheet not attached"), null);

        ClientContract back = desk.getClientContract(id);
        assertThat(back.getStatus()).isEqualTo(ClientContract.StatusEnum.DRAFT);
        assertThat(back.getRejectionReason()).isEqualTo("Rate sheet not attached");
        assertThat(audited("CLIENT_CONTRACT_VERIFICATION_REJECTED", id)).isEqualTo(1);
        assertThat(auditAfter("CLIENT_CONTRACT_VERIFICATION_REJECTED", id)).contains("Rate sheet not attached");

        desk.reviseClientContractDraft(etag(back), id, revise("R-1", TODAY.minusDays(10))
                .transportRatePerKg(new BigDecimal("6")), null);
        ApprovalTicket again = desk.requestClientContractVerification(etag(desk.getClientContract(id)), id, null);
        assertThat(again.getId()).isNotEqualTo(ticket.getId());
        assertThat(desk.getClientContract(id).getRejectionReason()).isNull();
    }

    @Test
    @DisplayName("a draft is invisible to the client's own portal user until it is in force")
    void draftsAreInternal() {
        ClientBillingApi desk = api(ClientBillingApi.class, maker, MAKER);
        UUID id = desk.createClientContractDraft(draft(client, "P-1", TODAY.minusDays(10), null), null).getId();
        AppUser portal = user("portal-" + id.toString().substring(0, 6) + "@client.test");
        desk.grantClientPortalAccess(client, new com.lms.client.model.ClientPortalUserRequest().userId(portal.id()),
                null);
        ClientBillingApi own = api(ClientBillingApi.class, portal, List.of("CLIENT_BILLING_READ"));
        assertThat(own.listClientContracts(client)).isEmpty();
        assertStatus(() -> own.getClientContract(id), 404);
        assertThat(desk.listClientContracts(client)).extracting(ClientContract::getId).containsExactly(id);
    }

    // ------------------------------------------------------- end date, expiry

    @Test
    @DisplayName("expiry is derived from the end date: no job, and nothing is in force after it")
    void expiryIsDerived() {
        ClientBillingApi desk = api(ClientBillingApi.class, maker, MAKER);
        UUID live = activate("E-1", TODAY.minusDays(40), TODAY.plusDays(30));
        assertThat(desk.getClientContract(live).getState()).isEqualTo(ClientContract.StateEnum.ACTIVE);

        // A version that lapsed yesterday, as it would be the day after its end date.
        UUID lapsed = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO client_contract (id, tenant_id, org_unit_id, code, client_partner_id, currency,
                                                     effective_from, valid_till, transport_rate_per_kg, status, version)
                        VALUES (:id, :tenant, :unit, 'E-OLD', :client, 'INR', :from, :till, 5, 'ACTIVE', 0)
                        """)
                .param("id", lapsed).param("tenant", tenantId).param("unit", orgUnitId).param("client", otherClient)
                .param("from", TODAY.minusDays(60)).param("till", TODAY.minusDays(1)).update();
        assertThat(desk.listClientContracts(otherClient)).singleElement().satisfies(c -> {
            assertThat(c.getStatus()).isEqualTo(ClientContract.StatusEnum.ACTIVE);
            assertThat(c.getState()).isEqualTo(ClientContract.StateEnum.EXPIRED);
        });
        // Nothing prices work done after the end date.
        assertProblem(() -> desk.recordClientServiceUsage(otherClient, usage("e-1"), null), 409, null);
    }

    // -------------------------------------------------------------- extension

    @Test
    @DisplayName("an approved extension moves the end date later, audited, and nothing else about a contract in force moves")
    void extension() {
        ClientBillingApi desk = api(ClientBillingApi.class, maker, MAKER);
        UUID openEnded = activate("X-0", TODAY.minusDays(90));
        assertProblem(() -> desk.requestClientContractExtension(openEnded, extension(TODAY.plusDays(10)), null),
                409, "client-contract-no-end-date");

        UUID id = activate("X-1", TODAY.minusDays(40), TODAY.plusDays(10), otherClient);
        assertProblem(() -> desk.requestClientContractExtension(id, extension(TODAY.plusDays(10)), null), 409,
                "client-contract-extension-not-later");
        assertThatThrownBy(() -> desk.requestClientContractExtension(id, new ClientContractExtensionRequest()
                .validTill(TODAY.plusDays(40)).reason(" "), null)).isInstanceOfSatisfying(FeignException.class,
                e -> assertThat(e.status()).isEqualTo(400));

        ApprovalTicket ticket = desk.requestClientContractExtension(id, extension(TODAY.plusDays(40)), null);
        assertThat(ticket.getSubjectType()).isEqualTo("CONTRACT_EXTENSION");
        assertThat(desk.getClientContract(id).getValidTill()).isEqualTo(TODAY.plusDays(10));
        assertProblem(() -> desk.requestClientContractExtension(id, extension(TODAY.plusDays(50)), null), 409,
                "approval-already-open");

        approve(ticket);
        ClientContract extended = desk.getClientContract(id);
        assertThat(extended.getValidTill()).isEqualTo(TODAY.plusDays(40));
        assertThat(extended.getStatus()).isEqualTo(ClientContract.StatusEnum.ACTIVE);
        assertThat(audited("CLIENT_CONTRACT_EXTENSION_REQUESTED", id)).isEqualTo(1);
        assertThat(audited("CLIENT_CONTRACT_EXTENDED", id)).isEqualTo(1);
        assertThat(auditBefore("CLIENT_CONTRACT_EXTENDED", id)).contains(TODAY.plusDays(10).toString());
        assertThat(auditAfter("CLIENT_CONTRACT_EXTENDED", id)).contains(TODAY.plusDays(40).toString())
                .contains("Customer renewed");

        // The extension is the only door: the database refuses the date moved any other way, or shortened.
        assertThatThrownBy(() -> jdbc.sql("UPDATE client_contract SET valid_till = :t WHERE id = :id")
                .param("t", TODAY.plusDays(90)).param("id", id).update()).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("extension");
    }

    // ------------------------------------------------------------- charge add

    @Test
    @DisplayName("an approved charge addition publishes a successor from the date, and reprices nothing before it")
    void chargeAddition() {
        ClientBillingApi desk = api(ClientBillingApi.class, maker, MAKER);
        UUID id = activate("C-1", TODAY.minusDays(30));
        LocalDate from = TODAY.plusDays(5);

        assertProblem(() -> desk.requestClientContractChargeAddition(id, addition("LABEL", from.minusDays(30)), null),
                409, "client-contract-charge-add-backdated");
        assertProblem(() -> desk.requestClientContractChargeAddition(id, addition("LABEL", from), null), 409,
                "client-service-rate-duplicated");

        ApprovalTicket ticket = desk.requestClientContractChargeAddition(id, addition("KITTING", from), null);
        assertThat(ticket.getSubjectType()).isEqualTo("CONTRACT_CHARGE_ADD");
        assertProblem(() -> desk.requestClientContractChargeAddition(id, addition("SHRINK", from), null), 409,
                "approval-already-open");
        assertThat(desk.listClientContracts(client)).hasSize(1);

        approve(ticket);

        List<ClientContract> versions = desk.listClientContracts(client);
        assertThat(versions).hasSize(2);
        ClientContract successor = versions.get(0);
        ClientContract incumbent = versions.get(1);
        assertThat(successor.getId()).isNotEqualTo(id);
        assertThat(successor.getEffectiveFrom()).isEqualTo(from);
        assertThat(successor.getStatus()).isEqualTo(ClientContract.StatusEnum.ACTIVE);
        assertThat(successor.getValueAddedServices()).extracting(ClientContractServiceRate::getServiceCode)
                .containsExactly("KITTING", "LABEL");
        assertThat(successor.getTransportRatePerKg()).isEqualByComparingTo(incumbent.getTransportRatePerKg());
        assertThat(incumbent.getId()).isEqualTo(id);
        assertThat(incumbent.getEffectiveTo()).isEqualTo(from.minusDays(1));
        assertThat(incumbent.getValueAddedServices()).extracting(ClientContractServiceRate::getServiceCode)
                .containsExactly("LABEL");
        assertThat(audited("CLIENT_CONTRACT_CHARGE_ADD_REQUESTED", id)).isEqualTo(1);
        assertThat(audited("CLIENT_CONTRACT_CHARGE_ADDED", id)).isEqualTo(1);
        assertThat(auditAfter("CLIENT_CONTRACT_CHARGE_ADDED", id)).contains(successor.getId().toString())
                .contains("KITTING").contains("Client asked for kitting");
    }

    // --------------------------------------------------------------- isolation

    @Test
    @DisplayName("another tenant's contract is invisible: 404 to read, and no edit, delete or request reaches it")
    void tenantIsolation() {
        UUID id = api(ClientBillingApi.class, maker, MAKER).createClientContractDraft(
                draft(client, "T-1", TODAY.minusDays(10), null), null).getId();

        UUID otherTenant = fixtures.createTenant("other-" + id.toString().substring(0, 6), "Other Logistics");
        String otherPath = "/other-" + id.toString().substring(0, 6) + "/";
        UUID otherUnit = fixtures.createOrgUnit(otherTenant, otherPath, "Other HQ", "HQ", null);
        UUID otherUserId = fixtures.createUser(otherTenant, otherUnit, otherPath, "x@other.test", "not-used-here");
        AppUser stranger = new AppUser(otherUserId, otherTenant, otherUnit, "x@other.test", "not-used", "x@other.test",
                AppUser.UserStatus.ACTIVE, null, 0, null, null, null, Instant.now(), Instant.now());
        String token = tokens.issueAccessToken(stranger, otherPath, MAKER).value();
        ClientBillingApi foreign = clientWithToken(ClientBillingApi.class, token);

        assertStatus(() -> foreign.getClientContract(id), 404);
        // A write by another tenant never reaches the contract: 404 as for any unknown id, or 403 where an
        // access policy answers first for a record it knows nothing about.
        assertHidden(() -> foreign.reviseClientContractDraft("\"0\"", id, revise("T-X", TODAY), null));
        assertHidden(() -> foreign.deleteClientContractDraft("\"0\"", id));
        assertHidden(() -> foreign.requestClientContractVerification("\"0\"", id, null));
        assertHidden(() -> foreign.getClientContractChecklist(id));
        assertHidden(() -> foreign.requestClientContractExtension(id, extension(TODAY.plusDays(9)), null));
        assertHidden(() -> foreign.requestClientContractChargeAddition(id, addition("X", TODAY.plusDays(9)), null));
        assertThat(api(ClientBillingApi.class, maker, MAKER).getClientContract(id).getCode()).isEqualTo("T-1");
    }

    // ---------------------------------------------------------------- helpers

    /** A contract in force from {@code from}, through the real flow: draft, ask, a second person approves. */
    private UUID activate(String code, LocalDate from) {
        return activate(code, from, null, client);
    }

    private UUID activate(String code, LocalDate from, LocalDate till) {
        return activate(code, from, till, client);
    }

    private UUID activate(String code, LocalDate from, LocalDate till, UUID forClient) {
        ClientBillingApi desk = api(ClientBillingApi.class, maker, MAKER);
        UUID id = desk.createClientContractDraft(draft(forClient, code, from, till), null).getId();
        approve(desk.requestClientContractVerification(etag(desk.getClientContract(id)), id, null));
        assertThat(desk.getClientContract(id).getStatus()).isEqualTo(ClientContract.StatusEnum.ACTIVE);
        return id;
    }

    private void approve(ApprovalTicket ticket) {
        api(ApprovalsApi.class, checker, CHECKER).approveApproval("\"" + ticket.getVersion() + "\"",
                ticket.getId(), null, new ApproveRequest());
    }

    private ClientContractDraftRequest draft(UUID forClient, String code, LocalDate from, LocalDate till) {
        return new ClientContractDraftRequest().orgUnitId(orgUnitId).code(code).clientPartnerId(forClient)
                .effectiveFrom(from).validTill(till)
                .transportRatePerKg(new BigDecimal("5.00")).transportMinimumCharge(BigDecimal.ZERO)
                .storageRatePerPalletDay(new BigDecimal("12")).handlingInRatePerPallet(new BigDecimal("25"))
                .handlingOutRatePerPallet(new BigDecimal("30"))
                .valueAddedServices(List.of(new ClientContractServiceRate()
                        .serviceCode("LABEL").unit("UNIT").rate(new BigDecimal("2.00"))));
    }

    private ReviseClientContractRequest revise(String code, LocalDate from) {
        return new ReviseClientContractRequest().orgUnitId(orgUnitId).code(code).effectiveFrom(from)
                .transportRatePerKg(new BigDecimal("5.00")).transportMinimumCharge(BigDecimal.ZERO)
                .storageRatePerPalletDay(new BigDecimal("12")).handlingInRatePerPallet(new BigDecimal("25"))
                .handlingOutRatePerPallet(new BigDecimal("30"));
    }

    private static ClientContractExtensionRequest extension(LocalDate till) {
        return new ClientContractExtensionRequest().validTill(till).reason("Customer renewed");
    }

    private static ClientContractChargeAdditionRequest addition(String service, LocalDate from) {
        return new ClientContractChargeAdditionRequest().serviceCode(service).unit("UNIT")
                .rate(new BigDecimal("3.50")).effectiveFrom(from).reason("Client asked for kitting");
    }

    private static ServiceUsageRequest usage(String key) {
        return new ServiceUsageRequest().serviceType(ClientServiceType.STORAGE).quantity(BigDecimal.ONE)
                .occurredOn(TODAY.minusDays(1)).reference("WMS " + key).sourceKey(key);
    }

    private static String etag(ClientContract contract) {
        return "\"" + contract.getVersion() + "\"";
    }

    private long audited(String action, UUID resourceId) {
        return count("SELECT count(*) FROM audit_log WHERE action = '" + action + "' AND resource_id = :id",
                resourceId);
    }

    private String auditAfter(String action, UUID resourceId) {
        return jdbc.sql("SELECT after_state::text FROM audit_log WHERE action = :a AND resource_id = :id"
                + " ORDER BY occurred_at DESC LIMIT 1").param("a", action).param("id", resourceId)
                .query(String.class).single();
    }

    private String auditBefore(String action, UUID resourceId) {
        return jdbc.sql("SELECT before_state::text FROM audit_log WHERE action = :a AND resource_id = :id"
                + " ORDER BY occurred_at DESC LIMIT 1").param("a", action).param("id", resourceId)
                .query(String.class).single();
    }

    private long count(String sql, UUID id) {
        return jdbc.sql(sql).param("id", id).query(Long.class).single();
    }

    private static void assertStatus(Runnable call, int status) {
        assertThat(catchFeign(call).status()).isEqualTo(status);
    }

    private static void assertHidden(Runnable call) {
        assertThat(catchFeign(call).status()).isIn(403, 404);
    }

    /** The documented status, and when given the problem's stable code. */
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
        throw new AssertionError("The call should have been refused");
    }

    private <T extends ApiClient.Api> T api(Class<T> type, AppUser who, List<String> permissions) {
        return clientWithToken(type, tokens.issueAccessToken(who, orgPath, permissions).value());
    }

    private <T extends ApiClient.Api> T clientWithToken(Class<T> type, String token) {
        ApiClient apiClient = new ApiClient().setBasePath("http://localhost:" + port);
        apiClient.getFeignBuilder().requestInterceptor(template ->
                template.header("Authorization", "Bearer " + token));
        return apiClient.buildClient(type);
    }

    private AppUser user(String email) {
        UUID id = fixtures.createUser(tenantId, orgUnitId, orgPath, email, "not-used-here");
        return new AppUser(id, tenantId, orgUnitId, email, "not-used", email,
                AppUser.UserStatus.ACTIVE, null, 0, null, null, null, Instant.now(), Instant.now());
    }

    private <T> T asSetup(Supplier<T> action) {
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("PARTNER_CREATE"));
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(SETUP_PRINCIPAL, null, authorities));
        try {
            return TenantContext.callWith(tenantId, orgPath, action);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
