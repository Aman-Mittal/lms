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
 * Proof of delivery and claims over HTTP, with real permissions.
 *
 * <p>Each seeded tenant has one consignment, so every test starts by putting
 * that consignment back in transit with no proof and no claims. The database
 * is reached directly (as the owner) for exactly two things a caller cannot do:
 * resetting state, and ageing a code past its expiry.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeliveryApiTest {

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
        // A demo deployment hands the code back; these tests need it to answer.
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
    private Tenant callerB;
    private Seeded a;
    private UUID consignment;

    @BeforeAll
    void twoTenants() {
        kit = new ApiTestKit(fixtures, tokens, port);
        everything = seeds.allPermissions().toArray(String[]::new);
        a = seeds.seed(0.0);
        Seeded b = seeds.seed(1.0);
        callerA = kit.tenantOf(a.tenantId(), a.orgUnitId(), a.orgPath());
        callerB = kit.tenantOf(b.tenantId(), b.orgUnitId(), b.orgPath());
        consignment = a.id("consignment");
    }

    @BeforeEach
    void freshConsignment() {
        jdbc.sql("DELETE FROM claim WHERE tenant_id = :t").param("t", a.tenantId()).update();
        jdbc.sql("DELETE FROM delivery_proof WHERE tenant_id = :t").param("t", a.tenantId()).update();
        jdbc.sql("UPDATE consignment SET status = 'IN_TRANSIT' WHERE id = :c")
                .param("c", consignment).update();
    }

    // ------------------------------------------------------------- helpers

    private String otp() {
        Outcome issued = kit.post(callerA, "/api/v1/deliveries/" + consignment + "/otp", "{}", everything);
        assertThat(issued.status()).as(issued.body()).isEqualTo(200);
        String code = kit.parse(issued).get("otp").asString();
        assertThat(code).matches("\\d{6}");
        return code;
    }

    private Outcome prove(String otp) {
        return kit.post(callerA, "/api/v1/deliveries/" + consignment + "/proof",
                "{\"otp\":\"" + otp + "\",\"recipientName\":\"R. Consignee\","
                        + "\"photoRef\":\"s3://pod/photo-1.jpg\",\"lat\":19.1,\"lon\":72.9}", everything);
    }

    private static String wrongCodeFor(String right) {
        return right.equals("000000") ? "111111" : "000000";
    }

    private UUID raise(String type, String amount) {
        Outcome raised = raiseOutcome(type, amount);
        assertThat(raised.status()).as(raised.body()).isEqualTo(201);
        return UUID.fromString(kit.parse(raised).get("id").asString());
    }

    private Outcome raiseOutcome(String type, String amount) {
        return kit.post(callerA, "/api/v1/claims", "{\"consignmentId\":\"" + consignment
                + "\",\"claimType\":\"" + type + "\",\"amount\":" + amount
                + ",\"description\":\"Pallet crushed at the door\"}", everything);
    }

    private Outcome move(UUID claim, String target, String extra) {
        return kit.post(callerA, "/api/v1/claims/" + claim + "/transitions",
                "{\"target\":\"" + target + "\"" + extra + "}", everything);
    }

    private void expect(String what, Outcome outcome, int status, String code) {
        assertThat(outcome.status()).as(what + ": " + outcome.body()).isEqualTo(status);
        if (code != null) {
            assertThat(outcome.code()).as(what).isEqualTo(code);
        }
    }

    // ------------------------------------------------------------- proof

    @Test
    @DisplayName("a delivery is proved with the code, once, and the proof can be read back")
    void proofLifecycle() {
        String code = otp();

        expect("a wrong code", prove(wrongCodeFor(code)), 409, "otp-invalid");
        expect("the right code", prove(code), 204, null);

        Outcome got = kit.get(callerA, "/api/v1/deliveries/" + consignment, everything);
        assertThat(got.status()).isEqualTo(200);
        JsonNode proof = kit.parse(got);
        assertThat(proof.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(proof.get("recipientName").asString()).isEqualTo("R. Consignee");
        assertThat(proof.get("photoRef").asString()).isEqualTo("s3://pod/photo-1.jpg");
        assertThat(got.body()).as("the code and its hash are never shown").doesNotContain("otp_hash", "otpHash");

        expect("a second proof", prove(code), 409, "proof-already-recorded");
        expect("a new code after proof",
                kit.post(callerA, "/api/v1/deliveries/" + consignment + "/otp", "{}", everything),
                409, "proof-already-recorded");
    }

    @Test
    @DisplayName("three wrong tries lock the code, and a new code starts again")
    void lockout() {
        String code = otp();
        String wrong = wrongCodeFor(code);
        for (int i = 0; i < 3; i++) {
            expect("wrong try " + (i + 1), prove(wrong), 409, "otp-invalid");
        }
        expect("the right code after three wrong ones", prove(code), 409, "otp-invalid");

        String fresh = otp();
        expect("the new code", prove(fresh), 204, null);
    }

    @Test
    @DisplayName("an expired code, a wrong one and a code nobody issued are refused alike")
    void refusalsAreIndistinguishable() {
        Outcome neverIssued = prove("123456");
        expect("never issued", neverIssued, 409, "otp-invalid");

        String code = otp();
        Outcome wrong = prove(wrongCodeFor(code));

        String expired = otp();
        jdbc.sql("UPDATE delivery_proof SET otp_expires_at = now() - interval '1 minute' WHERE consignment_id = :c")
                .param("c", consignment).update();
        Outcome late = prove(expired);

        expect("wrong", wrong, 409, "otp-invalid");
        expect("expired", late, 409, "otp-invalid");
        assertThat(kit.parse(late).get("detail").asString())
                .as("the answer does not say which of the three it was")
                .isEqualTo(kit.parse(wrong).get("detail").asString())
                .isEqualTo(kit.parse(neverIssued).get("detail").asString());
    }

    @Test
    @DisplayName("only a consignment that has left can be proved delivered")
    void notBeforeItLeaves() {
        jdbc.sql("UPDATE consignment SET status = 'PLANNED' WHERE id = :c").param("c", consignment).update();

        expect("issue before it leaves",
                kit.post(callerA, "/api/v1/deliveries/" + consignment + "/otp", "{}", everything),
                409, "consignment-not-in-transit");
        expect("a consignment nobody has",
                kit.post(callerA, "/api/v1/deliveries/" + UUID.randomUUID() + "/otp", "{}", everything),
                404, null);
    }

    @Test
    @DisplayName("a full-truckload consignment counts as having left once its trip has been dispatched")
    void tripDecidesForTruckload() {
        // As planning leaves it: assigned to a load, never moved on by itself.
        jdbc.sql("UPDATE consignment SET status = 'ASSIGNED_TO_LOAD' WHERE id = :c").param("c", consignment).update();
        UUID trip = jdbc.sql("""
                        SELECT t.id FROM trip t JOIN load_consignment lc ON lc.load_id = t.load_id
                         WHERE lc.consignment_id = :c
                        """).param("c", consignment).query(UUID.class).single();
        String before = jdbc.sql("SELECT status FROM trip WHERE id = :t").param("t", trip).query(String.class).single();
        try {
            jdbc.sql("UPDATE trip SET status = 'ASSIGNED' WHERE id = :t").param("t", trip).update();
            expect("its trip has not left", kit.post(callerA, "/api/v1/deliveries/" + consignment + "/otp", "{}",
                    everything), 409, "consignment-not-in-transit");

            jdbc.sql("UPDATE trip SET status = 'DISPATCHED' WHERE id = :t").param("t", trip).update();
            String code = otp();
            expect("its trip has left", prove(code), 204, null);
        } finally {
            jdbc.sql("UPDATE trip SET status = :s WHERE id = :t").param("s", before).param("t", trip).update();
        }
    }

    @Test
    @DisplayName("the proof needs a recipient and a whole position")
    void proofBody() {
        String code = otp();
        expect("no recipient", kit.post(callerA, "/api/v1/deliveries/" + consignment + "/proof",
                "{\"otp\":\"" + code + "\",\"recipientName\":\"  \"}", everything), 400, null);
        expect("latitude alone", kit.post(callerA, "/api/v1/deliveries/" + consignment + "/proof",
                "{\"otp\":\"" + code + "\",\"recipientName\":\"R\",\"lat\":19.1}", everything),
                409, "position-incomplete");
        expect("a delivery in the future", kit.post(callerA, "/api/v1/deliveries/" + consignment + "/proof",
                "{\"otp\":\"" + code + "\",\"recipientName\":\"R\",\"deliveredAt\":\"2099-01-01T00:00:00Z\"}",
                everything), 409, "delivered-in-future");
        // None of those used up the code.
        expect("the right code still works", prove(code), 204, null);
    }

    // ------------------------------------------------------------- claims

    @Test
    @DisplayName("loss and delay can be claimed at once; damage and shortage need a proof of delivery")
    void proofRules() {
        raise("LOSS", "1500.50");
        raise("DELAY", "200");
        expect("damage with no proof", raiseOutcome("DAMAGE", "100"), 409, "claim-needs-proof-of-delivery");
        expect("shortage with no proof", raiseOutcome("SHORTAGE", "100"), 409, "claim-needs-proof-of-delivery");

        expect("proved", prove(otp()), 204, null);
        raise("DAMAGE", "100");
        raise("SHORTAGE", "75.25");
    }

    @Test
    @DisplayName("an amount is more than zero, in whole paise, and a claim needs a real consignment")
    void claimBody() {
        expect("zero", raiseOutcome("LOSS", "0"), 409, "claim-amount-invalid");
        expect("negative", raiseOutcome("LOSS", "-5"), 409, "claim-amount-invalid");
        expect("three decimals", raiseOutcome("LOSS", "1.234"), 409, "claim-amount-invalid");
        expect("no such consignment", kit.post(callerA, "/api/v1/claims", "{\"consignmentId\":\""
                + UUID.randomUUID() + "\",\"claimType\":\"LOSS\",\"amount\":10,\"description\":\"x\"}",
                everything), 404, null);
    }

    @Test
    @DisplayName("a claim moves through review to a decision, and only forwards")
    void claimLifecycle() {
        UUID claim = raise("LOSS", "1000");

        expect("approve before review", move(claim, "APPROVED", ",\"approvedAmount\":500"), 409,
                "claim-transition-invalid");
        expect("review", move(claim, "UNDER_REVIEW", ""), 204, null);
        expect("approve without an amount", move(claim, "APPROVED", ""), 409, "claim-approved-amount-invalid");
        expect("approve more than claimed", move(claim, "APPROVED", ",\"approvedAmount\":1000.01"), 409,
                "claim-approved-amount-invalid");
        expect("approve", move(claim, "APPROVED", ",\"approvedAmount\":600"), 204, null);
        expect("settle", move(claim, "SETTLED", ""), 204, null);
        expect("nothing follows settlement", move(claim, "REJECTED", ",\"resolutionNote\":\"x\""), 409,
                "claim-transition-invalid");

        JsonNode read = kit.parse(kit.get(callerA, "/api/v1/claims/" + claim, everything));
        assertThat(read.get("status").asString()).isEqualTo("SETTLED");
        assertThat(read.get("approvedAmount").asDouble()).isEqualTo(600.0);
        assertThat(read.get("claimNo").asString()).startsWith("CLM-");
    }

    @Test
    @DisplayName("a rejection says why")
    void rejection() {
        UUID claim = raise("DELAY", "300");
        expect("review", move(claim, "UNDER_REVIEW", ""), 204, null);
        expect("reject without a reason", move(claim, "REJECTED", ""), 409, "claim-rejection-unexplained");
        expect("reject", move(claim, "REJECTED", ",\"resolutionNote\":\"Within the agreed window\""), 204, null);
        assertThat(kit.parse(kit.get(callerA, "/api/v1/claims/" + claim, everything)).get("resolutionNote").asString())
                .isEqualTo("Within the agreed window");
    }

    @Test
    @DisplayName("claims are listed a page at a time, and can be filtered")
    void listing() {
        raise("LOSS", "10");
        raise("LOSS", "20");
        UUID third = raise("DELAY", "30");
        expect("review the third", move(third, "UNDER_REVIEW", ""), 204, null);

        JsonNode first = kit.parse(kit.get(callerA, "/api/v1/claims?limit=2", everything));
        assertThat(first.get("items")).hasSize(2);
        assertThat(first.get("hasMore").asBoolean()).isTrue();
        String cursor = first.get("nextCursor").asString();

        JsonNode second = kit.parse(kit.get(callerA, "/api/v1/claims?limit=2&cursor=" + cursor, everything));
        assertThat(second.get("items")).hasSize(1);
        assertThat(second.get("hasMore").asBoolean()).isFalse();

        JsonNode reviewing = kit.parse(kit.get(callerA, "/api/v1/claims?status=UNDER_REVIEW", everything));
        assertThat(reviewing.get("items")).hasSize(1);
        assertThat(reviewing.get("items").get(0).get("id").asString()).isEqualTo(third.toString());
    }

    // --------------------------------------------------- permissions, tenants

    @Test
    @DisplayName("raising, deciding and reading need their own permissions")
    void permissions() {
        UUID claim = raise("LOSS", "100");

        expect("raise with decide only", kit.post(callerA, "/api/v1/claims", "{\"consignmentId\":\""
                + consignment + "\",\"claimType\":\"LOSS\",\"amount\":1,\"description\":\"x\"}",
                "CLAIM_DECIDE"), 403, null);
        expect("decide with raise only", kit.post(callerA, "/api/v1/claims/" + claim + "/transitions",
                "{\"target\":\"UNDER_REVIEW\"}", "CLAIM_RAISE"), 403, null);
        expect("list with raise only", kit.get(callerA, "/api/v1/claims", "CLAIM_RAISE"), 403, null);
        expect("issue a code without POD_RECORD",
                kit.post(callerA, "/api/v1/deliveries/" + consignment + "/otp", "{}", "POD_READ"), 403, null);
        expect("read a proof without POD_READ",
                kit.get(callerA, "/api/v1/deliveries/" + consignment, "POD_RECORD"), 403, null);
    }

    @Test
    @DisplayName("another tenant cannot touch a consignment, a proof or a claim that is not its own")
    void tenantsAreIsolated() {
        String code = otp();
        expect("a proves", prove(code), 204, null);
        UUID claim = raise("DAMAGE", "100");

        expect("b issues a code for a's consignment",
                kit.post(callerB, "/api/v1/deliveries/" + consignment + "/otp", "{}", everything), 404, null);
        expect("b reads a's proof",
                kit.get(callerB, "/api/v1/deliveries/" + consignment, everything), 404, null);
        expect("b raises a claim on a's consignment", kit.post(callerB, "/api/v1/claims",
                "{\"consignmentId\":\"" + consignment + "\",\"claimType\":\"LOSS\",\"amount\":1,\"description\":\"x\"}",
                everything), 404, null);
        expect("b reads a's claim", kit.get(callerB, "/api/v1/claims/" + claim, everything), 404, null);
        expect("b decides a's claim", kit.post(callerB, "/api/v1/claims/" + claim + "/transitions",
                "{\"target\":\"UNDER_REVIEW\"}", everything), 404, null);
        assertThat(kit.parse(kit.get(callerB, "/api/v1/claims", everything)).get("items")).isEmpty();
    }
}
