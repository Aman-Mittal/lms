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
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.lms.acceptance.TenantSeeds;
import com.lms.acceptance.TenantSeeds.Seeded;
import com.lms.acceptance.TestFixtures;
import com.lms.approvals.command.ApprovalExpirySweep;
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
 * The approvals module over HTTP with real permissions: an order's
 * cancellation with and without the tenant's approval switch, a proof of
 * delivery's reversal (always approved, refused once billed), the decision
 * rules (self-decision, duplicate open request, the wrong right, a stale or
 * missing If-Match, another tenant's request), the inbox scopes, the policy
 * snapshot on a request, the expiry sweep and the webhook types.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApprovalApiTest {

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
    @Autowired
    private ApprovalExpirySweep sweep;

    private ApiTestKit kit;
    private String[] everything;
    private Seeded a;
    private Tenant requester;
    private Tenant approver;
    private Tenant second;
    private Tenant foreigner;
    private UUID customer;
    private UUID origin;

    @BeforeAll
    void twoTenants() {
        kit = new ApiTestKit(fixtures, tokens, port);
        everything = seeds.allPermissions().toArray(String[]::new);
        a = seeds.seed(0.0);
        Seeded b = seeds.seed(1.0);
        requester = kit.tenantOf(a.tenantId(), a.orgUnitId(), a.orgPath());
        approver = kit.tenantOf(a.tenantId(), a.orgUnitId(), a.orgPath());
        second = kit.tenantOf(a.tenantId(), a.orgUnitId(), a.orgPath());
        foreigner = kit.tenantOf(b.tenantId(), b.orgUnitId(), b.orgPath());
        customer = jdbc.sql("SELECT customer_partner_id FROM sales_order WHERE id = :id")
                .param("id", a.id("sales_order")).query(UUID.class).single();
        origin = jdbc.sql("SELECT origin_terminal_id FROM sales_order WHERE id = :id")
                .param("id", a.id("sales_order")).query(UUID.class).single();
        jdbc.sql("UPDATE settlement_hold SET released_at = now() WHERE tenant_id = :t")
                .param("t", a.tenantId()).update();
    }

    @BeforeEach
    void defaultPolicies() {
        jdbc.sql("DELETE FROM approval_policy_override WHERE tenant_id = :t").param("t", a.tenantId()).update();
    }

    // ------------------------------------------------------------- helpers

    private UUID draftOrder() {
        Outcome raised = kit.post(requester, "/api/v1/orders", """
                {"orgUnitId":"%s","customerPartnerId":"%s","originTerminalId":"%s"}
                """.formatted(a.orgUnitId(), customer, origin), everything);
        assertThat(raised.status()).as(raised.body()).isEqualTo(201);
        return UUID.fromString(kit.parse(raised).get("id").asString());
    }

    private String orderStatus(UUID orderId) {
        return kit.parse(kit.get(requester, "/api/v1/orders/" + orderId, everything)).get("status").asString();
    }

    private void requireOrderCancellationApproval(String extra) {
        Outcome set = kit.put(requester, "/api/v1/approvals/policies/ORDER_CANCELLATION",
                "{\"required\":true" + extra + "}", "APPROVAL_POLICY_MANAGE");
        assertThat(set.status()).as(set.body()).isEqualTo(200);
        assertThat(kit.parse(set).get("required").asBoolean()).isTrue();
    }

    private UUID requestCancellation(UUID orderId) {
        Outcome asked = kit.post(requester, "/api/v1/orders/" + orderId + "/cancel",
                "{\"reason\":\"Customer withdrew\"}", everything);
        assertThat(asked.status()).as(asked.body()).isEqualTo(202);
        JsonNode ticket = kit.parse(asked);
        assertThat(ticket.get("status").asString()).isEqualTo("REQUESTED");
        assertThat(ticket.get("subjectType").asString()).isEqualTo("ORDER_CANCELLATION");
        return UUID.fromString(ticket.get("id").asString());
    }

    private JsonNode detail(Tenant caller, UUID id) {
        Outcome got = kit.get(caller, "/api/v1/approvals/" + id, everything);
        assertThat(got.status()).as(got.body()).isEqualTo(200);
        return kit.parse(got);
    }

    private long version(UUID id) {
        return detail(approver, id).get("approval").get("version").asLong();
    }

    private Outcome decide(Tenant caller, UUID id, String verb, String body, String ifMatch, String... perms) {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/approvals/" + id + "/" + verb))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + kit.token(caller, perms))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return kit.send(request.build());
    }

    private Outcome approve(Tenant caller, UUID id, String... perms) {
        return decide(caller, id, "approve", "{}", "\"" + version(id) + "\"", perms);
    }

    private static void expect(String what, Outcome outcome, int status, String code) {
        assertThat(outcome.status()).as(what + ": " + outcome.body()).isEqualTo(status);
        if (code != null) {
            assertThat(outcome.code()).as(what).isEqualTo(code);
        }
    }

    private String[] without(String... permissions) {
        List<String> drop = List.of(permissions);
        return Arrays.stream(everything).filter(p -> !drop.contains(p)).toArray(String[]::new);
    }

    private long audited(String action, UUID resourceId) {
        return jdbc.sql("SELECT count(*) FROM audit_log WHERE action = :a AND resource_id = :r")
                .param("a", action).param("r", resourceId).query(Long.class).single();
    }

    // --------------------------------------------------- order cancellation

    @Test
    @DisplayName("without the tenant's switch an order is cancelled at once, as before")
    void cancelWithoutApproval() {
        UUID order = draftOrder();
        expect("cancel", kit.post(requester, "/api/v1/orders/" + order + "/cancel", "{}", everything), 204, null);
        assertThat(orderStatus(order)).isEqualTo("CANCELLED");
        assertThat(jdbc.sql("SELECT count(*) FROM approval_request WHERE subject_id = :o")
                .param("o", order).query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("with the switch, cancelling asks for approval; approving cancels through the one path, audited")
    void cancelThroughApproval() {
        requireOrderCancellationApproval("");
        UUID order = draftOrder();
        UUID id = requestCancellation(order);
        assertThat(orderStatus(order)).as("nothing is cancelled while it waits").isEqualTo("DRAFT");

        expect("a second request", kit.post(requester, "/api/v1/orders/" + order + "/cancel", "{}", everything),
                409, "approval-already-open");
        expect("the requester deciding", approve(requester, id, everything), 409, "approval-self-decision");
        expect("without the deciding right", approve(approver, id, without("ORDER_APPROVE")), 403, null);
        expect("a stale If-Match", decide(approver, id, "approve", "{}", "\"99\"", everything),
                409, "concurrent-modification");
        expect("no If-Match", decide(approver, id, "approve", "{}", null, everything), 400, null);
        expect("another tenant's request", kit.get(foreigner, "/api/v1/approvals/" + id, everything), 404, null);
        expect("another tenant deciding", decide(foreigner, id, "approve", "{}", "\"0\"", everything), 404, null);

        Outcome fetched = kit.get(approver, "/api/v1/approvals/" + id, everything);
        assertThat(fetched.body()).contains("\"payload\"");
        expect("approved", approve(approver, id, "ORDER_APPROVE", "APPROVAL_READ"), 204, null);

        assertThat(orderStatus(order)).isEqualTo("CANCELLED");
        JsonNode got = detail(approver, id);
        assertThat(got.get("approval").get("status").asString()).isEqualTo("APPROVED");
        assertThat(got.get("approval").get("payload").get("reason").asString()).isEqualTo("Customer withdrew");
        assertThat(got.get("trail")).hasSize(1);
        assertThat(got.get("trail").get(0).get("permission").asString()).isEqualTo("ORDER_APPROVE");
        assertThat(audited("APPROVAL_REQUESTED", id)).isEqualTo(1);
        assertThat(audited("APPROVAL_APPROVED", id)).isEqualTo(1);
        assertThat(audited("ORDER_CANCELLATION_REQUESTED", order)).isEqualTo(1);
        assertThat(audited("ORDER_CANCELLED", order)).isEqualTo(1);

        expect("deciding a closed request", approve(second, id, everything), 409, "approval-not-open");
    }

    @Test
    @DisplayName("a rejection needs a reason and leaves the order as it was")
    void rejectLeavesOrder() {
        requireOrderCancellationApproval("");
        UUID order = draftOrder();
        UUID id = requestCancellation(order);
        String etag = "\"" + version(id) + "\"";
        expect("no reason", decide(approver, id, "reject", "{\"reason\":\" \"}", etag, everything), 400, null);
        expect("without the right", decide(approver, id, "reject", "{\"reason\":\"No\"}", etag,
                without("ORDER_APPROVE")), 403, null);
        expect("rejected", decide(approver, id, "reject", "{\"reason\":\"Customer reconfirmed\"}", etag,
                everything), 204, null);
        assertThat(orderStatus(order)).isEqualTo("DRAFT");
        JsonNode got = detail(approver, id).get("approval");
        assertThat(got.get("status").asString()).isEqualTo("REJECTED");
        assertThat(got.get("reason").asString()).isEqualTo("Customer reconfirmed");
        assertThat(audited("APPROVAL_REJECTED", id)).isEqualTo(1);
        // Closed, so the order can be asked about again.
        requestCancellation(order);
    }

    @Test
    @DisplayName("only the requester cancels a request")
    void requesterCancels() {
        requireOrderCancellationApproval("");
        UUID order = draftOrder();
        UUID id = requestCancellation(order);
        String etag = "\"" + version(id) + "\"";
        expect("someone else", decide(approver, id, "cancel", "{}", etag, everything), 409,
                "approval-not-requester");
        expect("the requester", decide(requester, id, "cancel", "{\"reason\":\"Asked by mistake\"}", etag,
                everything), 204, null);
        assertThat(detail(approver, id).get("approval").get("status").asString()).isEqualTo("CANCELLED");
        assertThat(orderStatus(order)).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("an approval the subject no longer allows fails with the owner's code and stays open")
    void staleApprovalStaysOpen() {
        requireOrderCancellationApproval("");
        UUID order = draftOrder();
        UUID id = requestCancellation(order);
        jdbc.sql("UPDATE sales_order SET status = 'FULFILLED' WHERE id = :o").param("o", order).update();
        expect("approve", approve(approver, id, everything), 409, "order-illegal-transition");
        assertThat(detail(approver, id).get("approval").get("status").asString()).isEqualTo("REQUESTED");
        assertThat(audited("APPROVAL_APPROVED", id)).isZero();
    }

    // ---------------------------------------------------------------- inbox

    @Test
    @DisplayName("the inbox narrows to what the caller can decide, and to what they asked for")
    void inboxScopes() {
        requireOrderCancellationApproval("");
        UUID order = draftOrder();
        UUID id = requestCancellation(order);

        assertThat(ids(kit.get(approver, "/api/v1/approvals?scope=MINE_TO_DECIDE", everything)))
                .contains(id.toString());
        assertThat(ids(kit.get(requester, "/api/v1/approvals?scope=MINE_TO_DECIDE", everything)))
                .doesNotContain(id.toString());
        assertThat(ids(kit.get(approver, "/api/v1/approvals?scope=MINE_TO_DECIDE",
                without("ORDER_APPROVE")))).doesNotContain(id.toString());
        assertThat(ids(kit.get(requester, "/api/v1/approvals?scope=REQUESTED_BY_ME", everything)))
                .contains(id.toString());
        assertThat(ids(kit.get(approver, "/api/v1/approvals?scope=REQUESTED_BY_ME", everything)))
                .doesNotContain(id.toString());
        assertThat(ids(kit.get(approver, "/api/v1/approvals?subjectType=ORDER_CANCELLATION&subjectId=" + order,
                everything))).containsExactly(id.toString());
        assertThat(ids(kit.get(foreigner, "/api/v1/approvals", everything))).doesNotContain(id.toString());
        expect("without the right to read", kit.get(approver, "/api/v1/approvals", without("APPROVAL_READ")),
                403, null);

        // Keyset paging: one row a page, no repeats.
        Outcome first = kit.get(approver, "/api/v1/approvals?limit=1", everything);
        JsonNode page = kit.parse(first);
        if (page.get("hasMore").asBoolean()) {
            Outcome next = kit.get(approver, "/api/v1/approvals?limit=1&cursor="
                    + java.net.URLEncoder.encode(page.get("nextCursor").asString(),
                    java.nio.charset.StandardCharsets.UTF_8), everything);
            assertThat(ids(next)).doesNotContainAnyElementsOf(ids(first));
        }
    }

    private List<String> ids(Outcome listing) {
        assertThat(listing.status()).as(listing.body()).isEqualTo(200);
        List<String> ids = new java.util.ArrayList<>();
        kit.parse(listing).get("items").forEach(n -> ids.add(n.get("id").asString()));
        return ids;
    }

    // --------------------------------------------------------------- policy

    @Test
    @DisplayName("an open request keeps the policy it was raised under; the next one takes the new policy")
    void policySnapshot() {
        requireOrderCancellationApproval("");
        UUID first = requestCancellation(draftOrder());
        requireOrderCancellationApproval(",\"decidePermissions\":[\"ORDER_APPROVE\",\"ORDER_APPROVE\"]");
        UUID later = requestCancellation(draftOrder());

        assertThat(detail(approver, first).get("approval").get("approvalsRequired").asInt()).isEqualTo(1);
        assertThat(detail(approver, later).get("approval").get("approvalsRequired").asInt()).isEqualTo(2);

        expect("first, one approver", approve(approver, first, everything), 204, null);
        assertThat(detail(approver, first).get("approval").get("status").asString()).isEqualTo("APPROVED");

        expect("later, step one", approve(approver, later, everything), 204, null);
        assertThat(detail(approver, later).get("approval").get("status").asString()).isEqualTo("REQUESTED");
        expect("the same approver again", approve(approver, later, everything), 409, "approval-already-given");
        expect("a second approver", approve(second, later, everything), 204, null);
        assertThat(detail(approver, later).get("approval").get("status").asString()).isEqualTo("APPROVED");

        expect("an unknown step permission", kit.put(requester, "/api/v1/approvals/policies/ORDER_CANCELLATION",
                "{\"decidePermissions\":[\"NOT_A_RIGHT\"]}", "APPROVAL_POLICY_MANAGE"), 409, "approval-steps-invalid");
        expect("without the right", kit.put(requester, "/api/v1/approvals/policies/ORDER_CANCELLATION",
                "{\"required\":true}", without("APPROVAL_POLICY_MANAGE")), 403, null);
        expect("an unknown subject type", kit.put(requester, "/api/v1/approvals/policies/NOTHING",
                "{\"required\":true}", "APPROVAL_POLICY_MANAGE"), 404, null);
        expect("switching off a mandatory flow", kit.put(requester, "/api/v1/approvals/policies/POD_REVERSAL",
                "{\"required\":false}", "APPROVAL_POLICY_MANAGE"), 409, "approval-mandatory");
    }

    // --------------------------------------------------------------- expiry

    @Test
    @DisplayName("the sweep expires a lapsed request once, or approves it when the policy says so")
    void expirySweep() {
        requireOrderCancellationApproval("");
        UUID order = draftOrder();
        UUID id = requestCancellation(order);
        lapse(id);
        sweep.sweep(Instant.now());
        assertThat(detail(approver, id).get("approval").get("status").asString()).isEqualTo("EXPIRED");
        assertThat(orderStatus(order)).isEqualTo("DRAFT");
        sweep.sweep(Instant.now());
        assertThat(audited("APPROVAL_EXPIRED", id)).as("idempotent").isEqualTo(1);

        requireOrderCancellationApproval(",\"onExpiry\":\"AUTO_APPROVE\"");
        UUID auto = requestCancellation(order);
        lapse(auto);
        sweep.sweep(Instant.now());
        assertThat(detail(approver, auto).get("approval").get("status").asString()).isEqualTo("APPROVED");
        assertThat(orderStatus(order)).isEqualTo("CANCELLED");
    }

    private void lapse(UUID id) {
        jdbc.sql("UPDATE approval_request SET due_at = now() - interval '1 minute' WHERE id = :id")
                .param("id", id).update();
    }

    // ---------------------------------------------------------- POD reversal

    private UUID provedConsignment() {
        UUID consignment = a.id("consignment");
        UUID t = a.tenantId();
        jdbc.sql("DELETE FROM approval_request WHERE tenant_id = :t AND subject_type = 'POD_REVERSAL'")
                .param("t", t).update();
        jdbc.sql("DELETE FROM delivery_proof WHERE tenant_id = :t").param("t", t).update();
        jdbc.sql("UPDATE freight_bill SET status = 'DRAFT' WHERE tenant_id = :t").param("t", t).update();
        jdbc.sql("UPDATE consignment SET status = 'IN_TRANSIT' WHERE id = :c").param("c", consignment).update();
        Outcome issued = kit.post(requester, "/api/v1/deliveries/" + consignment + "/otp", "{}", everything);
        assertThat(issued.status()).as(issued.body()).isEqualTo(200);
        String otp = kit.parse(issued).get("otp").asString();
        Outcome proved = kit.post(requester, "/api/v1/deliveries/" + consignment + "/proof",
                "{\"otp\":\"" + otp + "\",\"recipientName\":\"R. Consignee\"}", everything);
        assertThat(proved.status()).as(proved.body()).isEqualTo(204);
        assertThat(consignmentStatus(consignment)).isEqualTo("DELIVERED");
        return consignment;
    }

    private String consignmentStatus(UUID consignment) {
        return kit.parse(kit.get(requester, "/api/v1/consignments/" + consignment, everything))
                .get("status").asString();
    }

    private Outcome askReversal(UUID consignment, String... perms) {
        return kit.post(requester, "/api/v1/deliveries/" + consignment + "/proof/reversal",
                "{\"reason\":\"Signed by the wrong person\"}", perms);
    }

    @Test
    @DisplayName("a proof is reversed only through approval, which restores the consignment and is audited")
    void proofReversal() {
        UUID consignment = provedConsignment();
        expect("without the right", askReversal(consignment, without("POD_REVERSE")), 403, null);
        expect("another tenant's consignment", kit.post(foreigner, "/api/v1/deliveries/" + consignment
                + "/proof/reversal", "{\"reason\":\"x\"}", everything), 404, null);
        expect("no reason", kit.post(requester, "/api/v1/deliveries/" + consignment + "/proof/reversal",
                "{\"reason\":\"\"}", everything), 400, null);

        Outcome asked = askReversal(consignment, everything);
        expect("asked", asked, 202, null);
        UUID id = UUID.fromString(kit.parse(asked).get("id").asString());
        assertThat(consignmentStatus(consignment)).as("nothing changes while it waits").isEqualTo("DELIVERED");
        expect("a second request", askReversal(consignment, everything), 409, "approval-already-open");
        expect("the requester deciding", approve(requester, id, everything), 409, "approval-self-decision");
        expect("without the deciding right", approve(approver, id, without("POD_REVERSAL_APPROVE")), 403, null);

        expect("approved", approve(approver, id, everything), 204, null);
        assertThat(kit.parse(kit.get(requester, "/api/v1/deliveries/" + consignment, everything))
                .get("status").asString()).isEqualTo("REVERSED");
        assertThat(consignmentStatus(consignment)).isEqualTo("IN_TRANSIT");
        UUID proofId = jdbc.sql("SELECT id FROM delivery_proof WHERE consignment_id = :c")
                .param("c", consignment).query(UUID.class).single();
        assertThat(audited("DELIVERY_PROOF_REVERSAL_REQUESTED", proofId)).isEqualTo(1);
        assertThat(audited("DELIVERY_PROOF_REVERSED", proofId)).isEqualTo(1);
        assertThat(audited("CONSIGNMENT_DELIVERY_REVERSED", consignment)).isEqualTo(1);
        expect("nothing left to reverse", askReversal(consignment, everything), 409, "proof-not-recorded");
    }

    @Test
    @DisplayName("a proof billing has used is not reversed, when asked or when approved")
    void billedProofRefused() {
        UUID consignment = provedConsignment();
        Outcome asked = askReversal(consignment, everything);
        expect("asked", asked, 202, null);
        UUID id = UUID.fromString(kit.parse(asked).get("id").asString());

        jdbc.sql("UPDATE freight_bill SET status = 'APPROVED_FOR_PAYMENT' WHERE id = :b")
                .param("b", a.id("freight_bill")).update();
        expect("approved after billing", approve(approver, id, everything), 409, "pod-already-billed");
        assertThat(detail(approver, id).get("approval").get("status").asString()).isEqualTo("REQUESTED");
        assertThat(consignmentStatus(consignment)).isEqualTo("DELIVERED");

        String etag = "\"" + version(id) + "\"";
        expect("rejected instead", decide(approver, id, "reject", "{\"reason\":\"Already paid\"}", etag,
                everything), 204, null);
        expect("asked after billing", askReversal(consignment, everything), 409, "pod-already-billed");
    }

    // ------------------------------------------------------------- webhooks

    @Test
    @DisplayName("requests and decisions are announced as APPROVAL_REQUESTED and APPROVAL_DECIDED")
    void webhookTypes() {
        UUID subscription = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO webhook_subscription (id, tenant_id, name, url, event_types, secret, active)
                        VALUES (:id, :t, :name, 'https://hooks.example.com/lms',
                                ARRAY['APPROVAL_REQUESTED', 'APPROVAL_DECIDED'], 'not-a-real-secret', TRUE)
                        """)
                .param("id", subscription).param("t", a.tenantId())
                .param("name", "approvals-" + subscription).update();
        try {
            requireOrderCancellationApproval("");
            UUID id = requestCancellation(draftOrder());
            expect("approved", approve(approver, id, everything), 204, null);
            assertThat(jdbc.sql("""
                            SELECT (payload ->> 'type') || ':' || coalesce(payload -> 'data' ->> 'status', '-')
                              FROM webhook_delivery WHERE subscription_id = :s
                             ORDER BY created_at, id
                            """).param("s", subscription).query(String.class).list())
                    .containsExactly("APPROVAL_REQUESTED:-", "APPROVAL_DECIDED:APPROVED");
        } finally {
            jdbc.sql("DELETE FROM webhook_subscription WHERE id = :s").param("s", subscription).update();
        }
    }
}
