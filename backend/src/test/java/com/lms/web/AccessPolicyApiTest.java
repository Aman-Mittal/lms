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
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import com.lms.acceptance.SpineFixtures;
import com.lms.acceptance.TestFixtures;
import com.lms.client.ApiClient;
import com.lms.client.api.AccessPoliciesApi;
import com.lms.client.api.AuthenticationApi;
import com.lms.client.api.PartnersApi;
import com.lms.client.api.TripsApi;
import com.lms.client.model.AccessPolicy;
import com.lms.client.model.AccessPolicySlice;
import com.lms.client.model.CreateAccessPolicyRequest;
import com.lms.client.model.LoginRequest;
import com.lms.client.model.RegisterPartnerRequest;
import com.lms.client.model.ReviseAccessPolicyRequest;
import com.lms.client.model.SetUserAccessAttributesRequest;
import com.lms.client.model.Trip;
import com.lms.client.model.UserAccessAttributes;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Access policies over HTTP, through a client generated from the contract.
 *
 * <p>Proves the two things a scenario driving services in-process cannot: that
 * the administration endpoints are usable as documented, and that a policy
 * written over the API, and an attribute set over the API, reach a real
 * signed-in user's next request through the token they actually receive.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccessPolicyApiTest {

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

    private static final String PASSWORD = "correct-horse-battery";

    @LocalServerPort
    private int port;

    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private TokenService tokens;
    @Autowired
    private JwtDecoder jwtDecoder;
    @Autowired
    private SpineFixtures spine;
    @Autowired
    private FleetCommandService fleet;
    @Autowired
    private TerminalCommandService terminals;
    @Autowired
    private TripCommandService trips;

    private UUID tenantId;

    private String tenantCode;
    private String rootPath;
    private UUID rootUnit;
    private UUID northUnit;
    private UUID southUnit;
    private AppUser admin;
    private AppUser clerk;

    @BeforeEach
    void aTenantWithTwoRegions() {
        String salt = UUID.randomUUID().toString().substring(0, 8);
        tenantCode = "acme-" + salt;
        rootPath = "/acme-" + salt + "/";
        tenantId = fixtures.createTenant(tenantCode, "Acme Logistics");
        rootUnit = fixtures.createOrgUnit(tenantId, rootPath, "Acme HQ", "HQ", null);
        northUnit = fixtures.createOrgUnit(tenantId, rootPath + "north/", "North", "REGION", rootUnit);
        southUnit = fixtures.createOrgUnit(tenantId, rootPath + "south/", "South", "REGION", rootUnit);

        admin = user(tenantId, rootUnit, rootPath, "admin@acme.test");
        clerk = user(tenantId, northUnit, rootPath + "north/", "clerk@acme.test");
    }

    @Test
    @DisplayName("a new tenant's default policies are listed, and a policy is created, revised and deleted")
    void policyLifecycleOverHttp() {
        AccessPoliciesApi api = accessApi(token(admin, rootPath, "ACCESS_POLICY_MANAGE"));

        AccessPolicySlice defaults = api.listAccessPolicies(null, null, null);
        assertThat(defaults.getItems()).extracting(AccessPolicy::getCode).containsExactly(
                "asn-write-org-subtree",
                "client-billing-write-org-subtree",
                "client-contract-write-org-subtree",
                "client-credit-note-write-org-subtree",
                "driver-write-org-subtree",
                "exception-close-holding-limit",
                "inventory-adjust-org-subtree",
                "invoice-approval-limit",
                "itinerary-write-org-subtree",
                "load-write-org-subtree",
                "milk-run-write-org-subtree",
                "order-write-org-subtree",
                "partner-write-org-subtree",
                "rail-tariff-write-org-subtree",
                "routing-guide-write-org-subtree",
                "terminal-write-org-subtree",
                "trip-write-org-subtree",
                "vehicle-write-org-subtree",
                "warehouse-setup-org-subtree");
        AccessPolicySlice forInvoices = api.listAccessPolicies("INVOICE_APPROVE", null, null);
        assertThat(forInvoices.getItems()).singleElement().satisfies(policy -> {
            assertThat(policy.getEffect()).isEqualTo(AccessPolicy.EffectEnum.DENY);
            assertThat(policy.getCondition()).containsEntry("attr", "resource.amount")
                    .containsEntry("ref", "subject.approvalLimit");
        });

        UUID id = api.createAccessPolicy(new CreateAccessPolicyRequest()
                .code("weekend-freeze")
                .action("INVOICE_APPROVE")
                .effect(CreateAccessPolicyRequest.EffectEnum.DENY)
                .description("No approvals at the weekend")
                .condition(Map.of("attr", "env.dayOfWeek", "op", "in",
                        "value", List.of("SATURDAY", "SUNDAY"))), null).getId();

        AccessPolicy created = api.getAccessPolicy(id);
        assertThat(created.getEnabled()).isTrue();
        assertThat(created.getCondition().get("value")).isEqualTo(List.of("SATURDAY", "SUNDAY"));

        api.reviseAccessPolicy(id, new ReviseAccessPolicyRequest()
                .effect(ReviseAccessPolicyRequest.EffectEnum.DENY)
                .description("No approvals on Sunday")
                .condition(Map.of("attr", "env.dayOfWeek", "op", "eq", "value", "SUNDAY"))
                .enabled(false));
        AccessPolicy revised = api.getAccessPolicy(id);
        assertThat(revised.getEnabled()).isFalse();
        assertThat(revised.getDescription()).isEqualTo("No approvals on Sunday");

        api.deleteAccessPolicy(id);
        assertStatus(() -> api.getAccessPolicy(id), 404, null);
    }

    @Test
    @DisplayName("a malformed rule, a duplicate code and a caller without the permission are refused")
    void refusals() {
        AccessPoliciesApi api = accessApi(token(admin, rootPath, "ACCESS_POLICY_MANAGE"));

        assertStatus(() -> api.createAccessPolicy(new CreateAccessPolicyRequest()
                .code("bad").action("PARTNER_CREATE")
                .effect(CreateAccessPolicyRequest.EffectEnum.DENY).description("Typo")
                .condition(Map.of("attr", "subject.regoin", "op", "eq", "value", "WEST")), null),
                400, "Unknown attribute");
        assertStatus(() -> api.createAccessPolicy(new CreateAccessPolicyRequest()
                .code("invoice-approval-limit").action("INVOICE_APPROVE")
                .effect(CreateAccessPolicyRequest.EffectEnum.DENY).description("Again")
                .condition(Map.of("attr", "subject.region", "op", "present")), null),
                409, "access-policy-code-taken");

        AccessPoliciesApi clerkApi = accessApi(token(clerk, rootPath + "north/", "PARTNER_CREATE"));
        assertStatus(() -> clerkApi.listAccessPolicies(null, null, null), 403, null);
        assertStatus(() -> clerkApi.setUserAccessAttributes(clerk.id(),
                new SetUserAccessAttributesRequest().approvalLimit(new BigDecimal("1"))), 403, null);
    }

    @Test
    @DisplayName("a regional user may register partners in their own subtree only")
    void orgSubtreeOverHttp() {
        PartnersApi partners = partnersApi(token(clerk, rootPath + "north/", "PARTNER_CREATE"));

        assertThat(partners.registerPartner(partner(northUnit, "NORTH"), null).getId()).isNotNull();
        assertStatus(() -> partners.registerPartner(partner(southUnit, "SOUTH"), null), 403, null);
        assertStatus(() -> partners.registerPartner(partner(rootUnit, "HQ"), null), 403, null);

        // Head office sits above both and may register in either.
        PartnersApi headOffice = partnersApi(token(admin, rootPath, "PARTNER_CREATE"));
        assertThat(headOffice.registerPartner(partner(southUnit, "SOUTH"), null).getId()).isNotNull();
    }

    @Test
    @DisplayName("a policy and an attribute written over the API refuse the user's next signed-in request")
    void policyAsDataReachesTheTokenHolder() {
        AccessPoliciesApi adminApi = accessApi(token(admin, rootPath, "ACCESS_POLICY_MANAGE"));
        adminApi.createAccessPolicy(new CreateAccessPolicyRequest()
                .code("west-registers-nothing")
                .action("PARTNER_CREATE")
                .effect(CreateAccessPolicyRequest.EffectEnum.DENY)
                .description("The western region is onboarded centrally")
                .condition(Map.of("attr", "subject.region", "op", "eq", "value", "WEST")), null);
        adminApi.setUserAccessAttributes(clerk.id(), new SetUserAccessAttributesRequest()
                .region("WEST")
                .freightModes(List.of(SetUserAccessAttributesRequest.FreightModesEnum.FTL))
                .approvalLimit(new BigDecimal("25000.00")));

        UserAccessAttributes stored = adminApi.getUserAccessAttributes(clerk.id());
        assertThat(stored.getRegion()).isEqualTo("WEST");
        assertThat(stored.getApprovalLimit()).isEqualByComparingTo("25000");
        assertThat(stored.getPartnerId()).isNull();

        // A real sign-in: the attributes set over the API are in the token the
        // server itself issues.
        String issued = new ApiClient().setBasePath("http://localhost:" + port)
                .buildClient(AuthenticationApi.class)
                .login(new LoginRequest().tenantCode(tenantCode).email("clerk@acme.test")
                        .password(PASSWORD))
                .getAccessToken();
        Jwt claims = jwtDecoder.decode(issued);
        assertThat(claims.getClaimAsString(SubjectAttributes.CLAIM_REGION)).isEqualTo("WEST");
        assertThat(claims.getClaimAsStringList(SubjectAttributes.CLAIM_MODES)).containsExactly("FTL");
        assertThat(claims.getClaimAsString(SubjectAttributes.CLAIM_APPROVAL_LIMIT))
                .isEqualTo("25000.00");
        assertThat(claims.getClaims()).doesNotContainKey(SubjectAttributes.CLAIM_PARTNER);

        // The clerk has no role, so the permission is minted directly; the
        // region is what the tenant's new policy reads.
        String western = tokens.issueAccessToken(clerk, rootPath + "north/",
                List.of("PARTNER_CREATE"),
                new SubjectAttributes("WEST", List.of("FTL"), null, new BigDecimal("25000"))).value();
        assertStatus(() -> partnersApi(western).registerPartner(partner(northUnit, "N2"), null),
                403, null);

        String eastern = tokens.issueAccessToken(clerk, rootPath + "north/",
                List.of("PARTNER_CREATE"), new SubjectAttributes("EAST", null, null, null)).value();
        assertThat(partnersApi(eastern).registerPartner(partner(northUnit, "N3"), null).getId())
                .isNotNull();
    }

    @Test
    @DisplayName("a vendor-portal user reads their own trip, and another vendor's is a 404")
    void portalUserCannotSeeAnotherVendorsTrip() {
        UUID[] built = asTenantStaff(() -> {
            UUID customer = fleet.registerPartner(rootUnit, unique("C"), "Customer",
                    BusinessPartner.PartnerType.CUSTOMER, unique("TAX-C"));
            UUID consignee = fleet.registerPartner(rootUnit, unique("K"), "Consignee",
                    BusinessPartner.PartnerType.CUSTOMER, unique("TAX-K"));
            UUID ours = activeVendor("V1");
            UUID theirs = activeVendor("V2");
            UUID origin = depot("O", 19.1, 72.9);
            UUID destination = depot("D", 19.3, 73.1);
            String registration = "MH-12-PS-" + (1000 + (int) (Math.random() * 9000));
            UUID vehicle = fleet.registerVehicle(rootUnit, null, registration,
                    "TRUCK", "RIGID", "2-AXLE", new BigDecimal("26000"), new BigDecimal("6000"),
                    new BigDecimal("60"), false, false);
            UUID ourLoad = spine.awardedLoad(rootUnit, unique("L1"), customer, origin, consignee,
                    destination, vehicle, ours, new BigDecimal("5000"), false);
            UUID theirLoad = spine.awardedLoad(rootUnit, unique("L2"), customer, origin, consignee,
                    destination, vehicle, theirs, new BigDecimal("4000"), false);
            return new UUID[] {ours, trips.raiseTrip(unique("T1"), ourLoad, Instant.now()),
                    trips.raiseTrip(unique("T2"), theirLoad, Instant.now())};
        });
        UUID vendor = built[0];
        UUID ourTrip = built[1];
        UUID theirTrip = built[2];

        AppUser portalUser = user(tenantId, rootUnit, rootPath, "dispatch@vendor.test");
        String portal = tokens.issueAccessToken(portalUser, rootPath, List.of("INVOICE_READ"),
                new SubjectAttributes(null, null, vendor, null)).value();
        TripsApi api = client(portal).buildClient(TripsApi.class);

        assertThat(api.getTrip(ourTrip).getId()).isEqualTo(ourTrip);
        assertStatus(() -> api.getTrip(theirTrip), 404, null);
        assertThat(api.listTrips(null, null, null).getItems()).extracting(Trip::getId)
                .containsExactly(ourTrip);

        // The tenant's own staff, carrying no partner, still see both.
        TripsApi staff = client(token(admin, rootPath, "INVOICE_READ")).buildClient(TripsApi.class);
        assertThat(staff.getTrip(theirTrip).getId()).isEqualTo(theirTrip);
    }

    // --------------------------------------------------------------- helpers

    private UUID activeVendor(String code) {
        UUID id = fleet.registerPartner(rootUnit, unique(code), code + " Transport",
                BusinessPartner.PartnerType.VENDOR, unique("TAX-" + code));
        fleet.transitionPartner(id, BusinessPartner.PartnerStatus.PENDING_VERIFICATION);
        fleet.transitionPartner(id, BusinessPartner.PartnerStatus.ACTIVE);
        return id;
    }

    private UUID depot(String code, double lat, double lon) {
        return terminals.register(new RegisterTerminalCommand.PointRadius(rootUnit, unique(code),
                code, Terminal.FunctionalCategory.CUSTOMER_SITE, new LatLon(lat, lon), 500, 2, null,
                null, 45, List.of()));
    }

    private static String unique(String code) {
        return code + "-" + UUID.randomUUID().toString().substring(0, 6);
    }

    /** Builds through the real command services, as head-office staff with every permission it needs. */
    private <T> T asTenantStaff(Supplier<T> action) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                "api-test-setup", null, List.of(
                        new SimpleGrantedAuthority("PARTNER_CREATE"),
                        new SimpleGrantedAuthority("PARTNER_UPDATE"),
                        new SimpleGrantedAuthority("VEHICLE_CREATE"),
                        new SimpleGrantedAuthority("TERMINAL_CREATE"),
                        new SimpleGrantedAuthority("ORDER_CREATE"),
                        new SimpleGrantedAuthority("ORDER_UPDATE"),
                        new SimpleGrantedAuthority("LOAD_CREATE"),
                        new SimpleGrantedAuthority("LOAD_ALLOCATE"),
                        new SimpleGrantedAuthority("TRIP_EXECUTE"))));
        try {
            return TenantContext.callWith(tenantId, rootPath, action);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static RegisterPartnerRequest partner(UUID orgUnitId, String code) {
        String unique = code + "-" + UUID.randomUUID().toString().substring(0, 6);
        return new RegisterPartnerRequest().orgUnitId(orgUnitId).code(unique)
                .legalName(code + " Ltd").partnerType(RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER)
                .taxId("TAX-" + unique);
    }

    private static void assertStatus(Runnable call, int status, String fragment) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(FeignException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            if (fragment != null) {
                assertThat(e.contentUTF8()).contains(fragment);
            }
        });
    }

    private String token(AppUser who, String orgPath, String... permissions) {
        return tokens.issueAccessToken(who, orgPath, List.of(permissions)).value();
    }

    private AccessPoliciesApi accessApi(String token) {
        return client(token).buildClient(AccessPoliciesApi.class);
    }

    private PartnersApi partnersApi(String token) {
        return client(token).buildClient(PartnersApi.class);
    }

    private ApiClient client(String token) {
        ApiClient apiClient = new ApiClient().setBasePath("http://localhost:" + port);
        apiClient.getFeignBuilder().requestInterceptor(template ->
                template.header("Authorization", "Bearer " + token));
        return apiClient;
    }

    private AppUser user(UUID tenantId, UUID orgUnitId, String orgPath, String email) {
        UUID id = fixtures.createUser(tenantId, orgUnitId, orgPath, email, PASSWORD);
        return new AppUser(id, tenantId, orgUnitId, email, "not-used", email,
                AppUser.UserStatus.ACTIVE, null, 0, null, null, null, Instant.now(), Instant.now());
    }
}
