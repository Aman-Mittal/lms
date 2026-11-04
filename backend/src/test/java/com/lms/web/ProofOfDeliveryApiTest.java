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

import java.util.Arrays;
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
 * A proof of delivery driving status and money, over HTTP with real
 * permissions: the consignment is delivered by its proof, the carrier's trip
 * waits for clean proofs (or a person with the right releases it), the client
 * can be billed once a consignment is marked billable by hand, and the
 * delivery is announced to webhook subscribers.
 *
 * <p>Each seeded tenant has one consignment on one trip with a freight bill.
 * The database is reached directly (as the owner) only to reset state and to
 * stand in for the trip's completion, which placed the proof hold.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProofOfDeliveryApiTest {

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
    private Tenant callerB;
    private Seeded a;
    private UUID consignment;
    private UUID trip;
    private UUID bill;

    @BeforeAll
    void twoTenants() {
        kit = new ApiTestKit(fixtures, tokens, port);
        everything = seeds.allPermissions().toArray(String[]::new);
        a = seeds.seed(0.0);
        Seeded b = seeds.seed(1.0);
        callerA = kit.tenantOf(a.tenantId(), a.orgUnitId(), a.orgPath());
        callerB = kit.tenantOf(b.tenantId(), b.orgUnitId(), b.orgPath());
        consignment = a.id("consignment");
        trip = a.id("trip");
        bill = a.id("freight_bill");
        // The seed's exception ticket holds the trip too; closing it is not
        // this test's subject, so its hold is lifted here.
        jdbc.sql("UPDATE settlement_hold SET released_at = now() WHERE tenant_id = :t")
                .param("t", a.tenantId()).update();
    }

    /** In transit, unproved, and held for its proof as the trip's completion leaves it. */
    @BeforeEach
    void inTransitAndHeld() {
        UUID t = a.tenantId();
        jdbc.sql("DELETE FROM delivery_proof WHERE tenant_id = :t").param("t", t).update();
        jdbc.sql("DELETE FROM consignment_billing_mark WHERE tenant_id = :t").param("t", t).update();
        jdbc.sql("DELETE FROM settlement_hold WHERE tenant_id = :t AND reason = 'PROOF_OF_DELIVERY'")
                .param("t", t).update();
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

    private void prove(String body) {
        Outcome issued = kit.post(callerA, "/api/v1/deliveries/" + consignment + "/otp", "{}", everything);
        assertThat(issued.status()).as(issued.body()).isEqualTo(200);
        String otp = kit.parse(issued).get("otp").asString();
        Outcome proved = kit.post(callerA, "/api/v1/deliveries/" + consignment + "/proof",
                "{\"otp\":\"" + otp + "\",\"recipientName\":\"R. Consignee\"" + body + "}", everything);
        assertThat(proved.status()).as(proved.body()).isEqualTo(204);
    }

    private JsonNode billView() {
        Outcome got = kit.get(callerA, "/api/v1/freight-bills/" + bill, everything);
        assertThat(got.status()).as(got.body()).isEqualTo(200);
        return kit.parse(got);
    }

    private JsonNode consignmentView() {
        Outcome got = kit.get(callerA, "/api/v1/consignments/" + consignment, everything);
        assertThat(got.status()).as(got.body()).isEqualTo(200);
        return kit.parse(got);
    }

    private Outcome release(Tenant caller, UUID billId, String reason, String... permissions) {
        return kit.post(caller, "/api/v1/freight-bills/" + billId + "/proof-hold/release",
                "{\"reason\":\"" + reason + "\"}", permissions);
    }

    private Outcome mark(Tenant caller, UUID consignmentId, String reason, String... permissions) {
        return kit.post(caller, "/api/v1/client-billing/consignments/" + consignmentId
                + "/billing-eligibility", "{\"reason\":\"" + reason + "\"}", permissions);
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

    // --------------------------------------------------------------- status

    @Test
    @DisplayName("a clean proof delivers the consignment, shows on it, and releases the trip's proof hold")
    void cleanProofDeliversAndReleases() {
        assertThat(billView().get("awaitingProof").asBoolean()).isTrue();
        long deliveriesBefore = deliveriesAudited();

        prove("");

        JsonNode view = consignmentView();
        assertThat(view.get("status").asString()).isEqualTo("DELIVERED");
        assertThat(view.get("proofStatus").asString()).isEqualTo("CONFIRMED");
        assertThat(view.get("proofCondition").asString()).isEqualTo("CLEAN");
        assertThat(billView().get("awaitingProof").asBoolean()).isFalse();
        assertThat(deliveriesAudited()).as("the delivery is audited once").isEqualTo(deliveriesBefore + 1);
    }

    private long deliveriesAudited() {
        return jdbc.sql("""
                        SELECT count(*) FROM audit_log
                         WHERE action = 'CONSIGNMENT_DELIVERED' AND resource_id = :c
                        """).param("c", consignment).query(Long.class).single();
    }

    @Test
    @DisplayName("a shortage or damage needs a remark, is shown on the proof, and keeps the hold")
    void exceptionKeepsTheHold() {
        Outcome issued = kit.post(callerA, "/api/v1/deliveries/" + consignment + "/otp", "{}", everything);
        String otp = kit.parse(issued).get("otp").asString();
        expect("damage without a remark", kit.post(callerA, "/api/v1/deliveries/" + consignment + "/proof",
                "{\"otp\":\"" + otp + "\",\"recipientName\":\"R\",\"goodsCondition\":\"DAMAGE\"}", everything),
                409, "proof-remark-required");

        prove(",\"goodsCondition\":\"SHORTAGE\",\"remark\":\"One pallet short\"");

        JsonNode proof = kit.parse(kit.get(callerA, "/api/v1/deliveries/" + consignment, everything));
        assertThat(proof.get("goodsCondition").asString()).isEqualTo("SHORTAGE");
        assertThat(proof.get("remark").asString()).isEqualTo("One pallet short");
        assertThat(consignmentView().get("status").asString()).isEqualTo("DELIVERED");
        assertThat(billView().get("awaitingProof").asBoolean()).as("a shortage is for a person to accept").isTrue();
    }

    // ------------------------------------------------------ releasing by hand

    @Test
    @DisplayName("a hold is released by hand only with the right and a reason, once, and audited")
    void releaseByHand() {
        expect("without the right", release(callerA, bill, "Accepted", without("SETTLEMENT_HOLD_RELEASE")), 403, null);
        expect("without a reason", release(callerA, bill, " ", everything), 400, null);
        expect("another tenant's bill", release(callerB, bill, "Accepted", everything), 404, "not-found");
        expect("no such bill", release(callerA, UUID.randomUUID(), "Accepted", everything), 404, "not-found");
        assertThat(billView().get("awaitingProof").asBoolean()).isTrue();

        expect("released", release(callerA, bill, "Consignee confirmed by phone", everything), 204, null);
        JsonNode view = billView();
        assertThat(view.get("awaitingProof").asBoolean()).isFalse();
        assertThat(view.get("proofHoldReleaseNote").asString()).isEqualTo("Consignee confirmed by phone");
        assertThat(jdbc.sql("""
                        SELECT after_state ->> 'reason' FROM audit_log
                         WHERE action = 'SETTLEMENT_PROOF_HOLD_RELEASED' AND resource_id = :b
                        """).param("b", bill).query(String.class).list())
                .containsExactly("Consignee confirmed by phone");

        expect("released again", release(callerA, bill, "Again", everything), 409, "no-proof-hold");
    }

    // ------------------------------------------------------- the client's bill

    @Test
    @DisplayName("a consignment is marked billable by hand with the right and a reason, once")
    void markBillable() {
        expect("without the right", mark(callerA, consignment, "Paper copy signed",
                without("CLIENT_BILLING_RUN")), 403, null);
        expect("without a reason", mark(callerA, consignment, " ", everything), 400, null);
        expect("another tenant's consignment", mark(callerB, consignment, "Paper copy signed", everything),
                404, "not-found");
        expect("no such consignment", mark(callerA, UUID.randomUUID(), "Paper copy signed", everything),
                404, "not-found");

        expect("marked", mark(callerA, consignment, "Paper copy signed", everything), 204, null);
        expect("marked again", mark(callerA, consignment, "Paper copy signed", everything), 204, null);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM audit_log
                         WHERE action = 'CONSIGNMENT_BILLING_ELIGIBLE_MARKED' AND resource_id = :c
                        """).param("c", consignment).query(Long.class).single())
                .as("the mark is audited once").isEqualTo(1L);

        jdbc.sql("UPDATE consignment SET status = 'CANCELLED' WHERE id = :c").param("c", consignment).update();
        jdbc.sql("DELETE FROM consignment_billing_mark WHERE tenant_id = :t").param("t", a.tenantId()).update();
        expect("a cancelled consignment", mark(callerA, consignment, "Paper copy signed", everything),
                409, "consignment-not-billable");
    }

    // --------------------------------------------------------------- events

    @Test
    @DisplayName("a delivery by proof is announced to a subscriber of CONSIGNMENT_DELIVERED")
    void deliveryIsAnnounced() {
        UUID subscription = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO webhook_subscription (id, tenant_id, name, url, event_types, secret, active)
                        VALUES (:id, :t, :name, 'https://hooks.example.com/lms', ARRAY['CONSIGNMENT_DELIVERED'],
                                'not-a-real-secret', TRUE)
                        """)
                .param("id", subscription).param("t", a.tenantId())
                .param("name", "pod-" + subscription).update();
        try {
            prove("");

            assertThat(jdbc.sql("""
                            SELECT payload ->> 'type' || ':' || (payload -> 'data' ->> 'trigger')
                              FROM webhook_delivery WHERE subscription_id = :s
                            """).param("s", subscription).query(String.class).list())
                    .containsExactly("CONSIGNMENT_DELIVERED:PROOF_OF_DELIVERY");
        } finally {
            jdbc.sql("DELETE FROM webhook_subscription WHERE id = :s").param("s", subscription).update();
        }
    }
}
