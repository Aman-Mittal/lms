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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.lms.acceptance.TenantSeeds;
import com.lms.acceptance.TenantSeeds.Seeded;
import com.lms.acceptance.TestFixtures;
import com.lms.identity.security.TokenService;
import com.lms.web.ApiTestKit.Outcome;
import com.lms.web.ApiTestKit.Tenant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A proof of delivery fed line by line over HTTP with real permissions: the
 * draft is started once however often it is asked for, lines, documents and
 * times are fed and refused as they should be, a submission meets the
 * tenant's policy, paper is keyed only with its own right and says who keyed
 * it, and a submitted proof is reversed only through an approval.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LineLevelProofApiTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.7-alpine")
            .withDatabaseName("lms").withUsername("lms").withPassword("lms");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("lms.delivery.expose-otp", () -> "true");
    }

    @LocalServerPort
    private int port;
    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private TokenService tokens;
    @Autowired
    private TenantSeeds seeds;
    @Autowired
    private JdbcClient jdbc;

    private ApiTestKit kit;
    private String[] everything;
    private Tenant callerA;
    private Tenant approver;
    private Tenant callerB;
    private Seeded a;
    private UUID consignment;
    private UUID trip;
    private UUID bill;
    private List<UUID> orderLines;

    @BeforeAll
    void tenants() {
        kit = new ApiTestKit(fixtures, tokens, port);
        everything = seeds.allPermissions().toArray(String[]::new);
        a = seeds.seed(0.0);
        Seeded b = seeds.seed(1.0);
        callerA = kit.tenantOf(a.tenantId(), a.orgUnitId(), a.orgPath());
        approver = kit.tenantOf(a.tenantId(), a.orgUnitId(), a.orgPath());
        callerB = kit.tenantOf(b.tenantId(), b.orgUnitId(), b.orgPath());
        consignment = a.id("consignment");
        trip = a.id("trip");
        bill = a.id("freight_bill");
        orderLines = jdbc.sql("SELECT order_line_id FROM consignment_line WHERE consignment_id = :c ORDER BY 1")
                .param("c", consignment).query(UUID.class).list();
        jdbc.sql("UPDATE settlement_hold SET released_at = now() WHERE tenant_id = :t")
                .param("t", a.tenantId()).update();
    }

    @BeforeEach
    void inTransitAndHeld() {
        UUID t = a.tenantId();
        jdbc.sql("DELETE FROM approval_request WHERE tenant_id = :t AND subject_type = 'POD_REVERSAL'")
                .param("t", t).update();
        jdbc.sql("DELETE FROM delivery_proof WHERE tenant_id = :t").param("t", t).update();
        jdbc.sql("DELETE FROM settlement_hold WHERE tenant_id = :t AND reason = 'PROOF_OF_DELIVERY'")
                .param("t", t).update();
        jdbc.sql("UPDATE freight_bill SET status = 'DRAFT' WHERE tenant_id = :t").param("t", t).update();
        jdbc.sql("UPDATE consignment SET status = 'IN_TRANSIT' WHERE id = :c").param("c", consignment).update();
        jdbc.sql("""
                        INSERT INTO settlement_hold (tenant_id, exception_id, trip_id, ticket_no, placed_at, reason)
                        VALUES (:t, :hold, :trip, 'PROOF-OF-DELIVERY', now(), 'PROOF_OF_DELIVERY')
                        """)
                .param("t", t)
                .param("hold", UUID.nameUUIDFromBytes(("proof-of-delivery:" + trip)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .param("trip", trip)
                .update();
    }

    // ------------------------------------------------------------- helpers

    private String pod(String suffix) {
        return "/api/v1/deliveries/" + consignment + "/pod" + suffix;
    }

    private void ensure() {
        expect("ensure", kit.post(callerA, pod(""), "{}", everything), 204, null);
    }

    private JsonNode view() {
        Outcome got = kit.get(callerA, "/api/v1/deliveries/" + consignment, everything);
        assertThat(got.status()).as(got.body()).isEqualTo(200);
        return kit.parse(got);
    }

    private String otp() {
        Outcome issued = kit.post(callerA, "/api/v1/deliveries/" + consignment + "/otp", "{}", everything);
        assertThat(issued.status()).as(issued.body()).isEqualTo(200);
        return kit.parse(issued).get("otp").asString();
    }

    private String lineBody(UUID line, String quantities) {
        return "{\"orderLineId\":\"" + line + "\"," + quantities + "}";
    }

    private Outcome feed(String body, String... permissions) {
        return kit.put(callerA, pod("/lines"), "{\"lines\":[" + body + "]}", permissions);
    }

    private String consignmentStatus() {
        return kit.parse(kit.get(callerA, "/api/v1/consignments/" + consignment, everything))
                .get("status").asString();
    }

    private boolean awaitingProof() {
        return kit.parse(kit.get(callerA, "/api/v1/freight-bills/" + bill, everything))
                .get("awaitingProof").asBoolean();
    }

    private long audited(String action) {
        return jdbc.sql("SELECT count(*) FROM audit_log WHERE action = :a AND tenant_id = :t")
                .param("a", action).param("t", a.tenantId()).query(Long.class).single();
    }

    private static boolean absent(JsonNode node, String field) {
        return !node.has(field) || node.get(field).isNull();
    }

    private static void expect(String what, Outcome outcome, int status, String code) {
        assertThat(outcome.status()).as(what + ": " + outcome.body()).isEqualTo(status);
        if (code != null) {
            assertThat(outcome.code()).as(what).isEqualTo(code);
        }
    }

    private String[] without(String permission) {
        return Arrays.stream(everything).filter(p -> !p.equals(permission)).toArray(String[]::new);
    }

    // --------------------------------------------------------------- ensure

    @Test
    @DisplayName("ensuring a proof starts one draft however often it is asked, and shows every order line")
    void ensureIsIdempotent() {
        long before = audited("DELIVERY_POD_STARTED");
        expect("without the right", kit.post(callerA, pod(""), "{}", without("POD_RECORD")), 403, null);
        expect("another tenant's consignment", kit.post(callerB, pod(""), "{}", everything), 404, null);
        ensure();
        ensure();

        JsonNode draft = view();
        assertThat(draft.get("status").asString()).isEqualTo("PENDING");
        assertThat(draft.get("paper").asBoolean()).isFalse();
        assertThat(draft.get("documents")).isEmpty();
        assertThat(draft.get("lines").size()).isEqualTo(orderLines.size());
        assertThat(draft.get("lines").get(0).get("grossQty").decimalValue().signum()).isPositive();
        assertThat(absent(draft.get("lines").get(0), "receivedQty")).as("not fed yet").isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM delivery_proof WHERE consignment_id = :c")
                .param("c", consignment).query(Long.class).single()).isEqualTo(1L);
        assertThat(audited("DELIVERY_POD_STARTED")).as("started once").isEqualTo(before + 1);

        // A code issued on the draft turns it into the code route's proof; ensuring changes nothing.
        otp();
        ensure();
        assertThat(view().get("status").asString()).isEqualTo("OTP_ISSUED");
    }

    @Test
    @DisplayName("a draft cannot be fed for a consignment that has no proof started")
    void nothingToFeed() {
        expect("no draft", feed(lineBody(orderLines.get(0), "\"receivedQty\":1"), everything), 404, null);
        expect("no draft for times", kit.put(callerA, pod("/timings"), "{}", everything), 404, null);
    }

    // ---------------------------------------------------------------- lines

    @Test
    @DisplayName("lines are replaced as a whole and refused when unknown, repeated or negative")
    void feedingLines() {
        ensure();
        UUID line = orderLines.get(0);
        expect("without the right", feed(lineBody(line, "\"receivedQty\":1"), without("POD_RECORD")), 403, null);
        expect("an unknown line", feed(lineBody(UUID.randomUUID(), "\"receivedQty\":1"), everything),
                409, "pod-line-unknown");
        expect("a repeated line", feed(lineBody(line, "\"receivedQty\":1") + "," + lineBody(line,
                "\"shortQty\":1"), everything), 409, "pod-line-duplicate");
        expect("a negative quantity", feed(lineBody(line, "\"receivedQty\":-1"), everything), 400, null);
        expect("too fine a quantity", feed(lineBody(line, "\"receivedQty\":1.2345"), everything), 409,
                "pod-quantity-invalid");

        expect("fed", feed(lineBody(line, "\"receivedQty\":2,\"shortQty\":1,\"damagedQty\":0.5,\"excessQty\":0"),
                everything), 204, null);
        JsonNode fed = view().get("lines").get(0);
        assertThat(fed.get("receivedQty").decimalValue()).isEqualByComparingTo("2");
        assertThat(fed.get("shortQty").decimalValue()).isEqualByComparingTo("1");
        assertThat(fed.get("damagedQty").decimalValue()).isEqualByComparingTo("0.5");

        expect("replaced by nothing", kit.put(callerA, pod("/lines"), "{\"lines\":[]}", everything), 204, null);
        assertThat(absent(view().get("lines").get(0), "receivedQty")).isTrue();
    }

    // ------------------------------------------------------- documents, times

    @Test
    @DisplayName("documents are a type and a reference, replaced as a list and kept in order")
    void documents() {
        ensure();
        expect("a document with no reference", kit.put(callerA, pod("/documents"),
                "{\"documents\":[{\"type\":\"FRONT\",\"reference\":\" \"}]}", everything), 400, null);
        expect("an unknown type", kit.put(callerA, pod("/documents"),
                "{\"documents\":[{\"type\":\"SIDE\",\"reference\":\"x\"}]}", everything), 400, null);
        expect("listed", kit.put(callerA, pod("/documents"),
                "{\"documents\":[{\"type\":\"BACK\",\"reference\":\"store://pod/2\"},"
                        + "{\"type\":\"FRONT\",\"reference\":\"store://pod/1\"}]}", everything), 204, null);
        JsonNode docs = view().get("documents");
        assertThat(docs.size()).isEqualTo(2);
        assertThat(docs.get(0).get("type").asString()).isEqualTo("BACK");
        assertThat(docs.get(1).get("reference").asString()).isEqualTo("store://pod/1");
        expect("replaced", kit.put(callerA, pod("/documents"), "{\"documents\":[]}", everything), 204, null);
        assertThat(view().get("documents")).isEmpty();
    }

    @Test
    @DisplayName("times must run reporting, unloading start, unloading end, none after now")
    void timings() {
        ensure();
        Instant now = Instant.now();
        String early = now.minusSeconds(3600).toString();
        String middle = now.minusSeconds(1800).toString();
        String late = now.minusSeconds(600).toString();
        expect("start before reporting", kit.put(callerA, pod("/timings"), "{\"reportedAt\":\"" + middle
                + "\",\"unloadingStartedAt\":\"" + early + "\"}", everything), 409, "pod-timing-order-invalid");
        expect("end before start", kit.put(callerA, pod("/timings"), "{\"unloadingStartedAt\":\"" + late
                + "\",\"unloadingEndedAt\":\"" + middle + "\"}", everything), 409, "pod-timing-order-invalid");
        expect("end before reporting, start left out", kit.put(callerA, pod("/timings"), "{\"reportedAt\":\""
                + late + "\",\"unloadingEndedAt\":\"" + early + "\"}", everything), 409,
                "pod-timing-order-invalid");
        expect("in the future", kit.put(callerA, pod("/timings"), "{\"reportedAt\":\""
                + now.plusSeconds(3600) + "\"}", everything), 409, "pod-timing-in-future");

        expect("in order", kit.put(callerA, pod("/timings"), "{\"reportedAt\":\"" + early
                + "\",\"unloadingStartedAt\":\"" + middle + "\",\"unloadingEndedAt\":\"" + late + "\"}",
                everything), 204, null);
        JsonNode proof = view();
        assertThat(Instant.parse(proof.get("reportedAt").asString()))
                .isCloseTo(Instant.parse(early), org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MILLIS));
        assertThat(Instant.parse(proof.get("unloadingEndedAt").asString()))
                .isCloseTo(Instant.parse(late), org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MILLIS));

        expect("equal times are in order", kit.put(callerA, pod("/timings"), "{\"reportedAt\":\"" + middle
                + "\",\"unloadingStartedAt\":\"" + middle + "\"}", everything), 204, null);
        expect("cleared", kit.put(callerA, pod("/timings"), "{}", everything), 204, null);
        assertThat(absent(view(), "reportedAt")).isTrue();
    }

    // --------------------------------------------------------------- submit

    @Test
    @DisplayName("submitting a fed draft delivers the consignment; a shortage in the lines needs a remark and keeps the hold")
    void submitWithShortage() {
        ensure();
        UUID line = orderLines.get(0);
        BigDecimalHolder gross = new BigDecimalHolder(view().get("lines").get(0).get("grossQty").decimalValue());
        feed(lineBody(line, "\"receivedQty\":" + gross.minus(1) + ",\"shortQty\":1"), everything);

        String code = otp();
        expect("wrong code", kit.post(callerA, pod("/submit"),
                "{\"otp\":\"000000\",\"recipientName\":\"R\"}", everything), 409, "otp-invalid");
        expect("a short line needs a remark", kit.post(callerA, pod("/submit"),
                "{\"otp\":\"" + code + "\",\"recipientName\":\"R\"}", everything), 409,
                "proof-remark-required");
        expect("submitted", kit.post(callerA, pod("/submit"), "{\"otp\":\"" + code
                + "\",\"recipientName\":\"R. Consignee\",\"remark\":\"One unit short\"}", everything),
                204, null);

        JsonNode proof = view();
        assertThat(proof.get("status").asString()).isEqualTo("SUBMITTED");
        assertThat(proof.get("goodsCondition").asString()).as("derived from the lines").isEqualTo("SHORTAGE");
        assertThat(proof.get("paper").asBoolean()).isFalse();
        assertThat(consignmentStatus()).isEqualTo("DELIVERED");
        assertThat(awaitingProof()).as("a shortage is for a person to accept").isTrue();

        expect("fed after submission", feed(lineBody(line, "\"receivedQty\":1"), everything), 409,
                "proof-already-recorded");
        expect("documents after submission", kit.put(callerA, pod("/documents"), "{\"documents\":[]}",
                everything), 409, "proof-already-recorded");
        expect("ensured again", kit.post(callerA, pod(""), "{}", everything), 204, null);
        assertThat(view().get("status").asString()).isEqualTo("SUBMITTED");
    }

    @Test
    @DisplayName("a clean submission lifts the proof hold and shows on the consignment")
    void cleanSubmission() {
        ensure();
        String code = otp();
        expect("submitted", kit.post(callerA, pod("/submit"),
                "{\"otp\":\"" + code + "\",\"recipientName\":\"R\"}", everything), 204, null);
        assertThat(consignmentStatus()).isEqualTo("DELIVERED");
        assertThat(awaitingProof()).isFalse();
        JsonNode consignmentView = kit.parse(kit.get(callerA, "/api/v1/consignments/" + consignment, everything));
        assertThat(consignmentView.get("proofStatus").asString()).isEqualTo("SUBMITTED");
        expect("again", kit.post(callerA, pod("/submit"),
                "{\"otp\":\"" + code + "\",\"recipientName\":\"R\"}", everything), 409,
                "proof-already-recorded");
    }

    // --------------------------------------------------------------- paper

    @Test
    @DisplayName("paper is keyed only with its own right, with no code, and records who keyed it")
    void paperProof() {
        long before = audited("DELIVERY_POD_PAPER_KEYED");
        String body = "{\"recipientName\":\"R. Consignee\"}";
        expect("without the paper right", kit.post(callerA, pod("/paper"), body, without("POD_PAPER_KEY")),
                403, null);
        expect("the receiving right is not enough", kit.post(callerA, pod("/paper"), body,
                "POD_RECORD", "POD_READ"), 403, null);
        expect("another tenant's consignment", kit.post(callerB, pod("/paper"), body, everything), 404, null);
        expect("no recipient", kit.post(callerA, pod("/paper"), "{\"recipientName\":\" \"}", everything), 400, null);

        expect("keyed with no draft started", kit.post(callerA, pod("/paper"), body, everything), 204, null);
        JsonNode proof = view();
        assertThat(proof.get("status").asString()).isEqualTo("SUBMITTED");
        assertThat(proof.get("paper").asBoolean()).isTrue();
        assertThat(proof.get("keyedBy").asString()).isEqualTo(callerA.user().id().toString());
        assertThat(consignmentStatus()).isEqualTo("DELIVERED");
        assertThat(awaitingProof()).isFalse();
        assertThat(audited("DELIVERY_POD_PAPER_KEYED")).isEqualTo(before + 1);
        assertThat(jdbc.sql("""
                        SELECT actor_id FROM audit_log
                         WHERE action = 'DELIVERY_POD_PAPER_KEYED' AND tenant_id = :t
                         ORDER BY occurred_at DESC LIMIT 1
                        """).param("t", a.tenantId()).query(UUID.class).single()).isEqualTo(callerA.user().id());
        expect("keyed twice", kit.post(callerA, pod("/paper"), body, everything), 409, "proof-already-recorded");
    }

    @Test
    @DisplayName("a paper proof of damage needs a remark like any other")
    void paperDamage() {
        expect("no remark", kit.post(callerA, pod("/paper"),
                "{\"recipientName\":\"R\",\"goodsCondition\":\"DAMAGE\"}", everything), 409,
                "proof-remark-required");
        expect("with a remark", kit.post(callerA, pod("/paper"),
                "{\"recipientName\":\"R\",\"goodsCondition\":\"DAMAGE\",\"remark\":\"Crushed\"}",
                everything), 204, null);
        assertThat(awaitingProof()).isTrue();
    }

    // --------------------------------------------------------- not received

    @Test
    @DisplayName("not received proves nothing, needs a remark, and the draft can still be submitted")
    void notReceived() {
        ensure();
        expect("no remark", kit.post(callerA, pod("/not-received"), "{\"remark\":\" \"}", everything), 400, null);
        expect("recorded", kit.post(callerA, pod("/not-received"), "{\"remark\":\"Gate closed\"}", everything),
                204, null);
        assertThat(view().get("status").asString()).isEqualTo("NOT_RECEIVED");
        assertThat(consignmentStatus()).isEqualTo("IN_TRANSIT");
        assertThat(awaitingProof()).isTrue();

        expect("paper keyed after all", kit.post(callerA, pod("/paper"), "{\"recipientName\":\"R\"}", everything),
                204, null);
        assertThat(view().get("status").asString()).isEqualTo("SUBMITTED");
        assertThat(consignmentStatus()).isEqualTo("DELIVERED");
    }

    // ------------------------------------------------------------- reversal

    private Outcome decide(Tenant caller, UUID id, String verb) throws Exception {
        JsonNode detail = kit.parse(kit.get(caller, "/api/v1/approvals/" + id, everything));
        long version = detail.get("approval").get("version").asLong();
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/approvals/" + id + "/" + verb))
                .header("Content-Type", "application/json")
                .header("If-Match", "\"" + version + "\"")
                .header("Authorization", "Bearer " + kit.token(caller, everything))
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request,
                HttpResponse.BodyHandlers.ofString());
        return new Outcome(response.statusCode(), response.body(), 0);
    }

    @Test
    @DisplayName("a submitted proof is reversed only through an approval, which holds the trip again; billed is refused")
    void reversal() throws Exception {
        expect("paper keyed", kit.post(callerA, pod("/paper"), "{\"recipientName\":\"R\"}", everything), 204, null);
        assertThat(awaitingProof()).isFalse();

        Outcome asked = kit.post(callerA, "/api/v1/deliveries/" + consignment + "/proof/reversal",
                "{\"reason\":\"Signed by the wrong person\"}", everything);
        expect("asked", asked, 202, null);
        UUID id = UUID.fromString(kit.parse(asked).get("id").asString());
        assertThat(view().get("status").asString()).as("nothing changes while it waits").isEqualTo("SUBMITTED");

        jdbc.sql("UPDATE freight_bill SET status = 'APPROVED_FOR_PAYMENT' WHERE id = :b").param("b", bill).update();
        expect("approved after billing", decide(approver, id, "approve"), 409, "pod-already-billed");
        assertThat(view().get("status").asString()).isEqualTo("SUBMITTED");
        jdbc.sql("UPDATE freight_bill SET status = 'DRAFT' WHERE id = :b").param("b", bill).update();

        // A completed trip is what a reversal holds again.
        String tripStatus = jdbc.sql("SELECT status FROM trip WHERE id = :t").param("t", trip)
                .query(String.class).single();
        jdbc.sql("UPDATE trip SET status = 'COMPLETED' WHERE id = :t").param("t", trip).update();
        try {
            expect("approved", decide(approver, id, "approve"), 204, null);
        } finally {
            jdbc.sql("UPDATE trip SET status = :s WHERE id = :t").param("s", tripStatus).param("t", trip).update();
        }
        assertThat(view().get("status").asString()).isEqualTo("REVERSED");
        assertThat(consignmentStatus()).as("a completed trip delivered it").isEqualTo("DELIVERED");
        assertThat(awaitingProof()).as("the hold is applied again").isTrue();
        assertThat(audited("DELIVERY_PROOF_REVERSED")).isGreaterThanOrEqualTo(1L);

        expect("fed again after reversal", kit.put(callerA, pod("/timings"), "{}", everything), 204, null);
        expect("a refreshed submission", kit.post(callerA, pod("/paper"), "{\"recipientName\":\"R2\"}",
                everything), 204, null);
        assertThat(view().get("recipientName").asString()).isEqualTo("R2");
    }

    @Test
    @DisplayName("a draft that was never submitted has nothing to reverse")
    void nothingToReverse() {
        ensure();
        expect("pending", kit.post(callerA, "/api/v1/deliveries/" + consignment + "/proof/reversal",
                "{\"reason\":\"x\"}", everything), 409, "proof-not-recorded");
    }

    // ---------------------------------------------------------------- policy

    @Test
    @DisplayName("the tenant's POD settings require lines and documents and bound the quantity that arrives")
    void policyIsEnforced() {
        Seeded c = seeds.seed(2.0);
        Tenant callerC = kit.tenantOf(c.tenantId(), c.orgUnitId(), c.orgPath());
        UUID cons = c.id("consignment");
        jdbc.sql("UPDATE consignment SET status = 'IN_TRANSIT' WHERE id = :c").param("c", cons).update();
        String base = "/api/v1/deliveries/" + cons + "/pod";
        UUID line = jdbc.sql("SELECT order_line_id FROM consignment_line WHERE consignment_id = :c")
                .param("c", cons).query(UUID.class).list().get(0);
        java.math.BigDecimal gross = jdbc.sql("SELECT quantity FROM order_line WHERE id = :l").param("l", line)
                .query(java.math.BigDecimal.class).single();

        // Lenient by default: a bare submission needs nothing fed.
        expect("ensure", kit.post(callerC, base, "{}", everything), 204, null);
        String lenient = otp(callerC, cons);
        expect("submitted bare", kit.post(callerC, base + "/submit",
                "{\"otp\":\"" + lenient + "\",\"recipientName\":\"R\"}", everything), 204, null);
        jdbc.sql("DELETE FROM delivery_proof WHERE consignment_id = :c").param("c", cons).update();
        jdbc.sql("UPDATE consignment SET status = 'IN_TRANSIT' WHERE id = :c").param("c", cons).update();

        expect("profile", kit.post(callerC, "/api/v1/settings/profiles", """
                {"scopeType":"ORG_DEFAULT","values":{"delivery.pod.feedingRequired":true,
                 "delivery.pod.frontDocumentRequired":true,"delivery.pod.backDocumentRequired":true,
                 "delivery.pod.quantityTolerancePercent":10}}
                """, everything), 201, null);

        expect("ensure", kit.post(callerC, base, "{}", everything), 204, null);
        String code = otp(callerC, cons);
        String submit = "{\"otp\":\"" + code + "\",\"recipientName\":\"R\"}";
        expect("no lines", kit.post(callerC, base + "/submit", submit, everything), 409, "pod-lines-required");

        // 50% of the gross arrived: outside a 10% tolerance.
        String half = gross.divide(java.math.BigDecimal.valueOf(2), 3, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
        expect("fed half", kit.put(callerC, base + "/lines", "{\"lines\":[{\"orderLineId\":\"" + line
                + "\",\"receivedQty\":" + half + "}]}", everything), 204, null);
        expect("outside tolerance", kit.post(callerC, base + "/submit", submit, everything), 409,
                "pod-quantity-outside-tolerance");

        expect("fed the lot", kit.put(callerC, base + "/lines", "{\"lines\":[{\"orderLineId\":\"" + line
                + "\",\"receivedQty\":" + gross.stripTrailingZeros().toPlainString() + "}]}", everything),
                204, null);
        expect("no front", kit.post(callerC, base + "/submit", submit, everything), 409,
                "pod-front-document-required");
        expect("front only", kit.put(callerC, base + "/documents",
                "{\"documents\":[{\"type\":\"FRONT\",\"reference\":\"store://f\"}]}", everything), 204, null);
        expect("no back", kit.post(callerC, base + "/submit", submit, everything), 409,
                "pod-back-document-required");
        expect("both", kit.put(callerC, base + "/documents",
                "{\"documents\":[{\"type\":\"FRONT\",\"reference\":\"store://f\"},"
                        + "{\"type\":\"BACK\",\"reference\":\"store://b\"}]}", everything), 204, null);
        expect("submitted", kit.post(callerC, base + "/submit", submit, everything), 204, null);
        assertThat(kit.parse(kit.get(callerC, "/api/v1/deliveries/" + cons, everything)).get("status").asString())
                .isEqualTo("SUBMITTED");
    }

    private String otp(Tenant caller, UUID cons) {
        Outcome issued = kit.post(caller, "/api/v1/deliveries/" + cons + "/otp", "{}", everything);
        assertThat(issued.status()).as(issued.body()).isEqualTo(200);
        return kit.parse(issued).get("otp").asString();
    }

    /** A decimal that prints without trailing zeros. */
    private record BigDecimalHolder(java.math.BigDecimal value) {

        String minus(int n) {
            return value.subtract(java.math.BigDecimal.valueOf(n)).stripTrailingZeros().toPlainString();
        }
    }
}
