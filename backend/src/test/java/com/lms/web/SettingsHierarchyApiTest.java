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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import com.lms.acceptance.TestFixtures;
import com.lms.identity.security.TokenService;
import com.lms.masterdata.command.FleetCommandService;
import com.lms.masterdata.command.domain.BusinessPartner;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
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
 * The settings hierarchy over HTTP: organisational units with levels
 * (create, edit under If-Match, the rank rule, codes, audit, tenant
 * isolation, trees older than the rule), the country, zone and branch fold
 * with its level in the provenance, a unit's jurisdiction filling in for
 * platform values, and combination profiles (matching, precedence, the
 * ambiguity refusal, validation, isolation) -- still one statement per
 * resolution.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SettingsHierarchyApiTest {

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
    private static final String UNITS = "ORG_UNIT_MANAGE";
    private static final String PLATFORM = "PLATFORM_SETTINGS_MANAGE";
    private static final String PROFILES = "/api/v1/settings/profiles";
    private static final String ORG_UNITS = "/api/v1/org-units";
    private static final String TOLERANCE = "order.close.tolerancePercent";
    private static final String RATE = "test.hierarchy.rate";

    /** A dated platform key also kept per unit, for the jurisdiction fill. */
    @TestConfiguration
    static class HierarchyKeys {

        @Bean
        SettingContributor testHierarchyKeys() {
            return () -> List.of(SettingDefinition.of(SettingKey.decimal(RATE), "Test hierarchy rate",
                            "A dated rate the platform sets per jurisdiction")
                    .defaultValue(BigDecimal.ZERO).range(0, 100).scale(2).category(SettingCategory.COMPLIANCE)
                    .effectiveDated().alsoAt(ScopeType.PLATFORM, ScopeType.ORG_UNIT).build());
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

    private ApiTestKit kit;
    private Tenant tenant;
    private UUID country;
    private UUID zone;
    private UUID branch;
    private UUID customer;

    /** HQ > India (IN) > North (state HR) > Gurgaon, built through the API. */
    @BeforeEach
    void aTreeOfCountryZoneAndBranch() {
        kit = new ApiTestKit(fixtures, tokens, port);
        tenant = kit.newTenant("tree");
        country = createUnit(tenant, tenant.orgUnitId(), "in", "India", "COUNTRY", "IN", null);
        zone = createUnit(tenant, country, "north", "North", "ZONE", null, "HR");
        branch = createUnit(tenant, zone, "gurgaon", "Gurgaon", "BRANCH", null, null);
        customer = partner(tenant, "DF", "Delta Foods", BusinessPartner.PartnerType.CUSTOMER);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------ org units

    @Test
    @DisplayName("units are created beneath a parent with a level and codes, listed in path order, edited under If-Match"
            + " and audited")
    void orgUnitCrud() {
        JsonNode items = kit.parse(kit.get(tenant, ORG_UNITS)).get("items");
        assertThat(stream(items).map(u -> u.get("unitType").asString())).containsExactly("HQ", "COUNTRY", "ZONE",
                "BRANCH");
        JsonNode gurgaon = stream(items).filter(u -> u.get("id").asString().equals(branch.toString())).findFirst()
                .orElseThrow();
        assertThat(gurgaon.get("level").asInt()).isEqualTo(4);
        assertThat(gurgaon.get("path").asString()).isEqualTo(tenant.orgPath() + "in/north/gurgaon/");
        assertThat(gurgaon.get("effectiveCountry").asString()).isEqualTo("IN");
        assertThat(gurgaon.get("effectiveState").asString()).isEqualTo("HR");
        assertThat(gurgaon.get("parentId").asString()).isEqualTo(zone.toString());

        Outcome read = kit.get(tenant, ORG_UNITS + "/" + zone);
        assertThat(read.status()).isEqualTo(200);
        String etag = "\"" + kit.parse(read).get("version").asLong() + "\"";
        Outcome renamed = put(tenant, ORG_UNITS + "/" + zone, etag,
                "{\"name\":\"North zone\",\"unitType\":\"ZONE\",\"stateCode\":\"dl\"}", UNITS);
        assertThat(renamed.status()).as(renamed.body()).isEqualTo(200);
        assertThat(kit.parse(renamed).get("name").asString()).isEqualTo("North zone");
        assertThat(kit.parse(renamed).get("stateCode").asString()).isEqualTo("DL");
        assertThat(kit.parse(renamed).get("path").asString()).isEqualTo(tenant.orgPath() + "in/north/");
        assertThat(put(tenant, ORG_UNITS + "/" + zone, etag,
                "{\"name\":\"Again\",\"unitType\":\"ZONE\"}", UNITS).code()).isEqualTo("concurrent-modification");
        assertThat(put(tenant, ORG_UNITS + "/" + zone, null,
                "{\"name\":\"Again\",\"unitType\":\"ZONE\"}", UNITS).status()).isEqualTo(400);
        // REGION ranks as a zone: retyping keeps the tree valid.
        assertThat(put(tenant, ORG_UNITS + "/" + zone, etagOf(zone), "{\"name\":\"North\",\"unitType\":\"REGION\"}",
                UNITS).status()).isEqualTo(200);

        assertThat(jdbc.sql("""
                        SELECT action FROM audit_log WHERE resource_type = 'OrgUnit' AND resource_id = :id
                         ORDER BY occurred_at, action
                        """).param("id", zone).query(String.class).list())
                .containsExactly("ORG_UNIT_CREATED", "ORG_UNIT_UPDATED", "ORG_UNIT_UPDATED");
        // A caller lower in the tree sees only their own subtree.
        Tenant inZone = kit.tenantOf(tenant.tenantId(), zone, tenant.orgPath() + "in/north/");
        assertThat(kit.parse(kit.get(inZone, ORG_UNITS)).get("items").size()).isEqualTo(2);
        assertThat(kit.get(inZone, ORG_UNITS + "/" + country).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("a child must rank below its parent and above its children; codes belong to their levels; slugs are unique")
    void levelsAndCodesAreRefused() {
        assertThat(post(tenant, branch, "x", "X", "ZONE", null, null).code()).isEqualTo("org-unit-level-invalid");
        assertThat(post(tenant, country, "y", "Y", "COUNTRY", "LK", null).code()).isEqualTo("org-unit-level-invalid");
        assertThat(post(tenant, branch, "z", "Z", "BRANCH", null, null).code()).isEqualTo("org-unit-level-invalid");
        assertThat(post(tenant, tenant.orgUnitId(), "lk", "Lanka", "COUNTRY", null, null).code())
                .isEqualTo("org-unit-country-invalid");
        assertThat(post(tenant, tenant.orgUnitId(), "lk", "Lanka", "COUNTRY", "LKA", null).status())
                .as("longer than its schema allows").isEqualTo(400);
        assertThat(post(tenant, zone, "hub", "Hub", "HUB", "IN", null).code()).isEqualTo("org-unit-country-invalid");
        assertThat(post(tenant, tenant.orgUnitId(), "lk", "Lanka", "COUNTRY", "LK", "WP").code())
                .isEqualTo("org-unit-state-invalid");
        assertThat(post(tenant, zone, "gurgaon", "Again", "BRANCH", null, null).code())
                .isEqualTo("org-unit-slug-taken");
        assertThat(post(tenant, zone, "Bad Slug", "Bad", "BRANCH", null, null).code())
                .isEqualTo("org-unit-slug-invalid");
        // Retyping must keep the order with both the parent and the children.
        assertThat(put(tenant, ORG_UNITS + "/" + zone, etagOf(zone), "{\"name\":\"N\",\"unitType\":\"HUB\"}", UNITS)
                .code()).isEqualTo("org-unit-level-invalid");
        assertThat(put(tenant, ORG_UNITS + "/" + zone, etagOf(zone), "{\"name\":\"N\",\"unitType\":\"LOB\"}", UNITS)
                .code()).isEqualTo("org-unit-level-invalid");
        // The right is needed.
        Outcome unauthorised = kit.post(tenant, ORG_UNITS, """
                {"parentId":"%s","slug":"w","name":"W","unitType":"BRANCH"}""".formatted(zone), READ);
        assertThat(unauthorised.status()).isEqualTo(403);
        assertThat(jdbc.sql("SELECT count(*) FROM org_unit WHERE tenant_id = :t").param("t", tenant.tenantId())
                .query(Integer.class).single()).isEqualTo(4);
    }

    @Test
    @DisplayName("a tree written before the rank rule still reads and renames; only a new write is held to it")
    void olderTreesKeepWorking() {
        UUID first = fixtures.createOrgUnit(tenant.tenantId(), tenant.orgPath() + "old/", "Old branch", "BRANCH",
                tenant.orgUnitId());
        UUID nested = fixtures.createOrgUnit(tenant.tenantId(), tenant.orgPath() + "old/older/", "Older branch",
                "BRANCH", first);
        assertThat(kit.get(tenant, ORG_UNITS + "/" + nested).status()).isEqualTo(200);
        assertThat(put(tenant, ORG_UNITS + "/" + nested, etagOf(nested),
                "{\"name\":\"Renamed\",\"unitType\":\"BRANCH\"}", UNITS).status()).isEqualTo(200);
        assertThat(post(tenant, first, "newer", "Newer", "BRANCH", null, null).code())
                .isEqualTo("org-unit-level-invalid");
    }

    @Test
    @DisplayName("another tenant can neither read, add beneath nor edit a tenant's units")
    void orgUnitIsolation() {
        Tenant other = kit.newTenant("other");
        assertThat(kit.get(other, ORG_UNITS + "/" + zone).status()).isEqualTo(404);
        assertThat(stream(kit.parse(kit.get(other, ORG_UNITS)).get("items")).map(u -> u.get("id").asString()))
                .doesNotContain(zone.toString(), branch.toString());
        assertThat(post(other, zone, "mine", "Mine", "BRANCH", null, null).status()).isIn(403, 404);
        assertThat(put(other, ORG_UNITS + "/" + zone, "\"0\"", "{\"name\":\"Mine\",\"unitType\":\"ZONE\"}", UNITS)
                .status()).isIn(403, 404);
        assertThat(kit.parse(kit.get(tenant, ORG_UNITS + "/" + zone)).get("name").asString()).isEqualTo("North");
    }

    // ------------------------------------------------- country, zone, branch

    @Test
    @DisplayName("country < zone < branch fold by depth, and the provenance names each level")
    void levelsInTheFold() {
        profile("ORG_UNIT", country, "India terms", "{\"%s\":1}".formatted(TOLERANCE));
        profile("ORG_UNIT", zone, "North terms", "{\"%s\":2}".formatted(TOLERANCE));
        assertThat(source(effective("orgUnitId=" + branch), TOLERANCE)).isEqualTo("Zone: North");
        profile("ORG_UNIT", branch, "Gurgaon terms", "{\"%s\":3}".formatted(TOLERANCE));
        JsonNode atBranch = entry(effective("orgUnitId=" + branch), TOLERANCE);
        assertThat(atBranch.get("value").decimalValue()).isEqualByComparingTo("3");
        assertThat(atBranch.get("source").get("label").asString()).isEqualTo("Branch: Gurgaon");
        assertThat(source(effective("orgUnitId=" + country), TOLERANCE)).isEqualTo("Country: India");
        assertThat(kit.parse(kit.get(tenant, "/api/v1/settings/org-units", READ)).get("items").get(1).get("level")
                .asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("with no jurisdiction of its own, a context takes its unit's: nearest country, then nearest state")
    void jurisdictionFromTheUnit() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        UUID global = platform(null, null, "Global", 10, today);
        UUID india = platform("IN", null, "India", 11, today);
        UUID haryana = platform("IN", "HR", "Haryana", 12, today);

        assertThat(entry(effective("orgUnitId=" + branch), RATE).get("source").get("profileId").asString())
                .isEqualTo(haryana.toString());
        assertThat(source(effective("orgUnitId=" + branch), RATE)).isEqualTo("Platform: IN-HR");
        assertThat(entry(effective("orgUnitId=" + country), RATE).get("source").get("profileId").asString())
                .isEqualTo(india.toString());
        assertThat(entry(effective(""), RATE).get("source").get("profileId").asString())
                .isEqualTo(global.toString());
        // A jurisdiction the context names is used as given.
        assertThat(entry(effective("orgUnitId=" + branch + "&country=FR"), RATE).get("source").get("profileId")
                .asString()).isEqualTo(global.toString());
        assertThat(entry(effective("orgUnitId=" + branch + "&country=IN"), RATE).get("source").get("profileId")
                .asString()).isEqualTo(india.toString());
        // A state with no country above it applies nothing beyond the global value.
        UUID stray = createUnit(tenant, tenant.orgUnitId(), "stray", "Stray", "ZONE", null, "HR");
        assertThat(entry(effective("orgUnitId=" + stray), RATE).get("source").get("profileId").asString())
                .isEqualTo(global.toString());
    }

    // ---------------------------------------------------------- combinations

    @Test
    @DisplayName("a combination applies when every condition holds, above the customer's own profile, and says so")
    void combinationMatches() {
        profile("CUSTOMER", customer, "Delta terms", "{\"%s\":4}".formatted(TOLERANCE));
        UUID combination = combination("Gurgaon for Delta", """
                {"orgUnitId":"%s","customerId":"%s"}""".formatted(branch, customer),
                "{\"%s\":6}".formatted(TOLERANCE));

        JsonNode hit = entry(effective("orgUnitId=" + branch + "&customerId=" + customer), TOLERANCE);
        assertThat(hit.get("value").decimalValue()).isEqualByComparingTo("6");
        assertThat(hit.get("source").get("scopeType").asString()).isEqualTo("COMBINATION");
        assertThat(hit.get("source").get("profileId").asString()).isEqualTo(combination.toString());
        assertThat(hit.get("source").get("label").asString()).isEqualTo("Branch Gurgaon + Customer Delta Foods");
        // A unit beneath the branch is in its subtree; the zone above it is not.
        UUID hub = createUnit(tenant, branch, "dock", "Dock", "HUB", null, null);
        assertThat(source(effective("orgUnitId=" + hub + "&customerId=" + customer), TOLERANCE))
                .isEqualTo("Branch Gurgaon + Customer Delta Foods");
        assertThat(source(effective("orgUnitId=" + zone + "&customerId=" + customer), TOLERANCE))
                .isEqualTo("Customer: Delta terms");
        assertThat(source(effective("customerId=" + customer), TOLERANCE)).isEqualTo("Customer: Delta terms");

        JsonNode read = kit.parse(kit.get(tenant, PROFILES + "/" + combination, READ));
        assertThat(read.get("scopeType").asString()).isEqualTo("COMBINATION");
        assertThat(read.get("scopeLabel").asString()).isEqualTo("Branch Gurgaon + Customer Delta Foods");
        assertThat(read.get("conditions").get("orgUnitId").asString()).isEqualTo(branch.toString());
        assertThat(read.get("conditions").get("priority").asInt()).isZero();
        assertThat(kit.parse(kit.get(tenant, PROFILES + "?scopeType=COMBINATION", READ)).get("items").size())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("among matching combinations more conditions win, then the deeper unit, then the higher priority")
    void combinationPrecedence() {
        combination("Zone + Delta", "{\"orgUnitId\":\"%s\",\"customerId\":\"%s\"}".formatted(zone, customer),
                "{\"%s\":1}".formatted(TOLERANCE));
        combination("Branch + Delta", "{\"orgUnitId\":\"%s\",\"customerId\":\"%s\"}".formatted(branch, customer),
                "{\"%s\":2}".formatted(TOLERANCE));
        String context = "orgUnitId=" + branch + "&customerId=" + customer;
        assertThat(source(effective(context), TOLERANCE)).isEqualTo("Branch Gurgaon + Customer Delta Foods");

        combination("Zone + Delta + LTL", """
                {"orgUnitId":"%s","customerId":"%s","freightMode":"LTL"}""".formatted(zone, customer),
                "{\"%s\":3}".formatted(TOLERANCE));
        assertThat(source(effective(context + "&freightMode=LTL"), TOLERANCE))
                .isEqualTo("Zone North + Customer Delta Foods + Mode LTL");
        assertThat(source(effective(context + "&freightMode=FTL"), TOLERANCE))
                .as("a mode it does not name").isEqualTo("Branch Gurgaon + Customer Delta Foods");

        // Equal conditions count and unit depth: the higher priority wins.
        UUID vendor = partner(tenant, "VN", "Vega Lines", BusinessPartner.PartnerType.VENDOR);
        combination("Delta LTL", "{\"customerId\":\"%s\",\"freightMode\":\"LTL\",\"priority\":1}"
                .formatted(customer), "{\"%s\":4}".formatted(TOLERANCE));
        UUID higher = combination("Delta by Vega", "{\"customerId\":\"%s\",\"vendorId\":\"%s\",\"priority\":2}"
                .formatted(customer, vendor), "{\"%s\":5}".formatted(TOLERANCE));
        JsonNode both = entry(effective("customerId=" + customer + "&vendorId=" + vendor + "&freightMode=LTL"),
                TOLERANCE);
        assertThat(both.get("source").get("profileId").asString()).isEqualTo(higher.toString());
        assertThat(both.get("source").get("label").asString()).isEqualTo("Customer Delta Foods + Vendor Vega Lines");
        assertThat(kit.get(tenant, "/api/v1/settings/effective?freightMode=SEA", READ).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("two combinations the fold could not choose between may not set the same key: 409 ambiguous")
    void ambiguityIsRefused() {
        combination("Branch + Delta", "{\"orgUnitId\":\"%s\",\"customerId\":\"%s\"}".formatted(branch, customer),
                "{\"%s\":2}".formatted(TOLERANCE));
        // Branch + LTL could meet Branch + Delta (an LTL order for Delta at the branch), with as many
        // conditions, the same unit and the same priority.
        Outcome twin = kit.post(tenant, PROFILES, """
                {"scopeType":"COMBINATION","name":"Branch LTL","conditions":{"orgUnitId":"%s","freightMode":"LTL"},
                 "values":{"%s":3}}""".formatted(branch, TOLERANCE), MANAGE);
        assertThat(twin.status()).isEqualTo(409);
        assertThat(twin.code()).isEqualTo("setting-combination-ambiguous");
        assertThat(kit.parse(twin).get("errors").has(TOLERANCE)).isTrue();
        // Another key is no clash; a different priority settles it; so do disjoint values.
        UUID other = combination("Branch LTL", "{\"orgUnitId\":\"%s\",\"freightMode\":\"LTL\"}".formatted(branch),
                "{\"finance.match.tolerancePercent\":2}");
        Outcome addingTheKey = patch(tenant, PROFILES + "/" + other, etag(other),
                "{\"values\":{\"%s\":3}}".formatted(TOLERANCE), MANAGE);
        assertThat(addingTheKey.code()).isEqualTo("setting-combination-ambiguous");
        Outcome withPriority = patch(tenant, PROFILES + "/" + other, etag(other), """
                {"conditions":{"orgUnitId":"%s","freightMode":"LTL","priority":5},"values":{"%s":3}}"""
                .formatted(branch, TOLERANCE), MANAGE);
        assertThat(withPriority.status()).as(withPriority.body()).isEqualTo(200);
        assertThat(kit.parse(withPriority).get("conditions").get("priority").asInt()).isEqualTo(5);
        assertThat(kit.parse(kit.get(tenant, PROFILES + "/" + other + "/history", READ)).get("items").get(0)
                .get("after").has("conditions")).isTrue();
        UUID otherCustomer = partner(tenant, "OC", "Orion Mills", BusinessPartner.PartnerType.CUSTOMER);
        assertThat(kit.post(tenant, PROFILES, """
                {"scopeType":"COMBINATION","name":"Branch + Orion","conditions":{"orgUnitId":"%s","customerId":"%s"},
                 "values":{"%s":4}}""".formatted(branch, otherCustomer, TOLERANCE), MANAGE).status()).isEqualTo(201);
        // Sibling subtrees never meet.
        UUID south = createUnit(tenant, country, "south", "South", "ZONE", null, null);
        combination("North + Delta", "{\"orgUnitId\":\"%s\",\"customerId\":\"%s\"}".formatted(zone, customer),
                "{\"%s\":5}".formatted(TOLERANCE));
        combination("South + Delta", "{\"orgUnitId\":\"%s\",\"customerId\":\"%s\"}".formatted(south, customer),
                "{\"%s\":6}".formatted(TOLERANCE));
        assertThat(kit.post(tenant, PROFILES, """
                {"scopeType":"COMBINATION","name":"North again","conditions":{"orgUnitId":"%s","customerId":"%s"},
                 "values":{"%s":7}}""".formatted(zone, customer, TOLERANCE), MANAGE).code())
                .isEqualTo("setting-combination-ambiguous");
    }

    @Test
    @DisplayName("a combination needs two known dimensions of the tenant's, keys that allow it, and no scope id")
    void combinationValidation() {
        assertThat(create("""
                {"scopeType":"COMBINATION","name":"One","conditions":{"customerId":"%s"}}""".formatted(customer))
                .code()).isEqualTo("setting-combination-invalid");
        assertThat(create("""
                {"scopeType":"COMBINATION","name":"Sea","conditions":{"customerId":"%s","freightMode":"SEA"}}"""
                .formatted(customer)).code()).isEqualTo("setting-combination-invalid");
        assertThat(create("""
                {"scopeType":"COMBINATION","name":"Prio","conditions":{"customerId":"%s","freightMode":"LTL",
                 "priority":5000}}""".formatted(customer)).code()).isEqualTo("setting-combination-invalid");
        assertThat(create("{\"scopeType\":\"COMBINATION\",\"name\":\"None\"}").code())
                .isEqualTo("setting-combination-invalid");
        assertThat(create("""
                {"scopeType":"COMBINATION","scopeId":"%s","name":"Id","conditions":{"customerId":"%s",
                 "freightMode":"LTL"}}""".formatted(customer, customer)).code()).isEqualTo("setting-scope-invalid");
        assertThat(create("""
                {"scopeType":"CUSTOMER","scopeId":"%s","name":"C","conditions":{"customerId":"%s",
                 "freightMode":"LTL"}}""".formatted(customer, customer)).code()).isEqualTo("setting-scope-invalid");
        assertThat(create("""
                {"scopeType":"COMBINATION","name":"Unknown","conditions":{"customerId":"%s","freightMode":"LTL"}}"""
                .formatted(UUID.randomUUID())).status()).isEqualTo(404);
        assertThat(create("""
                {"scopeType":"COMBINATION","name":"Theme","conditions":{"customerId":"%s","freightMode":"LTL"},
                 "values":{"ui.theme":"dark"}}""".formatted(customer)).code()).isEqualTo("setting-scope-not-allowed");
        assertThat(jdbc.sql("SELECT count(*) FROM setting_profile WHERE tenant_id = :t")
                .param("t", tenant.tenantId()).query(Integer.class).single()).isZero();
    }

    @Test
    @DisplayName("another tenant can neither read a combination nor name a tenant's unit or customer in its own")
    void combinationIsolation() {
        UUID combination = combination("Gurgaon for Delta", "{\"orgUnitId\":\"%s\",\"customerId\":\"%s\"}"
                .formatted(branch, customer), "{\"%s\":6}".formatted(TOLERANCE));
        Tenant other = kit.newTenant("other");
        UUID theirs = partner(other, "TC", "Teal Crafts", BusinessPartner.PartnerType.CUSTOMER);
        assertThat(kit.get(other, PROFILES + "/" + combination, READ).status()).isEqualTo(404);
        assertThat(kit.post(other, PROFILES, """
                {"scopeType":"COMBINATION","name":"X","conditions":{"orgUnitId":"%s","customerId":"%s"}}"""
                .formatted(branch, theirs), MANAGE).status()).isEqualTo(403);
        assertThat(kit.post(other, PROFILES, """
                {"scopeType":"COMBINATION","name":"X","conditions":{"customerId":"%s","freightMode":"LTL"}}"""
                .formatted(customer), MANAGE).status()).isEqualTo(404);
        assertThat(entry(kit.parse(kit.get(other, "/api/v1/settings/effective?customerId=" + customer + "&orgUnitId="
                + branch, READ)).get("items"), TOLERANCE).get("source").get("kind").asString())
                .isEqualTo("SYSTEM_DEFAULT");
    }

    @Test
    @DisplayName("one statement resolves a context with units, a jurisdiction and combinations")
    void oneStatement() {
        profile("ORG_UNIT", zone, "North terms", "{\"%s\":2}".formatted(TOLERANCE));
        combination("Branch + Delta", "{\"orgUnitId\":\"%s\",\"customerId\":\"%s\"}".formatted(branch, customer),
                "{\"%s\":2}".formatted(TOLERANCE));
        combination("Delta LTL", "{\"customerId\":\"%s\",\"freightMode\":\"LTL\"}".formatted(customer),
                "{\"finance.match.tolerancePercent\":2}");
        jdbc.sql("CREATE EXTENSION IF NOT EXISTS pg_stat_statements").update();
        jdbc.sql("SELECT pg_stat_statements_reset()").query().singleRow();
        assertThat(kit.get(tenant, "/api/v1/settings/effective?orgUnitId=" + branch + "&customerId=" + customer
                + "&freightMode=LTL", READ).status()).isEqualTo(200);
        assertThat(jdbc.sql("""
                        SELECT coalesce(sum(calls), 0) FROM pg_stat_statements
                         WHERE query ILIKE '%setting_profile%' AND query NOT ILIKE '%pg_stat_statements%'
                        """).query(Long.class).single()).isEqualTo(1);
    }

    // --------------------------------------------------------------- helpers

    private UUID createUnit(Tenant t, UUID parent, String slug, String name, String type, String country,
                            String state) {
        Outcome created = post(t, parent, slug, name, type, country, state);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return UUID.fromString(kit.parse(created).get("id").asString());
    }

    private Outcome post(Tenant t, UUID parent, String slug, String name, String type, String country,
                         String state) {
        return kit.post(t, ORG_UNITS, """
                {"parentId":"%s","slug":"%s","name":"%s","unitType":"%s"%s%s}""".formatted(parent, slug, name, type,
                country == null ? "" : ",\"countryCode\":\"" + country + "\"",
                state == null ? "" : ",\"stateCode\":\"" + state + "\""), UNITS);
    }

    private String etagOf(UUID unit) {
        return "\"" + kit.parse(kit.get(tenant, ORG_UNITS + "/" + unit)).get("version").asLong() + "\"";
    }

    private String etag(UUID profile) {
        return "\"" + kit.parse(kit.get(tenant, PROFILES + "/" + profile, READ)).get("version").asLong() + "\"";
    }

    private UUID profile(String scopeType, UUID scopeId, String name, String values) {
        Outcome created = create("""
                {"scopeType":"%s","scopeId":"%s","name":"%s","values":%s}""".formatted(scopeType, scopeId, name, values));
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return UUID.fromString(kit.parse(created).get("id").asString());
    }

    private UUID combination(String name, String conditions, String values) {
        Outcome created = create("""
                {"scopeType":"COMBINATION","name":"%s","conditions":%s,"values":%s}""".formatted(name, conditions,
                values));
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return UUID.fromString(kit.parse(created).get("id").asString());
    }

    private Outcome create(String body) {
        return kit.post(tenant, PROFILES, body, MANAGE);
    }

    private UUID platform(String countryCode, String state, String name, int rate, LocalDate from) {
        Outcome created = kit.post(tenant, PROFILES, """
                {"scopeType":"PLATFORM","name":"%s"%s%s,"versions":[{"key":"%s","effectiveFrom":"%s","value":%d}]}"""
                .formatted(name, countryCode == null ? "" : ",\"country\":\"" + countryCode + "\"",
                        state == null ? "" : ",\"state\":\"" + state + "\"", RATE, from, rate), PLATFORM);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return UUID.fromString(kit.parse(created).get("id").asString());
    }

    private JsonNode effective(String query) {
        Outcome outcome = kit.get(tenant, "/api/v1/settings/effective?" + query, READ);
        assertThat(outcome.status()).as(outcome.body()).isEqualTo(200);
        return kit.parse(outcome).get("items");
    }

    private static String source(JsonNode items, String key) {
        return entry(items, key).get("source").get("label").asString();
    }

    private static JsonNode entry(JsonNode items, String key) {
        return stream(items).filter(e -> e.get("key").asString().equals(key)).findFirst().orElseThrow();
    }

    private Outcome put(Tenant t, String path, String ifMatch, String body, String... permissions) {
        return send("PUT", t, path, ifMatch, body, permissions);
    }

    private Outcome patch(Tenant t, String path, String ifMatch, String body, String... permissions) {
        return send("PATCH", t, path, ifMatch, body, permissions);
    }

    private Outcome send(String method, Tenant t, String path, String ifMatch, String body, String... permissions) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + kit.token(t, permissions))
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return kit.send(request.build());
    }

    private UUID partner(Tenant t, String code, String legalName, BusinessPartner.PartnerType type) {
        return asSetup(t, () -> fleet.registerPartner(t.orgUnitId(), code, legalName, type,
                "TAX-" + code + "-" + t.tenantId().toString().substring(0, 8)));
    }

    private <T> T asSetup(Tenant t, Supplier<T> action) {
        Set<SimpleGrantedAuthority> authorities = Set.of("PARTNER_CREATE").stream()
                .map(SimpleGrantedAuthority::new).collect(Collectors.toSet());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("setup", "n/a", authorities));
        try {
            return TenantContext.callWith(t.tenantId(), t.orgPath(), action);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static Stream<JsonNode> stream(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false);
    }
}
