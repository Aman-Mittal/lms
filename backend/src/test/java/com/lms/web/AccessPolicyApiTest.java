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

import com.lms.acceptance.TestFixtures;
import com.lms.client.ApiClient;
import com.lms.client.api.AccessPoliciesApi;
import com.lms.client.api.AuthenticationApi;
import com.lms.client.api.PartnersApi;
import com.lms.client.model.AccessPolicy;
import com.lms.client.model.AccessPolicySlice;
import com.lms.client.model.CreateAccessPolicyRequest;
import com.lms.client.model.LoginRequest;
import com.lms.client.model.RegisterPartnerRequest;
import com.lms.client.model.ReviseAccessPolicyRequest;
import com.lms.client.model.SetUserAccessAttributesRequest;
import com.lms.client.model.UserAccessAttributes;
import com.lms.identity.access.SubjectAttributes;
import com.lms.identity.command.domain.AppUser;
import com.lms.identity.security.TokenService;
import feign.FeignException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
        UUID tenantId = fixtures.createTenant(tenantCode, "Acme Logistics");
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
                "exception-close-holding-limit", "invoice-approval-limit",
                "partner-write-org-subtree");
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

    // --------------------------------------------------------------- helpers

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
