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

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import com.lms.acceptance.TestFixtures;
import com.lms.client.ApiClient;
import com.lms.client.api.SettingsApi;
import com.lms.client.api.UsersApi;
import com.lms.client.model.EffectiveSetting;
import com.lms.client.model.Me;
import com.lms.client.model.SettingScopeType;
import com.lms.identity.command.domain.AppUser;
import com.lms.identity.security.TokenService;
import com.lms.masterdata.command.FleetCommandService;
import com.lms.masterdata.command.domain.BusinessPartner;
import com.lms.order.command.OrderCloseSettings;
import com.lms.settings.api.ScopeType;
import com.lms.settings.api.SettingCategory;
import com.lms.settings.api.SettingContributor;
import com.lms.settings.api.SettingDefinition;
import com.lms.settings.api.SettingKey;
import com.lms.shared.tenant.TenantContext;
import com.lms.web.ApiTestKit.Outcome;
import com.lms.web.ApiTestKit.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
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
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Setting profiles over HTTP: definitions, CRUD with If-Match, one profile per
 * scope, refusals of unknown keys, misplaced keys and bad values, tenant
 * isolation by id, a person's own preferences, the fold with its provenance,
 * the key-level history, the order-close policy following a customer's
 * profile, and one statement per resolution however many profiles apply.
 *
 * <p>The container preloads {@code pg_stat_statements} so the last can be
 * counted by the database itself.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SettingsApiTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.7-alpine")
            .withDatabaseName("lms").withUsername("lms").withPassword("lms")
            .withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements",
                    "-c", "pg_stat_statements.track=all");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final String READ = "SETTINGS_READ";
    private static final String MANAGE = "SETTINGS_MANAGE";
    private static final String PROFILES = "/api/v1/settings/profiles";
    private static final String TOLERANCE = "order.close.tolerancePercent";
    private static final String PLATFORM = "PLATFORM_SETTINGS_MANAGE";
    private static final String RATE = "test.compliance.rate";
    private static final String LOCKED = "test.compliance.locked";

    /**
     * Two compliance-shaped keys for this test only: the registry has no
     * compliance key of its own yet, and the platform and dating rules need one.
     */
    @TestConfiguration
    static class ComplianceKeys {

        @Bean
        SettingContributor testComplianceKeys() {
            return () -> List.of(
                    SettingDefinition.of(SettingKey.decimal(RATE), "Test rate", "A dated rate the platform sets")
                            .defaultValue(BigDecimal.ZERO).range(0, 100).scale(2)
                            .category(SettingCategory.COMPLIANCE).effectiveDated()
                            .alsoAt(ScopeType.PLATFORM, ScopeType.ORG_UNIT).build(),
                    SettingDefinition.of(SettingKey.integer(LOCKED), "Test locked", "Only the platform sets it")
                            .defaultValue(0).category(SettingCategory.COMPLIANCE).effectiveDated().platformOnly()
                            .build());
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private TokenService tokens;
    @Autowired
    private FleetCommandService fleet;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private OrderCloseSettings closeSettings;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private ApiTestKit kit;
    private Tenant acme;
    private UUID north;
    private UUID northBranch;
    private String northBranchPath;
    private UUID customer;

    @BeforeEach
    void aTenantWithRegionBranchAndCustomer() {
        kit = new ApiTestKit(fixtures, tokens, port);
        acme = kit.newTenant("acme");
        north = fixtures.createOrgUnit(acme.tenantId(), acme.orgPath() + "north/", "North", "REGION",
                acme.orgUnitId());
        northBranchPath = acme.orgPath() + "north/delhi/";
        northBranch = fixtures.createOrgUnit(acme.tenantId(), northBranchPath, "Delhi", "BRANCH", north);
        customer = customer(acme, "CUST");
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("every signed-in caller reads the definitions, typed, bounded and with where each may be set")
    void definitions() {
        List<com.lms.client.model.SettingDefinition> all = settings(acme).listSettingDefinitions().getItems();
        com.lms.client.model.SettingDefinition tolerance = all.stream().filter(d -> d.getKey().equals(TOLERANCE)).findFirst()
                .orElseThrow();
        assertThat(tolerance.getScopes()).containsExactly(SettingScopeType.ORG_DEFAULT, SettingScopeType.ORG_UNIT,
                SettingScopeType.CUSTOMER);
        assertThat(tolerance.getMax()).isEqualByComparingTo("100");
        assertThat(tolerance.getUserPreference()).isFalse();
        com.lms.client.model.SettingDefinition theme = all.stream().filter(d -> d.getKey().equals("ui.theme")).findFirst().orElseThrow();
        assertThat(theme.getUserPreference()).isTrue();
        assertThat(theme.getAllowedValues()).containsExactly("light", "dark", "system");
        assertThat(all).extracting(com.lms.client.model.SettingDefinition::getKey)
                .contains("controltower.detector.etaSlipTolerance", "notify.orderClosed.email", "ui.defaultPageSize")
                .isSorted();
    }

    @Test
    @DisplayName("a profile is created, read with its ETag, patched under If-Match and deleted; one per scope")
    void crud() {
        Outcome created = kit.post(acme, PROFILES, """
                {"scopeType":"ORG_UNIT","scopeId":"%s","name":"North rules","values":{"%s":2.5}}
                """.formatted(north, TOLERANCE), MANAGE);
        assertThat(created.status()).isEqualTo(201);
        UUID id = UUID.fromString(kit.parse(created).get("id").asString());

        Outcome read = kit.get(acme, PROFILES + "/" + id, READ);
        assertThat(read.status()).isEqualTo(200);
        JsonNode body = kit.parse(read);
        assertThat(body.get("scopeLabel").asString()).isEqualTo("North (" + acme.orgPath() + "north/)");
        assertThat(body.get("values").get(TOLERANCE).decimalValue()).isEqualByComparingTo("2.5");
        assertThat(read.body()).contains("\"version\":1");
        String etag = etag(acme, id);

        assertThat(kit.post(acme, PROFILES, """
                {"scopeType":"ORG_UNIT","scopeId":"%s","name":"Again"}
                """.formatted(north), MANAGE).code()).isEqualTo("setting-profile-scope-taken");

        assertThat(patch(acme, PROFILES + "/" + id, null, "{\"values\":{\"%s\":3}}".formatted(TOLERANCE), MANAGE)
                .status()).isEqualTo(400);
        Outcome changed = patch(acme, PROFILES + "/" + id, etag,
                "{\"name\":\"North terms\",\"values\":{\"%s\":3}}".formatted(TOLERANCE), MANAGE);
        assertThat(changed.status()).isEqualTo(200);
        assertThat(kit.parse(changed).get("name").asString()).isEqualTo("North terms");
        Outcome stale = patch(acme, PROFILES + "/" + id, etag, "{\"values\":{\"%s\":4}}".formatted(TOLERANCE), MANAGE);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("concurrent-modification");
        assertThat(kit.parse(kit.get(acme, PROFILES + "/" + id, READ)).get("values").get(TOLERANCE).asInt())
                .isEqualTo(3);

        assertThat(delete(acme, PROFILES + "/" + id, etag, MANAGE).code()).isEqualTo("concurrent-modification");
        assertThat(delete(acme, PROFILES + "/" + id, etag(acme, id), MANAGE).status()).isEqualTo(204);
        assertThat(kit.get(acme, PROFILES + "/" + id, READ).status()).isEqualTo(404);

        UUID orgDefault = createOrgDefault(acme, "{\"%s\":1}".formatted(TOLERANCE));
        assertThat(delete(acme, PROFILES + "/" + orgDefault, etag(acme, orgDefault), MANAGE).code())
                .isEqualTo("setting-profile-org-default");
        assertThat(kit.post(acme, PROFILES, "{\"scopeType\":\"ORG_DEFAULT\"}", MANAGE).code())
                .isEqualTo("setting-profile-scope-taken");
    }

    @Test
    @DisplayName("reading needs SETTINGS_READ, writing SETTINGS_MANAGE")
    void permissions() {
        assertThat(kit.get(acme, PROFILES, "ORDER_READ").status()).isEqualTo(403);
        assertThat(kit.get(acme, "/api/v1/settings/effective", "ORDER_READ").status()).isEqualTo(403);
        assertThat(kit.post(acme, PROFILES, "{\"scopeType\":\"ORG_DEFAULT\"}", READ).status()).isEqualTo(403);
        UUID id = createOrgDefault(acme, "{}");
        assertThat(patch(acme, PROFILES + "/" + id, etag(acme, id), "{\"values\":{}}", READ).status())
                .isEqualTo(403);
        assertThat(kit.get(acme, PROFILES, MANAGE).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("unknown keys, keys outside their scopes, bad values and bad scopes are refused, each by its code")
    void refusals() {
        Outcome unknown = kit.post(acme, PROFILES, """
                {"scopeType":"ORG_DEFAULT","values":{"order.close.toleranse":1,"%s":2}}
                """.formatted(TOLERANCE), MANAGE);
        assertThat(unknown.status()).isEqualTo(409);
        assertThat(unknown.code()).isEqualTo("setting-unknown");
        assertThat(kit.parse(unknown).get("errors").has("order.close.toleranse")).isTrue();

        assertThat(kit.post(acme, PROFILES, """
                {"scopeType":"CUSTOMER","scopeId":"%s","name":"C","values":{"controltower.detector.etaSlipTolerance":"PT2H"}}
                """.formatted(customer), MANAGE).code()).isEqualTo("setting-scope-not-allowed");
        Outcome invalid = kit.post(acme, PROFILES, """
                {"scopeType":"CUSTOMER","scopeId":"%s","name":"C","values":{"%s":101,"order.close.autoFulfil":"yes"}}
                """.formatted(customer, TOLERANCE), MANAGE);
        assertThat(invalid.code()).isEqualTo("setting-invalid-value");
        assertThat(kit.parse(invalid).get("errors").has("order.close.autoFulfil")).isTrue();

        assertThat(kit.post(acme, PROFILES, "{\"scopeType\":\"USER\",\"scopeId\":\"%s\",\"name\":\"Theirs\"}"
                .formatted(UUID.randomUUID()), MANAGE).code()).isEqualTo("setting-scope-invalid");
        assertThat(kit.post(acme, PROFILES, "{\"scopeType\":\"CUSTOMER\",\"name\":\"No id\"}", MANAGE).code())
                .isEqualTo("setting-scope-invalid");
        assertThat(kit.post(acme, PROFILES, "{\"scopeType\":\"CUSTOMER\",\"scopeId\":\"%s\"}"
                .formatted(customer), MANAGE).code()).isEqualTo("setting-profile-name-required");
        assertThat(kit.post(acme, PROFILES, "{\"scopeType\":\"VEHICLE\",\"scopeId\":\"%s\",\"name\":\"V\"}"
                .formatted(UUID.randomUUID()), MANAGE).status()).isEqualTo(404);
        assertThat(kit.post(acme, PROFILES, "{\"scopeType\":\"PLANET\"}", MANAGE).status()).isEqualTo(400);
        assertThat(jdbc.sql("SELECT count(*) FROM setting_profile WHERE tenant_id = :t")
                .param("t", acme.tenantId()).query(Integer.class).single()).isZero();
    }

    @Test
    @DisplayName("the units a profile can be scoped to are the caller's own and those beneath, searchable")
    void orgUnits() {
        JsonNode all = kit.parse(kit.get(acme, "/api/v1/settings/org-units", READ)).get("items");
        assertThat(stream(all).map(u -> u.get("path").asString()))
                .containsExactly(acme.orgPath(), acme.orgPath() + "north/", northBranchPath);
        Tenant inNorth = kit.tenantOf(acme.tenantId(), north, acme.orgPath() + "north/");
        assertThat(kit.parse(kit.get(inNorth, "/api/v1/settings/org-units?q=DEL", READ)).get("items").size())
                .isEqualTo(1);
        assertThat(kit.parse(kit.get(inNorth, "/api/v1/settings/org-units", READ)).get("items").size())
                .isEqualTo(2);
        assertThat(kit.get(acme, "/api/v1/settings/org-units", "ORDER_READ").status()).isEqualTo(403);
    }

    @Test
    @DisplayName("tenant B cannot read, change, delete or name tenant A's profiles or scopes, even by id")
    void tenantIsolation() {
        UUID aProfile = kit.parse(kit.post(acme, PROFILES, """
                {"scopeType":"CUSTOMER","scopeId":"%s","name":"Acme customer","values":{"%s":4}}
                """.formatted(customer, TOLERANCE), MANAGE)).get("id").asString().transform(UUID::fromString);
        Tenant globex = kit.newTenant("globex");

        assertThat(kit.get(globex, PROFILES + "/" + aProfile, READ).status()).isEqualTo(404);
        assertThat(kit.get(globex, PROFILES + "/" + aProfile + "/history", READ).status()).isEqualTo(404);
        assertThat(patch(globex, PROFILES + "/" + aProfile, "\"0\"", "{\"values\":{\"%s\":9}}".formatted(TOLERANCE),
                MANAGE).status()).isEqualTo(404);
        assertThat(delete(globex, PROFILES + "/" + aProfile, "\"0\"", MANAGE).status()).isEqualTo(404);
        assertThat(kit.parse(kit.get(globex, PROFILES, READ)).get("items").size()).isZero();
        // Naming A's customer or unit as B's scope: the customer reads as absent, the unit is refused.
        assertThat(kit.post(globex, PROFILES, "{\"scopeType\":\"CUSTOMER\",\"scopeId\":\"%s\",\"name\":\"X\"}"
                .formatted(customer), MANAGE).status()).isEqualTo(404);
        assertThat(kit.post(globex, PROFILES, "{\"scopeType\":\"ORG_UNIT\",\"scopeId\":\"%s\",\"name\":\"X\"}"
                .formatted(north), MANAGE).status()).isEqualTo(403);
        // A's customer profile does not leak into B's resolution for the same id.
        JsonNode effective = effective(globex, "customerId=" + customer);
        assertThat(entry(effective, TOLERANCE).get("source").get("kind").asString()).isEqualTo("SYSTEM_DEFAULT");
        assertThat(kit.parse(kit.get(acme, PROFILES + "/" + aProfile, READ)).get("values").get(TOLERANCE).asInt())
                .isEqualTo(4);
    }

    @Test
    @DisplayName("the effective value folds org default < region < branch < customer, and says where it came from")
    void effectiveWithProvenance() {
        UUID orgDefault = createOrgDefault(acme, "{\"%s\":1}".formatted(TOLERANCE));
        UUID region = profile(acme, "ORG_UNIT", north, "North", "{\"%s\":2}".formatted(TOLERANCE));
        UUID branch = profile(acme, "ORG_UNIT", northBranch, "Delhi", "{\"%s\":3}".formatted(TOLERANCE));
        UUID forCustomer = profile(acme, "CUSTOMER", customer, "Customer", "{\"%s\":4}".formatted(TOLERANCE));

        assertSource(effective(acme, ""), TOLERANCE, "1", "ORG_DEFAULT", orgDefault);
        assertSource(effective(acme, "orgUnitId=" + north), TOLERANCE, "2", "ORG_UNIT", region);
        assertSource(effective(acme, "orgUnitId=" + northBranch), TOLERANCE, "3", "ORG_UNIT", branch);
        assertSource(effective(acme, "orgUnitId=" + acme.orgUnitId()), TOLERANCE, "1", "ORG_DEFAULT", orgDefault);
        assertSource(effective(acme, "orgUnitId=" + northBranch + "&customerId=" + customer), TOLERANCE, "4",
                "CUSTOMER", forCustomer);
        JsonNode autoFulfil = entry(effective(acme, "orgUnitId=" + northBranch), "order.close.autoFulfil");
        assertThat(autoFulfil.get("value").asBoolean()).isTrue();
        assertThat(autoFulfil.get("source").get("kind").asString()).isEqualTo("SYSTEM_DEFAULT");

        // Removing the branch's value lets the region answer again.
        assertThat(patch(acme, PROFILES + "/" + branch, etag(acme, branch),
                "{\"values\":{\"%s\":null}}".formatted(TOLERANCE), MANAGE).status()).isEqualTo(200);
        assertSource(effective(acme, "orgUnitId=" + northBranch), TOLERANCE, "2", "ORG_UNIT", region);

        // Through the generated client too: the contract carries the provenance.
        List<EffectiveSetting> items = settings(acme, READ).getEffectiveSettings(null, customer, null, null, null, null).getItems();
        EffectiveSetting viaClient = items.stream().filter(i -> i.getKey().equals(TOLERANCE)).findFirst()
                .orElseThrow();
        assertThat(viaClient.getSource().getProfileId()).isEqualTo(forCustomer);
    }

    @Test
    @DisplayName("the order close policy an order gets follows its customer's profile over the tenant's")
    void orderCloseFollowsCustomer() {
        createOrgDefault(acme, "{\"%s\":1,\"order.close.autoFulfil\":true}".formatted(TOLERANCE));
        profile(acme, "CUSTOMER", customer, "Generous", "{\"%s\":5}".formatted(TOLERANCE));
        UUID other = customer(acme, "OTHER");

        OrderCloseSettings.Policy forCustomer = asPlatform(acme,
                () -> closeSettings.effectiveFor(northBranch, customer));
        OrderCloseSettings.Policy forOther = asPlatform(acme, () -> closeSettings.effectiveFor(northBranch, other));
        assertThat(forCustomer.tolerancePercent()).isEqualByComparingTo("5");
        assertThat(forOther.tolerancePercent()).isEqualByComparingTo("1");
        // The tenant-wide endpoint still shows the organisation default.
        assertThat(kit.parse(kit.get(acme, "/api/v1/orders/close-settings", "ORDER_READ"))
                .get("tolerancePercent").decimalValue()).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("a person sets only their own preferences, under If-Match, folded over their unit and the tenant")
    void myPreferences() {
        createOrgDefault(acme, "{\"ui.theme\":\"dark\",\"ui.defaultPageSize\":25}");
        profile(acme, "ORG_UNIT", northBranch, "Delhi", "{\"ui.theme\":\"light\"}");
        Tenant ravi = kit.tenantOf(acme.tenantId(), northBranch, northBranchPath);
        Tenant meera = kit.tenantOf(acme.tenantId(), northBranch, northBranchPath);

        Outcome first = kit.get(ravi, "/api/v1/settings/me");
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body()).contains("\"version\":0");
        JsonNode effective = kit.parse(first).get("effective");
        assertThat(entry(effective, "ui.theme").get("value").asString()).isEqualTo("light");
        assertThat(entry(effective, "ui.theme").get("source").get("scopeType").asString()).isEqualTo("ORG_UNIT");
        assertThat(entry(effective, "ui.defaultPageSize").get("value").asInt()).isEqualTo(25);
        assertThat(entry(effective, "ui.timeZone").get("source").get("kind").asString()).isEqualTo("SYSTEM_DEFAULT");
        assertThat(stream(effective).map(e -> e.get("key").asString()))
                .doesNotContain(TOLERANCE).contains("notify.orderClosed.inApp");

        assertThat(patch(ravi, "/api/v1/settings/me", null, "{\"values\":{\"ui.theme\":\"dark\"}}").status())
                .isEqualTo(400);
        Outcome saved = patch(ravi, "/api/v1/settings/me", "\"0\"",
                "{\"values\":{\"ui.theme\":\"dark\",\"ui.timeZone\":\"Asia/Kolkata\",\"notify.orderClosed.email\":true}}");
        assertThat(saved.status()).isEqualTo(200);
        JsonNode mine = kit.parse(saved);
        assertThat(entry(mine.get("effective"), "ui.theme").get("source").get("scopeType").asString())
                .isEqualTo("USER");
        assertThat(mine.get("values").get("ui.timeZone").asString()).isEqualTo("Asia/Kolkata");
        assertThat(patch(ravi, "/api/v1/settings/me", "\"0\"", "{\"values\":{\"ui.theme\":\"light\"}}").code())
                .isEqualTo("concurrent-modification");
        assertThat(patch(ravi, "/api/v1/settings/me", etagOf(saved),
                "{\"values\":{\"%s\":5}}".formatted(TOLERANCE)).code()).isEqualTo("setting-scope-not-allowed");
        assertThat(patch(ravi, "/api/v1/settings/me", etagOf(saved),
                "{\"values\":{\"ui.timeZone\":\"Somewhere/Else\"}}").code()).isEqualTo("setting-invalid-value");

        // Meera, in the same unit, sees none of Ravi's choices.
        JsonNode hers = kit.parse(kit.get(meera, "/api/v1/settings/me"));
        assertThat(hers.get("values").size()).isZero();
        assertThat(entry(hers.get("effective"), "ui.theme").get("value").asString()).isEqualTo("light");

        // Ravi's profile is his: the administrative API neither lists nor reads it.
        UUID ravisProfile = jdbc.sql("SELECT id FROM setting_profile WHERE scope_type = 'USER' AND scope_id = :u")
                .param("u", ravi.user().id()).query(UUID.class).single();
        assertThat(kit.get(acme, PROFILES + "/" + ravisProfile, READ, MANAGE).status()).isEqualTo(404);
        assertThat(patch(acme, PROFILES + "/" + ravisProfile, "\"0\"", "{\"values\":{\"ui.theme\":\"light\"}}",
                MANAGE).status()).isEqualTo(404);
        assertThat(kit.get(acme, PROFILES, READ).body()).doesNotContain(ravisProfile.toString());
    }

    @Test
    @DisplayName("the history lists each change with only the keys that changed, before and after")
    void history() {
        UUID id = createOrgDefault(acme, "{\"%s\":1}".formatted(TOLERANCE));
        patch(acme, PROFILES + "/" + id, etag(acme, id),
                "{\"values\":{\"%s\":2,\"order.close.autoFulfil\":false}}".formatted(TOLERANCE), MANAGE);
        patch(acme, PROFILES + "/" + id, etag(acme, id), "{\"values\":{\"order.close.autoFulfil\":false}}", MANAGE);
        patch(acme, PROFILES + "/" + id, etag(acme, id), "{\"values\":{\"%s\":null}}".formatted(TOLERANCE), MANAGE);

        JsonNode items = kit.parse(kit.get(acme, PROFILES + "/" + id + "/history", READ)).get("items");
        assertThat(items.size()).as("the unchanged patch records nothing").isEqualTo(3);
        JsonNode removed = items.get(0);
        assertThat(removed.get("action").asString()).isEqualTo("SETTING_PROFILE_UPDATED");
        assertThat(removed.get("before").get(TOLERANCE).asInt()).isEqualTo(2);
        assertThat(removed.get("after").get(TOLERANCE).isNull()).isTrue();
        assertThat(removed.get("after").has("order.close.autoFulfil")).isFalse();
        JsonNode both = items.get(1);
        assertThat(both.get("before").get(TOLERANCE).asInt()).isEqualTo(1);
        assertThat(both.get("before").get("order.close.autoFulfil").isNull()).isTrue();
        assertThat(both.get("after").get("order.close.autoFulfil").asBoolean()).isFalse();
        assertThat(items.get(2).get("action").asString()).isEqualTo("SETTING_PROFILE_CREATED");

        // The legacy endpoint writes the same profile, and is on the same history.
        assertThat(kit.put(acme, "/api/v1/orders/close-settings",
                "{\"tolerancePercent\":7,\"autoFulfil\":true}", "ORDER_FORCE_CLOSE").status()).isEqualTo(200);
        JsonNode latest = kit.parse(kit.get(acme, PROFILES + "/" + id + "/history", READ)).get("items").get(0);
        assertThat(latest.get("after").get(TOLERANCE).asInt()).isEqualTo(7);
        assertThat(latest.get("after").get("order.close.autoFulfil").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("GET /me says who the caller is: identity and tenant as recorded, permissions as the token carries")
    void me() {
        UUID userId = fixtures.createUser(acme.tenantId(), northBranch, northBranchPath,
                "meera@" + acme.tenantId() + ".test", "not-used-here");
        Tenant meera = new Tenant(acme.tenantId(), northBranch, northBranchPath, new AppUser(userId,
                acme.tenantId(), northBranch, "meera@" + acme.tenantId() + ".test", "not-used", "Meera",
                AppUser.UserStatus.ACTIVE, null, 0, null, null, null, Instant.now(), Instant.now()));
        Me me = client(meera, UsersApi.class, "ORDER_READ").getMe();
        assertThat(me.getUserId()).isEqualTo(userId);
        assertThat(me.getEmail()).isEqualTo("meera@" + acme.tenantId() + ".test");
        assertThat(me.getOrgUnitName()).isEqualTo("Delhi");
        assertThat(me.getOrgPath()).isEqualTo(northBranchPath);
        assertThat(me.getTenantCode()).startsWith("acme-");
        assertThat(me.getPermissions()).containsExactly("ORDER_READ");
    }

    @Test
    @DisplayName("one statement resolves a context, however many profiles apply to it")
    void oneStatementPerResolution() {
        createOrgDefault(acme, "{\"%s\":1}".formatted(TOLERANCE));
        profile(acme, "ORG_UNIT", north, "North", "{\"%s\":2}".formatted(TOLERANCE));
        long few = statementsFor("orgUnitId=" + north);
        profile(acme, "ORG_UNIT", northBranch, "Delhi", "{\"%s\":3,\"ui.theme\":\"dark\"}".formatted(TOLERANCE));
        profile(acme, "CUSTOMER", customer, "Customer", "{\"%s\":4,\"order.close.autoFulfil\":false}"
                .formatted(TOLERANCE));
        long many = statementsFor("orgUnitId=" + northBranch + "&customerId=" + customer);
        assertThat(few).isEqualTo(1);
        assertThat(many).isEqualTo(1);
    }

    @Test
    @DisplayName("dated versions: asOf picks the one in effect, one in force never changes, a new one is today or later")
    void effectiveDatedVersions() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        UUID id = createOrgDefault(acme, "{}");
        Outcome first = patch(acme, PROFILES + "/" + id, etag(acme, id), """
                {"versions":[{"key":"%1$s","effectiveFrom":"%2$s","value":5},
                             {"key":"%1$s","effectiveFrom":"%3$s","value":6},
                             {"key":"%1$s","effectiveFrom":"%4$s","value":7}]}
                """.formatted(RATE, today, today.plusDays(10), today.plusDays(20)), MANAGE);
        assertThat(first.status()).as(first.body()).isEqualTo(200);
        JsonNode versions = kit.parse(first).get("versions");
        assertThat(stream(versions).map(v -> v.get("status").asString()))
                .containsExactly("IN_FORCE", "SCHEDULED", "SCHEDULED");
        assertThat(kit.parse(first).get("values").get(RATE).asInt()).isEqualTo(5);

        assertThat(entry(effective(acme, ""), RATE).get("value").asInt()).isEqualTo(5);
        assertThat(entry(effective(acme, "asOf=" + today.plusDays(15)), RATE).get("value").asInt()).isEqualTo(6);
        JsonNode later = entry(effective(acme, "asOf=" + today.plusDays(25)), RATE);
        assertThat(later.get("value").asInt()).isEqualTo(7);
        assertThat(later.get("source").get("effectiveFrom").asString()).isEqualTo(today.plusDays(20).toString());
        assertThat(entry(effective(acme, "asOf=" + today.minusDays(1)), RATE).get("source").get("kind").asString())
                .isEqualTo("SYSTEM_DEFAULT");

        // In force: neither changed nor removed. Scheduled: both, freely.
        Outcome inForce = patch(acme, PROFILES + "/" + id, etag(acme, id), """
                {"versions":[{"key":"%s","effectiveFrom":"%s","value":9}]}""".formatted(RATE, today), MANAGE);
        assertThat(inForce.code()).isEqualTo("setting-value-in-force");
        assertThat(kit.parse(inForce).get("errors").has(RATE + "@" + today)).isTrue();
        assertThat(patch(acme, PROFILES + "/" + id, etag(acme, id), """
                {"versions":[{"key":"%s","effectiveFrom":"%s","value":null}]}""".formatted(RATE, today), MANAGE)
                .code()).isEqualTo("setting-value-in-force");
        assertThat(patch(acme, PROFILES + "/" + id, etag(acme, id), """
                {"versions":[{"key":"%s","effectiveFrom":"%s","value":4}]}""".formatted(RATE, today.minusDays(1)),
                MANAGE).code()).isEqualTo("setting-effective-date-past");
        assertThat(patch(acme, PROFILES + "/" + id, etag(acme, id), "{\"values\":{\"%s\":4}}".formatted(RATE),
                MANAGE).code()).isEqualTo("setting-effective-date-required");
        Outcome rescheduled = patch(acme, PROFILES + "/" + id, etag(acme, id), """
                {"versions":[{"key":"%1$s","effectiveFrom":"%2$s","value":8},
                             {"key":"%1$s","effectiveFrom":"%3$s","value":null}]}
                """.formatted(RATE, today.plusDays(10), today.plusDays(20)), MANAGE);
        assertThat(rescheduled.status()).as(rescheduled.body()).isEqualTo(200);
        assertThat(entry(effective(acme, "asOf=" + today.plusDays(25)), RATE).get("value").asInt()).isEqualTo(8);

        // Every version change is on the history, named key@date.
        JsonNode latest = kit.parse(kit.get(acme, PROFILES + "/" + id + "/history", READ)).get("items").get(0);
        assertThat(latest.get("before").get(RATE + "@" + today.plusDays(10)).asInt()).isEqualTo(6);
        assertThat(latest.get("after").get(RATE + "@" + today.plusDays(10)).asInt()).isEqualTo(8);
        assertThat(latest.get("after").get(RATE + "@" + today.plusDays(20)).isNull()).isTrue();

        // A unit profile holding a version in force is kept.
        UUID unit = kit.parse(kit.post(acme, PROFILES, """
                {"scopeType":"ORG_UNIT","scopeId":"%s","name":"North","versions":[{"key":"%s","effectiveFrom":"%s","value":3}]}
                """.formatted(north, RATE, today), MANAGE)).get("id").asString().transform(UUID::fromString);
        assertThat(delete(acme, PROFILES + "/" + unit, etag(acme, unit), MANAGE).code())
                .isEqualTo("setting-value-in-force");
    }

    @Test
    @DisplayName("platform values lie beneath every tenant's, by jurisdiction, and a platform-only key cannot be overridden")
    void platformValues() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Tenant globex = kit.newTenant("globex");
        // Only the platform operator writes platform profiles.
        assertThat(kit.post(acme, PROFILES, "{\"scopeType\":\"PLATFORM\",\"name\":\"Global\"}", MANAGE)
                .status()).isEqualTo(403);
        UUID global = platformProfile(null, null, "Global rules", 18, 7, today);
        UUID india = platformProfile("IN", null, "India rules", 19, null, today);
        UUID maharashtra = platformProfile("IN", "MH", "Maharashtra rules", 20, null, today);
        assertThat(kit.post(acme, PROFILES, "{\"scopeType\":\"PLATFORM\",\"country\":\"IN\",\"name\":\"Again\"}",
                PLATFORM).code()).isEqualTo("setting-profile-scope-taken");
        assertThat(kit.post(acme, PROFILES, "{\"scopeType\":\"PLATFORM\",\"country\":\"I1\",\"name\":\"X\"}",
                PLATFORM).code()).isEqualTo("setting-scope-invalid");

        // Every tenant reads them, by its jurisdiction.
        assertSource(effective(globex, ""), RATE, "18", "PLATFORM", global);
        assertSource(effective(globex, "country=IN"), RATE, "19", "PLATFORM", india);
        assertSource(effective(globex, "country=IN&state=MH"), RATE, "20", "PLATFORM", maharashtra);
        assertSource(effective(globex, "country=IN&state=KA"), RATE, "19", "PLATFORM", india);
        assertSource(effective(globex, "country=FR&state=MH"), RATE, "18", "PLATFORM", global);
        assertSource(effective(globex, "country=IN"), LOCKED, "7", "PLATFORM", global);

        // A tenant's own value beats the platform's; a platform-only key refuses one.
        UUID own = kit.parse(kit.post(globex, PROFILES, """
                {"scopeType":"ORG_DEFAULT","versions":[{"key":"%s","effectiveFrom":"%s","value":21}]}
                """.formatted(RATE, today), MANAGE)).get("id").asString().transform(UUID::fromString);
        assertSource(effective(globex, "country=IN&state=MH"), RATE, "21", "ORG_DEFAULT", own);
        assertSource(effective(acme, "country=IN&state=MH"), RATE, "20", "PLATFORM", maharashtra);
        Outcome locked = patch(globex, PROFILES + "/" + own, etag(globex, own), """
                {"versions":[{"key":"%s","effectiveFrom":"%s","value":1}]}""".formatted(LOCKED, today), MANAGE);
        assertThat(locked.code()).isEqualTo("setting-not-overridable");

        // Tenants neither list nor edit them; the operator does.
        assertThat(kit.get(globex, PROFILES + "/" + global, READ).status()).isEqualTo(404);
        assertThat(kit.get(globex, PROFILES + "?scopeType=PLATFORM", READ).body()).doesNotContain(global.toString());
        assertThat(patch(globex, PROFILES + "/" + global, "\"1\"", "{\"name\":\"Mine now\"}", MANAGE).status())
                .isEqualTo(403);
        assertThat(kit.parse(kit.get(acme, PROFILES + "?scopeType=PLATFORM", PLATFORM)).get("items").size())
                .isGreaterThanOrEqualTo(3);
        Outcome changed = patch(acme, PROFILES + "/" + global, etagAs(acme, global, PLATFORM), """
                {"versions":[{"key":"%s","effectiveFrom":"%s","value":17}]}""".formatted(RATE, today.plusDays(30)),
                PLATFORM);
        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
        assertSource(effective(acme, "asOf=" + today.plusDays(31)), RATE, "17", "PLATFORM", global);

        // Underneath, row-level security refuses a platform row to a tenant's transaction.
        assertThatThrownBy(() -> asPlatform(globex, () -> jdbc.sql("""
                INSERT INTO setting_profile (id, tenant_id, scope_type, name, country)
                VALUES (gen_random_uuid(), NULL, 'PLATFORM', 'Forged', 'DE')
                """).update())).rootCause().hasMessageContaining("row-level security");
    }

    // ---------------------------------------------------------------- helpers

    private UUID platformProfile(String country, String state, String name, int rate, Integer locked, LocalDate from) {
        StringBuilder versions = new StringBuilder("[{\"key\":\"" + RATE + "\",\"effectiveFrom\":\"" + from
                + "\",\"value\":" + rate + "}");
        if (locked != null) {
            versions.append(",{\"key\":\"" + LOCKED + "\",\"effectiveFrom\":\"" + from + "\",\"value\":" + locked + "}");
        }
        versions.append("]");
        String body = "{\"scopeType\":\"PLATFORM\",\"name\":\"" + name + "\""
                + (country == null ? "" : ",\"country\":\"" + country + "\"")
                + (state == null ? "" : ",\"state\":\"" + state + "\"")
                + ",\"versions\":" + versions + "}";
        Outcome created = kit.post(acme, PROFILES, body, PLATFORM);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return UUID.fromString(kit.parse(created).get("id").asString());
    }

    private String etagAs(Tenant tenant, UUID id, String permission) {
        return etagOf(kit.get(tenant, PROFILES + "/" + id, permission));
    }

    private long statementsFor(String query) {
        jdbc.sql("CREATE EXTENSION IF NOT EXISTS pg_stat_statements").update();
        jdbc.sql("SELECT pg_stat_statements_reset()").query().singleRow();
        assertThat(kit.get(acme, "/api/v1/settings/effective?" + query, READ).status()).isEqualTo(200);
        return jdbc.sql("""
                        SELECT coalesce(sum(calls), 0) FROM pg_stat_statements
                         WHERE query ILIKE '%setting_profile%' AND query NOT ILIKE '%pg_stat_statements%'
                        """).query(Long.class).single();
    }

    private void assertSource(JsonNode effective, String key, String value, String scopeType, UUID profileId) {
        JsonNode e = entry(effective, key);
        assertThat(e.get("value").decimalValue()).isEqualByComparingTo(value);
        assertThat(e.get("source").get("kind").asString()).isEqualTo("PROFILE");
        assertThat(e.get("source").get("scopeType").asString()).isEqualTo(scopeType);
        assertThat(e.get("source").get("profileId").asString()).isEqualTo(profileId.toString());
    }

    private JsonNode effective(Tenant tenant, String query) {
        Outcome outcome = kit.get(tenant, "/api/v1/settings/effective?" + query, READ);
        assertThat(outcome.status()).isEqualTo(200);
        return kit.parse(outcome).get("items");
    }

    private static JsonNode entry(JsonNode items, String key) {
        return stream(items).filter(e -> e.get("key").asString().equals(key)).findFirst().orElseThrow();
    }

    private UUID createOrgDefault(Tenant tenant, String values) {
        Outcome created = kit.post(tenant, PROFILES, "{\"scopeType\":\"ORG_DEFAULT\",\"values\":" + values + "}",
                MANAGE);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return UUID.fromString(kit.parse(created).get("id").asString());
    }

    private UUID profile(Tenant tenant, String scopeType, UUID scopeId, String name, String values) {
        Outcome created = kit.post(tenant, PROFILES, """
                {"scopeType":"%s","scopeId":"%s","name":"%s","values":%s}
                """.formatted(scopeType, scopeId, name, values), MANAGE);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return UUID.fromString(kit.parse(created).get("id").asString());
    }

    private String etag(Tenant tenant, UUID id) {
        return etagOf(kit.get(tenant, PROFILES + "/" + id, READ));
    }

    private String etagOf(Outcome outcome) {
        return "\"" + kit.parse(outcome).get("version").asLong() + "\"";
    }

    private Outcome patch(Tenant tenant, String path, String ifMatch, String body, String... permissions) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + kit.token(tenant, permissions))
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body));
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return kit.send(request.build());
    }

    private Outcome delete(Tenant tenant, String path, String ifMatch, String... permissions) {
        return kit.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + kit.token(tenant, permissions))
                .header("If-Match", ifMatch)
                .DELETE().build());
    }

    private SettingsApi settings(Tenant tenant, String... permissions) {
        return client(tenant, SettingsApi.class, permissions);
    }

    private <T extends ApiClient.Api> T client(Tenant tenant, Class<T> api, String... permissions) {
        String token = kit.token(tenant, permissions);
        ApiClient apiClient = new ApiClient().setBasePath("http://localhost:" + port);
        apiClient.getFeignBuilder().requestInterceptor(template ->
                template.header("Authorization", "Bearer " + token));
        return apiClient.buildClient(api);
    }

    private UUID customer(Tenant tenant, String code) {
        return asSetup(tenant, () -> fleet.registerPartner(tenant.orgUnitId(), code, code,
                BusinessPartner.PartnerType.CUSTOMER, "TAX-" + code + "-" + tenant.tenantId().toString().substring(0, 8)));
    }

    private <T> T asSetup(Tenant tenant, Supplier<T> action) {
        Set<SimpleGrantedAuthority> authorities = Set.of("PARTNER_CREATE").stream()
                .map(SimpleGrantedAuthority::new).collect(Collectors.toSet());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("setup", "n/a", authorities));
        try {
            return TenantContext.callWith(tenant.tenantId(), tenant.orgPath(), action);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private <T> T asPlatform(Tenant tenant, Supplier<T> action) {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        return TenantContext.callWith(tenant.tenantId(), tenant.orgPath(), () -> transactions.execute(s -> action.get()));
    }

    private static Stream<JsonNode> stream(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false);
    }
}
