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
package com.lms.e2e;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

import com.lms.client.api.OrdersApi;
import com.lms.client.model.RaiseOrderRequest;
import com.lms.client.model.RegisterPartnerRequest;
import com.lms.client.model.RegisterPointRadiusTerminalRequest;
import com.lms.e2e.ContractSupport.Caller;
import com.lms.e2e.ContractSupport.OtherTenant;
import com.lms.e2e.ContractSupport.Reply;
import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.yaml.snakeyaml.Yaml;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The API's contract behaviour over HTTP, across the whole surface: who may
 * call, what a refusal looks like, what a retry does, how a listing pages and
 * what the served contract says.
 *
 * <p>Every call here is raw HTTP (see {@link ContractSupport}), because the
 * subject is the wire itself. Every step phrase starts with "the contract" or
 * names a "contract" caller so that none can collide with another step class:
 * Cucumber glue is global to the package and an ambiguous step fails the
 * whole suite.
 *
 * <p>The demo tenant is shared by every end-to-end scenario, so every code is
 * salted, nothing here depends on a global count, and the one kind of data this
 * class writes to shared tenant tables besides salted resources -- the
 * limited users -- is removed in an {@code @After} hook whatever the outcome.
 */
public class ContractSteps {

    private static final Pattern PERMISSION_CODE = Pattern.compile("\\b[A-Z]+(_[A-Z]+)+\\b");

    /** Writes the routing probe must not send: one generates runs for the whole tenant. */
    private static final Set<String> NOT_PROBED = Set.of(
            "POST /api/v1/milk-runs/generate",
            "POST /api/v1/auth/login",
            "POST /api/v1/auth/refresh",
            "POST /api/v1/auth/logout");

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JwtEncoder jwtEncoder;

    private HttpWorld world;
    private ContractSupport support;
    private String admin;
    private final List<Caller> callers = new ArrayList<>();
    private String callerToken;

    private Reply reply;
    private String requestPath;
    private final List<Reply> history = new ArrayList<>();

    private OtherTenant other;
    private String otherToken;
    private UUID ownedId;
    private String ownedPath;

    private String listingPath;
    private final List<UUID> registered = new ArrayList<>();
    private final List<Reply> pages = new ArrayList<>();

    private Map<String, Object> contractDocument;
    private final List<String> probeFailures = new ArrayList<>();
    private int probed;

    @After("@e2e_contract")
    public void removeCallers() {
        if (support != null) {
            callers.forEach(support::remove);
        }
    }

    // ------------------------------------------------------------ session

    @Given("the contract suite is signed in as the demo administrator")
    public void signedIn() {
        world = new HttpWorld(port);
        world.signInAsDemoAdministrator();
        support = new ContractSupport(port, jdbc, passwordEncoder, transactionManager, jwtEncoder);
        admin = support.login(HttpWorld.DEMO_TENANT, HttpWorld.DEMO_ADMIN, EndToEndContext.DEMO_PASSWORD);
    }

    private String resolve(String text) {
        String id = UUID.randomUUID().toString();
        return text.replace("{id}", id).replace("{org}", world.orgUnitId().toString())
                .replace("{salt}", world.salt);
    }

    private Reply send(String token, String method, String path, String body, String... headers) {
        requestPath = path;
        reply = support.send(method, path, token, body, headers);
        history.add(reply);
        return reply;
    }

    /** Valid bodies, so that a refusal can only be about who is asking. */
    private String bodyOfKind(String kind) {
        String id = UUID.randomUUID().toString();
        return switch (kind) {
            case "none" -> null;
            case "empty object" -> "{}";
            case "an order" -> """
                    {"orgUnitId":"%s","customerPartnerId":"%s","orderNo":"CT-%s",
                     "originTerminalId":"%s","requestedPickupAt":"2031-01-01T00:00:00Z","source":"API"}"""
                    .formatted(world.orgUnitId(), id, world.salt, id);
            case "a trip" -> """
                    {"tripNo":"CT-%s","loadId":"%s","plannedStartAt":"2031-01-01T00:00:00Z"}"""
                    .formatted(world.salt, id);
            case "a ping batch" -> """
                    {"pings":[{"vehicleId":"%s","recordedAt":"2031-01-01T00:00:00Z","lat":61.0,"lon":61.0}]}"""
                    .formatted(id);
            case "a load" -> """
                    {"orgUnitId":"%s","loadNo":"CT-%s","originTerminalId":"%s","vehicleId":"%s"}"""
                    .formatted(world.orgUnitId(), world.salt, id, id);
            case "malformed JSON" -> "{not json";
            case "truncated JSON" -> "{\"code\":\"X\"";
            case "a bare scalar" -> "\"text\"";
            case "an array" -> "[]";
            case "a wrong JSON type" -> "{\"code\":[1,2],\"legalName\":{}}";
            case "an unknown enum value" -> """
                    {"orgUnitId":"%s","code":"CT-ENUM-%s","legalName":"Enum Pvt Ltd","partnerType":"NOPE"}"""
                    .formatted(world.orgUnitId(), world.salt);
            case "a malformed id in the body" -> "{\"orgUnitId\":\"not-a-uuid\"}";
            case "an allocation" -> "{\"loadId\":\"%s\"}".formatted(id);
            case "a partner" -> partnerBody("CT-DENIED-" + world.salt, "Denied Pvt Ltd");
            case "a terminal" -> """
                    {"orgUnitId":"%s","code":"CT-DENIED-%s","name":"Denied","category":"HUB",
                     "centre":{"lat":61.9,"lon":61.9},"radiusMetres":500,"dockCount":1,"avgDwellMinutes":5}"""
                    .formatted(world.orgUnitId(), world.salt);
            default -> throw new AssertionError("no body of kind " + kind);
        };
    }

    private String partnerBody(String code, String legalName) {
        return """
                {"orgUnitId":"%s","code":"%s","legalName":"%s","partnerType":"CUSTOMER"}"""
                .formatted(world.orgUnitId(), code, legalName);
    }

    // ------------------------------------------------- 401: no credential

    @When("the contract caller sends {string} without a token")
    public void withoutToken(String operation) {
        String[] parts = resolve(operation).split(" ", 2);
        send(null, parts[0], parts[1], parts[0].equals("GET") ? null : "{}");
    }

    @When("the contract caller presents {string} to {string}")
    public void presents(String credential, String operation) {
        String[] parts = resolve(operation).split(" ", 2);
        UUID tenantId = support.tenantIdOf(HttpWorld.DEMO_TENANT);
        switch (credential) {
            case "a garbage token" -> send("not-a-jwt", parts[0], parts[1], null);
            case "a three-part garbage token" -> send("aaa.bbb.ccc", parts[0], parts[1], null);
            case "an unsigned token" -> send(unsignedToken(), parts[0], parts[1], null);
            case "an expired token" -> send(
                    support.expiredToken(tenantId, "/demo/", List.of("PARTNER_READ")),
                    parts[0], parts[1], null);
            case "a Basic credential" -> send(null, parts[0], parts[1], null,
                    "Authorization", "Basic YWRtaW46eA==");
            default -> throw new AssertionError("unknown credential " + credential);
        }
    }

    @Then("the contract answer is 401 with a Bearer challenge")
    public void unauthorised() {
        assertThat(reply.status()).as("answer: " + reply).isEqualTo(401);
        assertThat(reply.header("WWW-Authenticate")).as("the challenge").startsWith("Bearer");
    }

    @Then("the contract refusal is a problem document that does not say why the token failed")
    public void genericRefusal() {
        assertThat(reply.isProblem()).as("content type " + reply.contentType() + ": " + reply).isTrue();
        Map<String, Object> problem = reply.json();
        assertThat(problem.get("status")).isEqualTo(401);
        assertThat(problem.get("code")).isIn("authentication-required", "invalid-token");
        assertThat(problem.get("instance")).isEqualTo(requestPath.replaceAll("\\?.*$", ""));
        assertThat(reply.body()).doesNotContainIgnoringCase("jwt").doesNotContainIgnoringCase("signature")
                .doesNotContainIgnoringCase("decode").doesNotContainIgnoringCase("algorithm")
                .doesNotContain("Exception").doesNotContain("com.lms");
        assertThat(String.valueOf(reply.header("WWW-Authenticate"))).doesNotContainIgnoringCase("jwt")
                .doesNotContainIgnoringCase("signature").doesNotContainIgnoringCase("decode");
    }

    @Then("the contract refusal code is {string}")
    public void refusalCode(String code) {
        assertThat(reply.code()).isEqualTo(code);
    }

    @Then("the contract challenge says the token expired")
    public void expiredChallenge() {
        assertThat(reply.header("WWW-Authenticate")).contains("invalid_token").contains("expired");
    }

    @When("the contract caller presents a freshly minted token with the same claims to list partners")
    public void freshToken() {
        UUID tenantId = support.tenantIdOf(HttpWorld.DEMO_TENANT);
        String token = support.mint(tenantId, "/demo/", List.of("PARTNER_READ"),
                Instant.now(), Instant.now().plusSeconds(600));
        send(token, "GET", "/api/v1/partners?limit=1", null);
    }

    @Then("the contract answer is 200")
    public void ok() {
        assertThat(reply.status()).as("answer: " + reply).isEqualTo(200);
    }

    // ------------------------------------- a token without tenant, without perms

    @Given("the contract tenant holds a partner of its own")
    public void ownPartner() {
        ownedId = world.partner("CT-OWN", RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER);
        ownedPath = "/api/v1/partners/" + ownedId;
    }

    @When("the contract caller reads that partner with a token that has no tenant claim")
    public void noTenantClaim() {
        String token = support.mint(null, "/demo/", List.of("PARTNER_READ"),
                Instant.now(), Instant.now().plusSeconds(600));
        send(token, "GET", ownedPath, null);
    }

    @When("the contract caller reads that partner with a token for the tenant that holds PARTNER_READ")
    public void tenantClaim() {
        String token = support.mint(support.tenantIdOf(HttpWorld.DEMO_TENANT), "/demo/",
                List.of("PARTNER_READ"), Instant.now(), Instant.now().plusSeconds(600));
        send(token, "GET", ownedPath, null);
    }

    @When("the contract caller reads that partner with a token for the tenant that carries no permissions")
    public void noPerms() {
        String token = support.mint(support.tenantIdOf(HttpWorld.DEMO_TENANT), "/demo/",
                List.of(), Instant.now(), Instant.now().plusSeconds(600));
        send(token, "GET", ownedPath, null);
    }

    @Then("the contract answer is the partner itself")
    public void thePartner() {
        assertThat(reply.status()).as("answer: " + reply).isEqualTo(200);
        assertThat(reply.json().get("id")).isEqualTo(ownedId.toString());
    }

    // ---------------------------------------------------- 403: permissions

    @Given("a contract caller holding only {string}")
    public void limitedCaller(String permissions) {
        List<String> codes = List.of(permissions.split(",\\s*"));
        Caller caller = support.provisionUser(HttpWorld.DEMO_TENANT, world.orgUnitId(),
                world.salt, codes);
        callers.add(caller);
        callerToken = support.login(HttpWorld.DEMO_TENANT, caller.email(), ContractSupport.PASSWORD);
    }

    @When("that contract caller sends {string} to {string} with {string} as the body")
    public void callerSends(String method, String path, String bodyKind) {
        send(callerToken, method, resolve(path), bodyOfKind(bodyKind));
    }

    @Then("the contract answer is a {int} problem with code {string}")
    public void problemWithCode(int status, String code) {
        assertThat(reply.status()).as("answer: " + reply).isEqualTo(status);
        assertThat(reply.isProblem()).as("content type " + reply.contentType()).isTrue();
        Map<String, Object> problem = reply.json();
        assertThat(problem.get("status")).as("status in the document").isEqualTo(status);
        assertThat(problem.get("code")).isEqualTo(code);
        assertThat((String) problem.get("type")).endsWith("/" + code);
        assertThat(problem.get("instance")).as("instance names the request path")
                .isEqualTo(requestPath.replaceAll("\\?.*$", ""));
        assertThat((String) problem.get("title")).isNotBlank();
        assertThat((String) problem.get("detail")).isNotBlank();
    }

    @Then("the contract problem keeps the Allow header listing {string}")
    public void allowHeader(String methods) {
        String allow = reply.header("Allow");
        assertThat(allow).as("Allow header on " + reply).isNotBlank();
        for (String method : methods.split(",\\s*")) {
            assertThat(allow).contains(method);
        }
    }

    @Then("the contract problem reveals nothing of the implementation")
    public void noInternals() {
        assertThat(reply.body()).doesNotContain("Exception").doesNotContain("com.lms")
                .doesNotContain("org.springframework").doesNotContain("jackson")
                .doesNotContain("java.lang").doesNotContain("SELECT").doesNotContain("\tat ");
    }

    @Then("the contract problem does not name a permission")
    public void noPermissionNamed() {
        assertThat((String) reply.json().get("detail")).doesNotContainPattern(PERMISSION_CODE);
    }

    @Then("the contract caller may read partners")
    public void mayReadPartners() {
        send(callerToken, "GET", "/api/v1/partners?limit=1", null);
        assertThat(reply.status()).as("answer: " + reply).isEqualTo(200);
        assertThat(reply.json()).containsKeys("items", "hasMore");
    }

    @Then("the contract caller may not read partners")
    public void mayNotReadPartners() {
        send(callerToken, "GET", "/api/v1/partners?limit=1", null);
        problemWithCode(403, "access-denied");
    }

    @Then("the contract caller may not register a partner")
    public void mayNotRegister() {
        send(callerToken, "POST", "/api/v1/partners", partnerBody("CT-NOPE-" + world.salt, "Nope"));
        problemWithCode(403, "access-denied");
    }

    @Then("the contract caller may register the partner {string}")
    public void mayRegister(String code) {
        send(callerToken, "POST", "/api/v1/partners", partnerBody(code + "-" + world.salt, code));
        assertThat(reply.status()).as("answer: " + reply).isEqualTo(201);
        assertThat(reply.id()).isNotNull();
    }

    @Then("the contract caller may not read terminals")
    public void mayNotReadTerminals() {
        send(callerToken, "GET", "/api/v1/terminals?limit=1", null);
        problemWithCode(403, "access-denied");
    }

    @Then("the contract caller may read terminals")
    public void mayReadTerminals() {
        send(callerToken, "GET", "/api/v1/terminals?limit=1", null);
        assertThat(reply.status()).as("answer: " + reply).isEqualTo(200);
    }

    @Then("the refused partner {string} was never created, so the administrator can register it")
    public void neverCreated(String code) {
        send(admin, "POST", "/api/v1/partners", partnerBody(code + "-" + world.salt, code));
        assertThat(reply.status()).as("a refused write leaves nothing behind: " + reply).isEqualTo(201);
    }

    // ------------------------------------------------ administrator calls

    @When("the contract administrator sends {string} to {string}")
    public void adminSends(String method, String path) {
        send(admin, method, resolve(path), null);
    }

    @When("the contract administrator sends {string} to {string} with {string} as the body")
    public void adminSendsBody(String method, String path, String bodyKind) {
        send(admin, method, resolve(path), bodyOfKind(bodyKind));
    }

    @When("the contract administrator posts a plain text body to {string}")
    public void adminPostsPlainText(String path) {
        send(admin, "POST", resolve(path), "not json at all", "Content-Type", "text/plain");
    }

    @When("the contract administrator sends {string} to {string} with the correlation id {string}")
    public void adminSendsCorrelated(String method, String path, String correlationId) {
        send(admin, method, resolve(path), method.equals("GET") ? null : "{}",
                "X-Correlation-Id", correlationId);
    }

    @When("the contract caller sends {string} to {string} without a token and with the correlation id {string}")
    public void anonymousCorrelated(String method, String path, String correlationId) {
        send(null, method, resolve(path), null, "X-Correlation-Id", correlationId);
    }

    @Then("the contract answer echoes the correlation id {string}")
    public void echoesCorrelation(String correlationId) {
        assertThat(reply.header("X-Correlation-Id")).isEqualTo(correlationId);
    }

    @Then("the contract problem reports field errors for {string}")
    @SuppressWarnings("unchecked")
    public void fieldErrors(String fields) {
        Object errors = reply.json().get("errors");
        assertThat(errors).as("errors map in " + reply).isInstanceOf(Map.class);
        Map<String, Object> byField = (Map<String, Object>) errors;
        for (String field : fields.split(",\\s*")) {
            assertThat(byField).as("error for " + field).containsKey(field);
            assertThat((String) byField.get(field)).as("message for " + field).isNotBlank();
        }
    }

    @Then("the contract problem reports field errors for exactly {string}")
    @SuppressWarnings("unchecked")
    public void exactFieldErrors(String fields) {
        Map<String, Object> byField = (Map<String, Object>) reply.json().get("errors");
        assertThat(new TreeSet<>(byField.keySet()))
                .isEqualTo(new TreeSet<>(List.of(fields.split(",\\s*"))));
    }

    @When("the contract administrator registers a partner whose code is blank and whose legal name is blank")
    public void blankPartner() {
        send(admin, "POST", "/api/v1/partners", """
                {"orgUnitId":"%s","code":" ","legalName":"","partnerType":"CUSTOMER"}"""
                .formatted(world.orgUnitId()));
    }

    @When("the contract administrator registers a partner whose code is blank")
    public void blankCodePartner() {
        send(admin, "POST", "/api/v1/partners", """
                {"orgUnitId":"%s","code":" ","legalName":"Fine Pvt Ltd","partnerType":"CUSTOMER"}"""
                .formatted(world.orgUnitId()));
    }

    @Then("the contract problem detail names the requested id")
    public void namesId() {
        String id = requestPath.substring(requestPath.lastIndexOf('/') + 1);
        assertThat((String) reply.json().get("detail")).contains(id);
    }

    @Then("the contract problem detail names the requested tracking reference")
    public void namesRef() {
        String ref = requestPath.substring(requestPath.lastIndexOf('/') + 1);
        assertThat((String) reply.json().get("detail")).contains(ref);
    }

    @When("the contract administrator registers the partner {string}")
    public void registers(String code) {
        send(admin, "POST", "/api/v1/partners", partnerBody(code + "-" + world.salt, code));
    }

    @Then("the contract answer is 201 with an id and a Location for that partner")
    public void created() {
        assertThat(reply.status()).as("answer: " + reply).isEqualTo(201);
        assertThat(reply.header("Location")).endsWith("/api/v1/partners/" + reply.id());
        ownedId = reply.id();
    }

    @Then("registering the partner {string} again is refused as a conflict that leaves the first untouched")
    public void conflict(String code) {
        Reply first = reply;
        send(admin, "POST", "/api/v1/partners", partnerBody(code + "-" + world.salt, "Second attempt"));
        problemWithCode(409, "partner-code-taken");
        assertThat((String) reply.json().get("detail")).contains(code + "-" + world.salt);
        send(admin, "GET", "/api/v1/partners/" + first.id(), null);
        assertThat(reply.json().get("legalName")).as("the original survives").isEqualTo(code);
    }

    // ------------------------------------------------------- other tenant

    @Given("a second tenant with its own administrator")
    public void secondTenant() {
        other = support.provisionTenant(world.salt);
        otherToken = support.login(other.code(), other.email(), ContractSupport.PASSWORD);
    }

    @Given("the demo tenant owns a contract {word}")
    public void owns(String kind) {
        switch (kind) {
            case "partner" -> {
                ownedId = world.partner("CT-OWNED", RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER);
                ownedPath = "/api/v1/partners/" + ownedId;
                listingPath = "/api/v1/partners";
            }
            case "terminal" -> {
                ownedId = world.terminal(RegisterPointRadiusTerminalRequest.CategoryEnum.HUB,
                        "CT-OWNED", 62.0, 62.0);
                ownedPath = "/api/v1/terminals/" + ownedId;
                listingPath = "/api/v1/terminals";
            }
            case "vehicle" -> {
                ownedId = world.lorry("CTO", 10000);
                ownedPath = "/api/v1/vehicles/" + ownedId;
                listingPath = "/api/v1/vehicles";
            }
            case "driver" -> {
                ownedId = world.driver("CTO");
                ownedPath = "/api/v1/drivers/" + ownedId;
                listingPath = "/api/v1/drivers";
            }
            case "order" -> {
                UUID customer = world.partner("CT-CUST", RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER);
                UUID origin = world.terminal(RegisterPointRadiusTerminalRequest.CategoryEnum.CUSTOMER_SITE,
                        "CT-ORIGIN", 63.0, 63.0);
                ownedId = world.api(OrdersApi.class).raiseOrder(new RaiseOrderRequest()
                        .orgUnitId(world.orgUnitId()).customerPartnerId(customer)
                        .orderNo("SO-CTO-" + world.salt).originTerminalId(origin)
                        .requestedPickupAt(java.time.OffsetDateTime.now(ZoneOffset.UTC).plusHours(4))
                        .source(RaiseOrderRequest.SourceEnum.API), null).getId();
                ownedPath = "/api/v1/orders/" + ownedId;
                listingPath = "/api/v1/orders";
            }
            default -> throw new AssertionError("unknown kind " + kind);
        }
    }

    @Then("the demo administrator reads that resource")
    public void adminReadsOwned() {
        send(admin, "GET", ownedPath, null);
        assertThat(reply.status()).as("a positive control: " + reply).isEqualTo(200);
        assertThat(reply.json().get("id")).isEqualTo(ownedId.toString());
    }

    @Then("the second tenant's administrator gets a 404 not-found for that resource")
    public void otherTenantReads() {
        send(otherToken, "GET", ownedPath, null);
        problemWithCode(404, "not-found");
    }

    @Then("that resource is absent from the second tenant's listing")
    @SuppressWarnings("unchecked")
    public void absentFromListing() {
        send(otherToken, "GET", listingPath + "?limit=200", null);
        assertThat(reply.status()).isEqualTo(200);
        List<Map<String, Object>> items = (List<Map<String, Object>>) reply.json().get("items");
        assertThat(items).extracting(item -> item.get("id")).doesNotContain(ownedId.toString());
    }

    @Then("the second tenant's administrator cannot move that partner along its lifecycle")
    public void otherTenantTransitions() {
        send(otherToken, "POST", "/api/v1/partners/" + ownedId + "/transitions",
                "{\"target\":\"PENDING_VERIFICATION\"}");
        assertThat(reply.status()).as("answer: " + reply).isEqualTo(404);
        assertThat(reply.code()).isEqualTo("not-found");
        send(admin, "GET", ownedPath, null);
        assertThat(reply.json().get("status")).as("the partner did not move").isEqualTo("DRAFT");
    }

    @Then("a partner the second tenant registers is invisible to the demo administrator")
    public void invisibleToDemo() {
        send(otherToken, "POST", "/api/v1/partners", """
                {"orgUnitId":"%s","code":"CT-B-%s","legalName":"B Ltd","partnerType":"CUSTOMER"}"""
                .formatted(other.orgUnitId(), world.salt));
        assertThat(reply.status()).as("answer: " + reply).isEqualTo(201);
        UUID theirs = reply.id();
        send(admin, "GET", "/api/v1/partners/" + theirs, null);
        problemWithCode(404, "not-found");
        send(otherToken, "GET", "/api/v1/partners/" + theirs, null);
        assertThat(reply.status()).as("it is theirs: " + reply).isEqualTo(200);
    }

    // -------------------------------------------------------- idempotency

    @When("the contract administrator registers the partner {string} with the key {string}")
    public void registersWithKey(String code, String key) {
        send(admin, "POST", "/api/v1/partners", partnerBody(code + "-" + world.salt, code),
                "Idempotency-Key", key + "-" + world.salt);
    }

    @When("the contract administrator registers the partner {string} named {string} with the key {string}")
    public void registersNamedWithKey(String code, String name, String key) {
        send(admin, "POST", "/api/v1/partners", partnerBody(code + "-" + world.salt, name),
                "Idempotency-Key", key + "-" + world.salt);
    }

    @When("the contract administrator sends an empty object to register a partner with the key {string}")
    public void invalidWithKey(String key) {
        send(admin, "POST", "/api/v1/partners", "{}", "Idempotency-Key", key + "-" + world.salt);
    }

    @Then("answer number {int} was created and carries no replay marker")
    public void firstAnswer(int n) {
        Reply r = history.get(n - 1);
        assertThat(r.status()).as("answer " + n + ": " + r).isEqualTo(201);
        assertThat(r.header("Idempotent-Replay")).isNull();
    }

    @Then("answer number {int} replays answer number {int} with the same status and body")
    public void replays(int later, int earlier) {
        Reply a = history.get(earlier - 1);
        Reply b = history.get(later - 1);
        assertThat(b.status()).as("replayed status").isEqualTo(a.status());
        assertThat(b.id()).as("replayed id").isEqualTo(a.id());
        assertThat(b.header("Idempotent-Replay")).as("replay marker").isEqualTo("true");
    }

    @Then("answer number {int} is refused as a reused key")
    public void reusedKey(int n) {
        reply = history.get(n - 1);
        requestPath = "/api/v1/partners";
        assertThat(reply.status()).as("answer: " + reply).isEqualTo(409);
        assertThat(reply.isProblem()).isTrue();
        assertThat(reply.code()).isEqualTo("idempotency-key-reused");
    }

    @Then("exactly one partner exists with the code {string}")
    public void exactlyOne(String code) {
        // The code is unique, so a second registration is refused only if the
        // first one exists, and a first one exists only once.
        send(admin, "POST", "/api/v1/partners", partnerBody(code + "-" + world.salt, "Probe"));
        assertThat(reply.status()).as("answer: " + reply).isEqualTo(409);
        assertThat(reply.code()).isEqualTo("partner-code-taken");
    }

    @Then("no partner exists yet with the code {string}, so registering it now is a first creation")
    public void noneYet(String code) {
        send(admin, "POST", "/api/v1/partners", partnerBody(code + "-" + world.salt, code));
        assertThat(reply.status()).as("answer: " + reply).isEqualTo(201);
    }

    @Then("answer number {int} is refused as a validation failure")
    public void validationFailure(int n) {
        reply = history.get(n - 1);
        requestPath = "/api/v1/partners";
        problemWithCode(400, "validation-failed");
        assertThat(reply.header("Idempotent-Replay")).isNull();
    }

    @Then("answer number {int} is refused as a duplicate code")
    public void duplicateCode(int n) {
        reply = history.get(n - 1);
        requestPath = "/api/v1/partners";
        problemWithCode(409, "partner-code-taken");
    }

    @Then("answer number {int} is a fresh creation, not a replay of the failure")
    public void freshAfterFailure(int n) {
        Reply r = history.get(n - 1);
        assertThat(r.status()).as("the key was released: " + r).isEqualTo(201);
        assertThat(r.header("Idempotent-Replay")).isNull();
        assertThat(r.id()).isNotNull();
    }

    @Given("the contract administrator has a partner to attach documents to")
    public void partnerForDocuments() {
        ownedId = world.partner("CT-DOC", RegisterPartnerRequest.PartnerTypeEnum.VENDOR);
        registered.clear();
        registered.add(world.partner("CT-DOC2", RegisterPartnerRequest.PartnerTypeEnum.VENDOR));
    }

    private String documentBody() {
        return """
                {"documentType":"GST","documentNo":"GST-%s","issuingAuthority":"GSTN",
                 "issuedOn":"%s","expiresOn":"%s"}"""
                .formatted(world.salt, LocalDate.now(ZoneOffset.UTC).minusMonths(1),
                        LocalDate.now(ZoneOffset.UTC).plusYears(1));
    }

    @When("the contract administrator attaches the same document to that partner twice with the key {string}")
    public void attachTwice(String key) {
        for (int i = 0; i < 2; i++) {
            send(admin, "POST", "/api/v1/partners/" + ownedId + "/documents", documentBody(),
                    "Idempotency-Key", key + "-" + world.salt);
        }
    }

    @When("the contract administrator attaches that document to that partner with no key")
    public void attachNoKey() {
        send(admin, "POST", "/api/v1/partners/" + ownedId + "/documents", documentBody());
    }

    @When("the contract administrator attaches that document to that partner with the key {string}")
    public void attachWithKey(String key) {
        send(admin, "POST", "/api/v1/partners/" + ownedId + "/documents", documentBody(),
                "Idempotency-Key", key + "-" + world.salt);
    }

    @When("the contract administrator attaches that document to the other partner with the key {string}")
    public void attachOtherWithKey(String key) {
        send(admin, "POST", "/api/v1/partners/" + registered.get(0) + "/documents", documentBody(),
                "Idempotency-Key", key + "-" + world.salt);
    }

    @Then("the first two document answers are one creation and its replay")
    public void documentReplay() {
        Reply a = history.get(history.size() - 2);
        Reply b = history.get(history.size() - 1);
        assertThat(a.status()).as("first: " + a).isEqualTo(201);
        assertThat(b.status()).as("second: " + b).isEqualTo(201);
        assertThat(b.id()).isEqualTo(a.id());
        assertThat(a.header("Idempotent-Replay")).isNull();
        assertThat(b.header("Idempotent-Replay")).isEqualTo("true");
    }

    @Then("that partner holds {int} document(s)")
    public void documentCount(int count) {
        send(admin, "GET", "/api/v1/partners/" + ownedId + "/documents", null);
        assertThat(reply.status()).as("answer: " + reply).isEqualTo(200);
        assertThat(reply.jsonList()).hasSize(count);
    }

    @Then("the other partner holds {int} documents")
    public void otherDocumentCount(int count) {
        send(admin, "GET", "/api/v1/partners/" + registered.get(0) + "/documents", null);
        assertThat(reply.status()).as("answer: " + reply).isEqualTo(200);
        assertThat(reply.jsonList()).hasSize(count);
    }

    @Then("the last document answer is refused as a reused key")
    public void lastReused() {
        reply = history.get(history.size() - 1);
        requestPath = "/api/v1/partners/" + registered.get(0) + "/documents";
        assertThat(reply.status()).as("answer: " + reply).isEqualTo(409);
        assertThat(reply.code()).isEqualTo("idempotency-key-reused");
    }

    @When("the contract administrator raises the same order twice with the key {string}")
    public void raiseOrderTwice(String key) {
        UUID customer = world.partner("CT-OCUST", RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER);
        UUID origin = world.terminal(RegisterPointRadiusTerminalRequest.CategoryEnum.CUSTOMER_SITE,
                "CT-OIDEM", 63.5, 63.5);
        String body = """
                {"orgUnitId":"%s","customerPartnerId":"%s","orderNo":"SO-CTI-%s",
                 "originTerminalId":"%s","requestedPickupAt":"2031-01-01T00:00:00Z","source":"API"}"""
                .formatted(world.orgUnitId(), customer, world.salt, origin);
        listingPath = body;
        for (int i = 0; i < 2; i++) {
            send(admin, "POST", "/api/v1/orders", body, "Idempotency-Key", key + "-" + world.salt);
        }
    }

    @Then("the two order answers are one creation and its replay")
    public void orderReplay() {
        Reply a = history.get(history.size() - 2);
        Reply b = history.get(history.size() - 1);
        assertThat(a.status()).as("first: " + a).isEqualTo(201);
        assertThat(b.status()).as("second: " + b).isEqualTo(201);
        assertThat(b.id()).isEqualTo(a.id());
        assertThat(b.header("Idempotent-Replay")).isEqualTo("true");
        ownedId = a.id();
    }

    @Then("that order exists once, because raising its number again with a new key is a conflict")
    public void orderExistsOnce() {
        send(admin, "GET", "/api/v1/orders/" + ownedId, null);
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.json().get("orderNo")).isEqualTo("SO-CTI-" + world.salt);
        send(admin, "POST", "/api/v1/orders", listingPath,
                "Idempotency-Key", "fresh-" + world.salt);
        assertThat(reply.status()).as("answer: " + reply).isEqualTo(409);
        assertThat(reply.isProblem()).isTrue();
    }

    @When("the second tenant registers a partner with the key {string} that the demo tenant also used")
    public void otherTenantSameKey(String key) {
        send(otherToken, "POST", "/api/v1/partners", """
                {"orgUnitId":"%s","code":"CT-KEY-B-%s","legalName":"B","partnerType":"CUSTOMER"}"""
                .formatted(other.orgUnitId(), world.salt), "Idempotency-Key", key + "-" + world.salt);
    }

    @Then("the second tenant's answer is a fresh creation of its own")
    public void ownCreation() {
        Reply demo = history.get(0);
        assertThat(reply.status()).as("answer: " + reply).isEqualTo(201);
        assertThat(reply.header("Idempotent-Replay")).isNull();
        assertThat(reply.id()).isNotEqualTo(demo.id());
    }

    @When("the contract caller signs in twice with the key {string}")
    public void loginTwice(String key) {
        String body = """
                {"tenantCode":"%s","email":"%s","password":"%s"}"""
                .formatted(HttpWorld.DEMO_TENANT, HttpWorld.DEMO_ADMIN, EndToEndContext.DEMO_PASSWORD);
        for (int i = 0; i < 2; i++) {
            send(null, "POST", "/api/v1/auth/login", body, "Idempotency-Key", key + "-" + world.salt);
        }
    }

    @Then("both sign-ins succeed and neither is a replay")
    public void neitherReplay() {
        for (Reply r : history.subList(history.size() - 2, history.size())) {
            assertThat(r.status()).as("sign-in: " + r.status()).isEqualTo(200);
            assertThat(r.header("Idempotent-Replay")).isNull();
            assertThat(r.json()).containsKey("accessToken");
        }
    }

    @When("the contract administrator reads the partners twice with the key {string}")
    public void readTwice(String key) {
        for (int i = 0; i < 2; i++) {
            send(admin, "GET", "/api/v1/partners?limit=1", null, "Idempotency-Key", key + "-" + world.salt);
        }
    }

    @Then("both reads answer 200 and neither is a replay")
    public void readsNotReplay() {
        for (Reply r : history.subList(history.size() - 2, history.size())) {
            assertThat(r.status()).isEqualTo(200);
            assertThat(r.header("Idempotent-Replay")).isNull();
        }
    }

    // --------------------------------------------------------- pagination

    @Given("{int} contract {word} have been registered")
    public void registerMany(int count, String kind) {
        registered.clear();
        for (int i = 0; i < count; i++) {
            switch (kind) {
                case "partners" -> {
                    listingPath = "/api/v1/partners";
                    registered.add(world.partner("CT-PG" + i, RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER));
                }
                case "terminals" -> {
                    listingPath = "/api/v1/terminals";
                    registered.add(world.terminal(RegisterPointRadiusTerminalRequest.CategoryEnum.HUB,
                            "CT-PG" + i, 60.0 + i * 0.1, 60.0));
                }
                case "vehicles" -> {
                    listingPath = "/api/v1/vehicles";
                    registered.add(world.lorry("CTP" + i, 10000));
                }
                case "drivers" -> {
                    listingPath = "/api/v1/drivers";
                    registered.add(world.driver("CTP" + i));
                }
                default -> throw new AssertionError("unknown kind " + kind);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> itemsOf(Reply page) {
        assertThat(page.status()).as("page: " + page).isEqualTo(200);
        return (List<Map<String, Object>>) page.json().get("items");
    }

    private List<Reply> walk(int limit) {
        List<Reply> walked = new ArrayList<>();
        String cursor = null;
        for (int guard = 0; guard < 5000; guard++) {
            String path = listingPath + "?limit=" + limit
                    + (cursor == null ? "" : "&cursor=" + java.net.URLEncoder.encode(cursor,
                    java.nio.charset.StandardCharsets.UTF_8));
            Reply page = support.get(path, admin);
            walked.add(page);
            Object next = page.status() == 200 ? page.json().get("nextCursor") : null;
            if (next == null) {
                return walked;
            }
            cursor = (String) next;
        }
        throw new AssertionError("the walk never ended");
    }

    private List<String> idsOf(List<Reply> walked) {
        List<String> ids = new ArrayList<>();
        walked.forEach(page -> itemsOf(page).forEach(item -> ids.add((String) item.get("id"))));
        return ids;
    }

    @When("the contract administrator walks that listing two at a time")
    public void walkTwo() {
        pages.clear();
        pages.addAll(walk(2));
    }

    @Then("every page but the last holds two items, says there is more and offers a cursor")
    public void fullPages() {
        assertThat(pages.size()).as("five or more resources need three or more pages").isGreaterThanOrEqualTo(3);
        for (Reply page : pages.subList(0, pages.size() - 1)) {
            assertThat(itemsOf(page)).hasSize(2);
            assertThat(page.json().get("hasMore")).isEqualTo(true);
            assertThat(page.json().get("nextCursor")).isInstanceOf(String.class);
            assertThat((String) page.json().get("nextCursor")).isNotBlank();
        }
    }

    @Then("the last page holds one or two items, says there is no more and offers no cursor")
    public void lastPage() {
        Reply last = pages.get(pages.size() - 1);
        assertThat(itemsOf(last).size()).isBetween(1, 2);
        assertThat(last.json().get("hasMore")).isEqualTo(false);
        assertThat(last.json().get("nextCursor")).as("no cursor on the last page").isNull();
    }

    @Then("no resource appears twice and every registered one appears exactly once")
    public void noDuplicates() {
        List<String> ids = idsOf(pages);
        assertThat(ids).doesNotHaveDuplicates();
        for (UUID id : registered) {
            assertThat(ids).filteredOn(id.toString()::equals).hasSize(1);
        }
    }

    @Then("walking with the largest page size yields the same ids in the same order")
    public void sameOrder() {
        List<Reply> big = walk(200);
        assertThat(idsOf(big)).isEqualTo(idsOf(pages));
    }

    @Then("asking again for the cursor of the first page returns the same second page")
    public void stableCursor() {
        String cursor = (String) pages.get(0).json().get("nextCursor");
        Reply again = support.get(listingPath + "?limit=2&cursor="
                + java.net.URLEncoder.encode(cursor, java.nio.charset.StandardCharsets.UTF_8), admin);
        assertThat(itemsOf(again)).isEqualTo(itemsOf(pages.get(1)));
    }

    @When("the contract administrator lists partners with the limit {string}")
    public void listWithLimit(String limit) {
        listingPath = "/api/v1/partners";
        String query = limit.isEmpty() ? "" : "?limit=" + limit;
        send(admin, "GET", "/api/v1/partners" + query, null);
    }

    @Then("the page holds as many partners as the smaller of the total and {int}")
    public void pageSize(int cap) {
        int total = idsOf(walk(200)).size();
        List<Map<String, Object>> items = itemsOf(reply);
        assertThat(items).hasSize(Math.min(total, cap));
        assertThat(reply.json().get("hasMore")).isEqualTo(total > cap);
        if (total > cap) {
            assertThat(reply.json().get("nextCursor")).isNotNull();
        } else {
            assertThat(reply.json().get("nextCursor")).isNull();
        }
    }

    @When("the contract administrator takes the first page of partners two at a time")
    public void firstPageOnly() {
        listingPath = "/api/v1/partners";
        pages.clear();
        pages.add(support.get(listingPath + "?limit=2", admin));
    }

    @When("a new partner is registered before the second page is taken")
    public void registerBetween() {
        registered.add(world.partner("CT-LATE", RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER));
    }

    @Then("finishing the walk shows no partner twice and none of those already listed is lost")
    public void finishWalk() {
        Reply first = pages.get(0);
        List<String> seen = new ArrayList<>(idsOf(List.of(first)));
        String cursor = (String) first.json().get("nextCursor");
        while (cursor != null) {
            Reply page = support.get(listingPath + "?limit=2&cursor="
                    + java.net.URLEncoder.encode(cursor, java.nio.charset.StandardCharsets.UTF_8), admin);
            seen.addAll(idsOf(List.of(page)));
            cursor = (String) page.json().get("nextCursor");
        }
        assertThat(seen).doesNotHaveDuplicates();
        // Everything registered before the walk began is on the first page or
        // after it; the late one may sit on either side of the cursor.
        for (UUID id : registered.subList(0, registered.size() - 1)) {
            assertThat(seen).contains(id.toString());
        }
    }

    // ------------------------------------------------- the served contract

    @When("the contract caller fetches the served contract without a token")
    public void fetchContract() {
        send(null, "GET", "/openapi.yaml", null);
        if (reply.status() == 200) {
            contractDocument = new Yaml().load(reply.body());
        }
    }

    @Then("the contract document is served publicly and is an OpenAPI 3.1 description of the platform")
    @SuppressWarnings("unchecked")
    public void documentServed() {
        assertThat(reply.status()).as("served: " + reply.status()).isEqualTo(200);
        assertThat((String) contractDocument.get("openapi")).startsWith("3.1");
        assertThat(((Map<String, Object>) contractDocument.get("info")).get("title"))
                .isEqualTo("LMS Platform API");
        Map<String, Object> schemes = (Map<String, Object>) ((Map<String, Object>)
                contractDocument.get("components")).get("securitySchemes");
        assertThat(schemes).containsKey("bearerAuth");
        assertThat(((Map<String, Object>) schemes.get("bearerAuth")).get("scheme")).isEqualTo("bearer");
        assertThat(contractDocument.get("security")).as("secure by default").isNotNull();
    }

    @Then("the contract document declares the tag {string} and at least one operation uses it")
    @SuppressWarnings("unchecked")
    public void tagDeclared(String tag) {
        List<Map<String, Object>> tags = (List<Map<String, Object>>) contractDocument.get("tags");
        assertThat(tags).extracting(t -> t.get("name")).contains(tag);
        Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) contractDocument.get("paths");
        long users = paths.values().stream()
                .flatMap(item -> item.entrySet().stream())
                .filter(e -> e.getValue() instanceof Map)
                .map(e -> (Map<String, Object>) e.getValue())
                .filter(op -> op.get("tags") instanceof List<?> l && l.contains(tag))
                .count();
        assertThat(users).as("operations tagged " + tag).isGreaterThan(0);
    }

    @Then("every operation in the contract document has a unique operationId")
    @SuppressWarnings("unchecked")
    public void operationIds() {
        Set<String> ids = new HashSet<>();
        for (var entry : ((Map<String, Map<String, Object>>) contractDocument.get("paths")).entrySet()) {
            for (var op : entry.getValue().entrySet()) {
                if (op.getValue() instanceof Map<?, ?> operation && operation.containsKey("operationId")) {
                    assertThat(ids.add((String) operation.get("operationId")))
                            .as("duplicate operationId on " + op.getKey() + " " + entry.getKey()).isTrue();
                }
            }
        }
        assertThat(ids).hasSizeGreaterThan(150);
    }

    @Then("only GET is public on the contract path")
    public void onlyGetPublic() {
        Reply post = support.post("/openapi.yaml", null, "{}");
        assertThat(post.status()).as("POST to the public contract path").isEqualTo(401);
    }

    @Then("neither {string} nor {string} is served without a token")
    public void notPublic(String a, String b) {
        for (String path : List.of(a, b)) {
            Reply r = support.get(path, null);
            assertThat(r.status()).as(path).isEqualTo(401);
        }
    }

    @SuppressWarnings("unchecked")
    @When("the contract administrator probes every documented operation")
    public void probeEverything() {
        fetchContract();
        Map<String, Object> components = (Map<String, Object>) contractDocument.get("components");
        Map<String, Object> parameters = (Map<String, Object>) components.get("parameters");
        Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) contractDocument.get("paths");
        probed = 0;
        probeFailures.clear();
        for (var entry : new LinkedHashMap<>(paths).entrySet()) {
            for (String method : List.of("get", "post", "put", "delete")) {
                if (!(entry.getValue().get(method) instanceof Map<?, ?> operation)) {
                    continue;
                }
                String key = method.toUpperCase(Locale.ROOT) + " " + entry.getKey();
                if (NOT_PROBED.contains(key)) {
                    continue;
                }
                List<Map<String, Object>> declared = new ArrayList<>();
                declared.addAll((List<Map<String, Object>>) entry.getValue().getOrDefault("parameters", List.of()));
                declared.addAll((List<Map<String, Object>>) ((Map<String, Object>) operation)
                        .getOrDefault("parameters", List.of()));
                String path = pathFor(entry.getKey()) + requiredQuery(declared, parameters);
                Reply r = support.send(method.toUpperCase(Locale.ROOT), path, admin,
                        method.equals("get") || method.equals("delete") ? null : "{}");
                probed++;
                boolean bareNotFound = r.status() == 404 && !(r.isProblem() && "not-found".equals(r.code()));
                if (r.status() == 405 || bareNotFound || r.status() >= 500) {
                    probeFailures.add(key + " answered " + r);
                }
            }
        }
    }

    private static String pathFor(String template) {
        return template.replaceAll("\\{(seq|sequence)}", "1")
                .replace("{trackingRef}", "NO-SUCH-REF")
                .replaceAll("\\{[A-Za-z]+}", UUID.randomUUID().toString());
    }

    @SuppressWarnings("unchecked")
    private static String requiredQuery(List<Map<String, Object>> declared, Map<String, Object> shared) {
        StringBuilder query = new StringBuilder();
        for (Map<String, Object> parameter : declared) {
            Map<String, Object> p = parameter;
            if (p.containsKey("$ref")) {
                String ref = (String) p.get("$ref");
                p = (Map<String, Object>) shared.get(ref.substring(ref.lastIndexOf('/') + 1));
            }
            if (!"query".equals(p.get("in")) || !Boolean.TRUE.equals(p.get("required"))) {
                continue;
            }
            Map<String, Object> schema = (Map<String, Object>) p.get("schema");
            String value;
            if ("uuid".equals(schema.get("format"))) {
                value = UUID.randomUUID().toString();
            } else if ("date".equals(schema.get("format"))) {
                value = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).toString();
            } else if ("date-time".equals(schema.get("format"))) {
                value = Instant.now().toString();
            } else if ("integer".equals(schema.get("type"))) {
                value = "1";
            } else if (schema.get("enum") instanceof List<?> values) {
                value = String.valueOf(values.get(0));
            } else {
                value = "x";
            }
            query.append(query.length() == 0 ? "?" : "&").append(p.get("name")).append('=').append(value);
        }
        return query.toString();
    }

    @Then("every probed operation is routed: none answers 405, a bare 404 or a server error")
    public void everythingRouted() {
        assertThat(probeFailures).as("operations that are not routed").isEmpty();
        assertThat(probed).as("operations probed").isGreaterThan(140);
    }

    // ----------------------------------------------------------- actuator

    @Then("the health endpoint {string} is public and reports UP")
    public void healthPublic(String path) {
        Reply r = support.get(path, null);
        assertThat(r.status()).as(path + ": " + r).isEqualTo(200);
        assertThat(r.json().get("status")).isEqualTo("UP");
    }

    @Then("the actuator path {string} is refused without a token")
    public void actuatorRefused(String path) {
        Reply r = support.get(path, null);
        assertThat(r.status()).as(path + ": " + r).isEqualTo(401);
        assertThat(r.header("WWW-Authenticate")).startsWith("Bearer");
        assertThat(r.isProblem()).as("a problem document: " + r).isTrue();
        assertThat(r.code()).isEqualTo("authentication-required");
        assertThat(r.json().get("detail")).as("nothing leaks to an anonymous caller")
                .isEqualTo("This operation requires authentication");
    }

    @Then("the actuator path {string} is not exposed even to the administrator")
    public void notExposed(String path) {
        Reply r = support.get(path, admin);
        assertThat(r.status()).as(path + " must not be served: " + r).isEqualTo(404);
        assertThat(r.isProblem()).as("a problem document: " + r).isTrue();
        assertThat(r.code()).isEqualTo("not-found");
        assertThat(r.body()).doesNotContain("\"propertySources\"").doesNotContain("\"names\"");
    }

    @Then("the administrator sees only the health and info actuator endpoints")
    @SuppressWarnings("unchecked")
    public void exposedEndpoints() {
        Reply r = support.get("/actuator", admin);
        assertThat(r.status()).as("answer: " + r).isEqualTo(200);
        Map<String, Object> links = (Map<String, Object>) r.json().get("_links");
        Set<String> names = new LinkedHashSet<>(links.keySet());
        names.removeIf(name -> name.equals("self") || name.startsWith("health"));
        assertThat(names).containsExactly("info");
        assertThat(links).containsKey("health");
    }

    /**
     * A token that claims to need no signature (alg none, empty signature part).
     * Built from readable JSON rather than written as a literal: it is a
     * fixture, not a credential, and a JWT-shaped string in source is what
     * secret scanners exist to flag.
     */
    private static String unsignedToken() {
        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        String header = "{\"alg\":\"none\"}";
        String claims = "{\"sub\":\"x\",\"tenant\":\"x\",\"perms\":[\"PARTNER_READ\"]}";
        return base64.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
                + base64.encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".";
    }
}
