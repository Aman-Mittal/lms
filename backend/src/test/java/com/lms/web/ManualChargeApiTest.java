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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.finance.command.FinanceSettingDefinitions;
import com.lms.web.ApiTestKit.Outcome;
import com.lms.web.ApiTestKit.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The charge catalogue and manual charges over HTTP (issue #225): catalogue
 * CRUD under {@code If-Match}, a charge under and over the approval threshold,
 * what an approved receivable and payable become (a client charge on an
 * invoice, a line of an open bill, or a wait for the vendor's next bill), that
 * an approved or paid bill is never re-priced, cancellation before billing
 * only, retries, tenant isolation, rights and webhooks.
 */
class ManualChargeApiTest extends MoneyApiSupport {

    private UUID trip;

    @BeforeEach
    void aTripAndALowThreshold() {
        trip = raiseSingleDropTrip();
        threshold("1000");
    }

    private void threshold(String amount) {
        setting(Map.of(FinanceSettingDefinitions.MANUAL_CHARGE_APPROVAL_THRESHOLD, new BigDecimal(amount)));
    }

    private static void expect(String what, Outcome outcome, int status, String code) {
        assertThat(outcome.status()).as(what + ": " + outcome.body()).isEqualTo(status);
        if (code != null) {
            assertThat(outcome.code()).as(what).isEqualTo(code);
        }
    }

    // ------------------------------------------------------------- catalogue

    @Test
    @DisplayName("the catalogue is created, read with its ETag and edited only against the version read")
    void catalogueLifecycle() {
        Outcome made = post("/api/v1/charge-catalogue", json("code", q("loading"), "name", q("Loading"), "basis",
                q("PER_UNIT"), "defaultRate", "50", "direction", q("BOTH")), CATALOGUE);
        expect("created", made, 201, null);
        String id = id(made);

        expect("same code in another case", post("/api/v1/charge-catalogue", json("code", q("LOADING"), "name",
                q("Again"), "basis", q("FLAT"), "defaultRate", "1", "direction", q("PAYABLE")), CATALOGUE), 409,
                "charge-code-taken");
        expect("a code with a blank in it", post("/api/v1/charge-catalogue", json("code", q("bad code"), "name",
                q("Bad"), "basis", q("FLAT"), "defaultRate", "1", "direction", q("PAYABLE")), CATALOGUE), 409,
                "charge-code-invalid");
        expect("without the right", post("/api/v1/charge-catalogue", json("code", q("X1"), "name", q("X"), "basis",
                q("FLAT"), "defaultRate", "1", "direction", q("PAYABLE")), RECORD), 403, null);
        expect("a negative rate", post("/api/v1/charge-catalogue", json("code", q("X2"), "name", q("X"), "basis",
                q("FLAT"), "defaultRate", "-1", "direction", q("PAYABLE")), CATALOGUE), 400, null);

        String path = "/api/v1/charge-catalogue/" + id;
        String etag = etagOf(path, CATALOGUE);
        assertThat(etag).isEqualTo("\"0\"");
        String edit = json("name", q("Loading and handling"), "basis", q("PER_KG"), "defaultRate", "2.5",
                "direction", q("RECEIVABLE"), "active", "true");
        expect("a stale version", send("PUT", path, edit, "\"7\"", CATALOGUE), 409, "concurrent-modification");
        expect("no If-Match", send("PUT", path, edit, null, CATALOGUE), 400, null);
        expect("edited", send("PUT", path, edit, etag, CATALOGUE), 204, null);
        expect("the same version again", send("PUT", path, edit, etag, CATALOGUE), 409, "concurrent-modification");

        JsonNode read = kit.parse(get(path, CATALOGUE));
        assertThat(read.get("code").asString()).isEqualTo("LOADING");
        assertThat(read.get("name").asString()).isEqualTo("Loading and handling");
        assertThat(read.get("basis").asString()).isEqualTo("PER_KG");
        assertThat(read.get("direction").asString()).isEqualTo("RECEIVABLE");
        assertThat(read.get("version").asLong()).isEqualTo(1);
        assertThat(audited("CHARGE_CATALOGUE_CREATED", id)).isEqualTo(1);
        assertThat(audited("CHARGE_CATALOGUE_UPDATED", id)).isEqualTo(1);

        UUID both = entry("HALTING", "PER_DAY", "400", "BOTH");
        UUID payable = entry("TOLL", "FLAT", "120", "PAYABLE");
        List<String> forReceivable = codes(get("/api/v1/charge-catalogue?direction=RECEIVABLE", CATALOGUE));
        assertThat(forReceivable).containsExactly("HALTING", "LOADING");
        assertThat(codes(get("/api/v1/charge-catalogue?q=toll", RECORD))).containsExactly("TOLL");
        assertThat(codes(get("/api/v1/charge-catalogue?limit=1", CATALOGUE))).hasSize(1);
        JsonNode first = kit.parse(get("/api/v1/charge-catalogue?limit=1", CATALOGUE));
        assertThat(first.get("hasMore").asBoolean()).isTrue();
        assertThat(codes(get("/api/v1/charge-catalogue?limit=1&cursor=" + first.get("nextCursor").asString(),
                CATALOGUE))).containsExactly("LOADING");
        assertThat(both).isNotEqualTo(payable);
        expect("reading needs a catalogue or charge right", get("/api/v1/charge-catalogue", "INVOICE_READ"), 403, null);
    }

    private List<String> codes(Outcome page) {
        assertThat(page.status()).as(page.body()).isEqualTo(200);
        List<String> codes = new java.util.ArrayList<>();
        kit.parse(page).get("items").forEach(item -> codes.add(item.get("code").asString()));
        return codes;
    }

    // ----------------------------------------------------- amounts and refusals

    @Test
    @DisplayName("a charge at or under the threshold is in force at once, its amount following the basis")
    void underTheThreshold() {
        UUID perUnit = entry("HALT", "PER_UNIT", "100", "RECEIVABLE");
        Outcome raised = charge(perUnit, "TRIP", trip, "3", null, null);
        expect("raised", raised, 201, null);
        JsonNode view = chargeView(id(raised));
        assertThat(view.get("status").asString()).isEqualTo("APPROVED");
        assertThat(dec(view, "amount")).isEqualByComparingTo("300");
        assertThat(view.get("direction").asString()).isEqualTo("RECEIVABLE");
        assertThat(view.get("customerPartnerId").asString()).isEqualTo(yard.customer().toString());
        assertThat(view.get("subjectRef").asString()).startsWith("TR-");
        assertThat(view.get("billed").asBoolean()).isFalse();
        assertThat(view.hasNonNull("clientChargeId")).as("the customer holds no client contract").isFalse();
        assertThat(audited("MANUAL_CHARGE_RECORDED", id(raised))).isEqualTo(1);

        JsonNode overridden = chargeView(id(charge(perUnit, "TRIP", trip, "2", "120", null)));
        assertThat(dec(overridden, "amount")).isEqualByComparingTo("240");
        assertThat(dec(overridden, "rate")).isEqualByComparingTo("120");

        UUID flat = entry("PENALTY", "FLAT", "500", "RECEIVABLE");
        JsonNode counted = chargeView(id(charge(flat, "TRIP", trip, "3", null, null)));
        assertThat(dec(counted, "amount")).as("a flat charge is one thing, whatever the quantity").isEqualByComparingTo("500");
        assertThat(dec(counted, "quantity")).isEqualByComparingTo("1");

        UUID perKg = entry("WEIGHT", "PER_KG", "0.333", "RECEIVABLE");
        assertThat(dec(chargeView(id(charge(perKg, "TRIP", trip, "100.5", null, null))), "amount"))
                .as("quantity times rate, half-up to two decimals").isEqualByComparingTo("33.47");

        JsonNode listed = kit.parse(get("/api/v1/manual-charges?subjectType=TRIP&subjectId=" + trip, RECORD));
        assertThat(listed.get("items")).hasSize(4);
        assertThat(kit.parse(get("/api/v1/manual-charges?tripId=" + trip + "&limit=2", RECORD)).get("hasMore")
                .asBoolean()).isTrue();
    }

    @Test
    @DisplayName("what the rules refuse: an inactive entry, a wrong side, nothing to charge, a payable off a trip")
    void refusals() {
        UUID receivable = entry("HALT", "PER_UNIT", "100", "RECEIVABLE");
        UUID both = entry("BOTH1", "PER_UNIT", "100", "BOTH");
        UUID payable = entry("PAY1", "PER_UNIT", "100", "PAYABLE");

        expect("a payable entry as a receivable", charge(payable, "TRIP", trip, "1", null, "RECEIVABLE"), 409,
                "manual-charge-direction-not-allowed");
        expect("an entry for either side without saying which", charge(both, "TRIP", trip, "1", null, null), 409,
                "manual-charge-direction-required");
        expect("no amount", charge(receivable, "TRIP", trip, "1", "0", null), 409, "manual-charge-amount-zero");
        expect("quantity above zero", charge(receivable, "TRIP", trip, "0", null, null), 400, null);
        expect("a reason is mandatory", post("/api/v1/manual-charges", json("catalogueId", q(receivable),
                "subjectType", q("TRIP"), "subjectId", q(trip), "quantity", "1"), RECORD), 400, null);

        UUID consignment = jdbc.sql("""
                SELECT lc.consignment_id FROM load_consignment lc JOIN trip t ON t.load_id = lc.load_id
                 WHERE t.id = :trip LIMIT 1""").param("trip", trip).query(UUID.class).single();
        UUID order = jdbc.sql("SELECT order_id FROM consignment WHERE id = :id").param("id", consignment)
                .query(UUID.class).single();
        expect("a receivable on a consignment", charge(receivable, "CONSIGNMENT", consignment, "1", null, null), 201,
                null);
        expect("a receivable on an order", charge(receivable, "ORDER", order, "1", null, null), 201, null);
        expect("a payable on an order", charge(payable, "ORDER", order, "1", null, null), 409,
                "manual-charge-payable-needs-trip");
        expect("a payable on a consignment", charge(payable, "CONSIGNMENT", consignment, "1", null, null), 409,
                "manual-charge-payable-needs-trip");
        expect("a subject that is not there", charge(receivable, "TRIP", UUID.randomUUID(), "1", null, null), 404, null);
        expect("a customer who is not aboard", post("/api/v1/manual-charges", json("catalogueId", q(receivable),
                "subjectType", q("TRIP"), "subjectId", q(trip), "customerId", q(yard.consignee()), "quantity", "1",
                "reason", q("x")), RECORD), 409, "manual-charge-customer-not-aboard");

        expect("switched off", send("PUT", "/api/v1/charge-catalogue/" + receivable, json("name", q("Halt"),
                "basis", q("PER_UNIT"), "defaultRate", "100", "direction", q("RECEIVABLE"), "active", "false"),
                etagOf("/api/v1/charge-catalogue/" + receivable, CATALOGUE), CATALOGUE), 204, null);
        expect("an inactive entry", charge(receivable, "TRIP", trip, "1", null, null), 409, "charge-inactive");

        UUID cancelled = raiseSingleDropTrip();
        expect("cancel the trip", post("/api/v1/trips/" + cancelled + "/cancellation", json("reason", q("Not needed")),
                TRIP_RIGHTS), 204, null);
        expect("a cancelled trip", charge(both, "TRIP", cancelled, "1", null, "RECEIVABLE"), 409,
                "manual-charge-subject-cancelled");
    }

    @Test
    @DisplayName("a retry with the same Idempotency-Key is one charge, answered as the first was")
    void retriedRequest() {
        UUID perUnit = entry("HALT", "PER_UNIT", "100", "RECEIVABLE");
        String body = json("catalogueId", q(perUnit), "subjectType", q("TRIP"), "subjectId", q(trip), "quantity", "2",
                "reason", q("Waited"));
        Outcome first = postKeyed("/api/v1/manual-charges", body, "mc-" + UUID.randomUUID(), RECORD);
        String key = "mc-retry-" + UUID.randomUUID();
        Outcome one = postKeyed("/api/v1/manual-charges", body, key, RECORD);
        Outcome two = postKeyed("/api/v1/manual-charges", body, key, RECORD);
        assertThat(first.status()).isEqualTo(201);
        assertThat(one.status()).isEqualTo(201);
        assertThat(two.status()).isEqualTo(201);
        assertThat(two.body()).isEqualTo(one.body());
        assertThat(jdbc.sql("SELECT count(*) FROM manual_charge WHERE tenant_id = :t")
                .param("t", yard.tenantId()).query(Long.class).single()).isEqualTo(2);
    }

    // ------------------------------------------------------------- approvals

    @Test
    @DisplayName("above the threshold a charge waits; approved, a receivable is a client charge that an invoice prices at its own amount")
    void aboveTheThresholdBecomesAClientCharge() {
        UUID contract = clientContract();
        assertThat(contract).isNotNull();
        UUID detention = entry("DETENTION", "PER_HOUR", "300", "RECEIVABLE");

        Outcome asked = charge(detention, "TRIP", trip, "5", null, null);
        expect("waits for an approval", asked, 202, null);
        JsonNode pending = kit.parse(asked);
        String chargeId = pending.get("id").asString();
        assertThat(pending.get("approval").get("subjectType").asString()).isEqualTo("MANUAL_CHARGE");
        JsonNode waiting = chargeView(chargeId);
        assertThat(waiting.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(waiting.get("approvalId").asString()).isEqualTo(pending.get("approval").get("id").asString());
        assertThat(jdbc.sql("SELECT count(*) FROM client_charge WHERE tenant_id = :t AND service_type = 'MANUAL'")
                .param("t", yard.tenantId()).query(Long.class).single()).as("nothing is billed yet").isZero();

        approve(pending.get("approval"), "MANUAL_CHARGE_APPROVE");
        JsonNode approved = chargeView(chargeId);
        assertThat(approved.get("status").asString()).isEqualTo("APPROVED");
        assertThat(approved.hasNonNull("clientChargeId")).isTrue();
        Map<String, Object> row = jdbc.sql("""
                SELECT service_type, manual_amount, manual_rate, client_partner_id, source_key
                  FROM client_charge WHERE id = :id""").param("id", UUID.fromString(approved.get("clientChargeId")
                .asString())).query().singleRow();
        assertThat(row.get("service_type")).isEqualTo("MANUAL");
        assertThat((BigDecimal) row.get("manual_amount")).isEqualByComparingTo("1500");
        assertThat(row.get("client_partner_id")).isEqualTo(yard.customer());
        assertThat(row.get("source_key")).isEqualTo("MANUAL:" + chargeId);
        assertThat(audited("MANUAL_CHARGE_APPROVED", chargeId)).isEqualTo(1);

        // Put the charge in a month that has ended, run the billing for it, and read the line.
        LocalDate lastMonth = LocalDate.now().minusMonths(1).withDayOfMonth(1);
        jdbc.sql("UPDATE client_charge SET occurred_on = :d, billing_period = :d WHERE id = :id")
                .param("d", lastMonth).param("id", UUID.fromString(approved.get("clientChargeId").asString()))
                .update();
        Outcome run = post("/api/v1/client-billing/clients/" + yard.customer() + "/billing-runs",
                json("period", q(lastMonth)), "CLIENT_BILLING_RUN");
        expect("billing run", run, 200, null);
        JsonNode lines = kit.parse(get("/api/v1/client-billing/invoices/" + id(run) + "/lines", "CLIENT_BILLING_READ"));
        assertThat(lines).hasSize(1);
        JsonNode line = lines.get(0);
        assertThat(line.get("serviceType").asString()).isEqualTo("MANUAL");
        assertThat(dec(line, "amount")).as("priced by the charge, never by the contract's rates").isEqualByComparingTo("1500");
        assertThat(dec(line, "taxRate")).isEqualByComparingTo("18");
        assertThat(line.get("narrative").asString()).contains("DETENTION");

        assertThat(chargeView(chargeId).get("billed").asBoolean()).isTrue();
        expect("cancelling a billed charge", send("POST", "/api/v1/manual-charges/" + chargeId + "/cancellation",
                json("reason", q("Mistake")), etagOf("/api/v1/manual-charges/" + chargeId, RECORD), RECORD), 409,
                "manual-charge-already-billed");
    }

    @Test
    @DisplayName("a rejected approval leaves a charge that never took effect; a pending one can be withdrawn by cancelling it")
    void rejectedAndWithdrawn() {
        UUID detention = entry("DETENTION", "PER_HOUR", "300", "RECEIVABLE");
        JsonNode rejected = kit.parse(charge(detention, "TRIP", trip, "5", null, null));
        String request = rejected.get("approval").get("id").asString();
        Tenant decider = approver();
        String version = kit.parse(kit.get(decider, "/api/v1/approvals/" + request, "APPROVAL_READ"))
                .get("approval").get("version").asString();
        Outcome no = kit.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port
                        + "/api/v1/approvals/" + request + "/reject"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + kit.token(decider, "MANUAL_CHARGE_APPROVE", "APPROVAL_READ"))
                .header("If-Match", "\"" + version + "\"")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json("reason", q("Not agreed")))).build());
        expect("rejected", no, 204, null);
        assertThat(chargeView(rejected.get("id").asString()).get("status").asString()).isEqualTo("REJECTED");

        JsonNode withdrawn = kit.parse(charge(detention, "TRIP", trip, "5", null, null));
        String id = withdrawn.get("id").asString();
        String path = "/api/v1/manual-charges/" + id;
        expect("cancelled while pending", send("POST", path + "/cancellation", json("reason", q("Raised in error")),
                etagOf(path, RECORD), RECORD), 204, null);
        JsonNode view = chargeView(id);
        assertThat(view.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(view.get("cancelReason").asString()).isEqualTo("Raised in error");
        JsonNode ticket = kit.parse(kit.get(decider, "/api/v1/approvals/" + withdrawn.get("approval").get("id")
                .asString(), "APPROVAL_READ")).get("approval");
        assertThat(ticket.get("status").asString()).isEqualTo("CANCELLED");
        expect("cancelling twice", send("POST", path + "/cancellation", json("reason", q("Again")),
                etagOf(path, RECORD), RECORD), 409, "manual-charge-illegal-transition");
    }

    @Test
    @DisplayName("a receivable is cancelled before its invoice and its client charge goes with it")
    void cancelBeforeBilling() {
        clientContract();
        UUID perUnit = entry("HALT", "PER_UNIT", "100", "RECEIVABLE");
        String id = id(charge(perUnit, "TRIP", trip, "2", null, null));
        JsonNode view = chargeView(id);
        UUID clientCharge = UUID.fromString(view.get("clientChargeId").asString());
        String path = "/api/v1/manual-charges/" + id;
        expect("cancelled", send("POST", path + "/cancellation", json("reason", q("Customer disputed")),
                etagOf(path, RECORD), RECORD), 204, null);
        assertThat(jdbc.sql("SELECT count(*) FROM client_charge WHERE id = :id").param("id", clientCharge)
                .query(Long.class).single()).isZero();
        assertThat(chargeView(id).get("status").asString()).isEqualTo("CANCELLED");
        assertThat(audited("MANUAL_CHARGE_CANCELLED", id)).isEqualTo(1);
    }

    // -------------------------------------------------------------- payables

    @Test
    @DisplayName("a payable becomes a line of the trip's draft bill, and cancelling it takes the line off again")
    void payableOnADraftBill() {
        UUID bill = draftBill(trip, "10000");
        UUID perUnit = entry("TOLL", "PER_UNIT", "100", "PAYABLE");
        String id = id(charge(perUnit, "TRIP", trip, "3", null, null));
        JsonNode view = chargeView(id);
        assertThat(view.get("freightBillId").asString()).isEqualTo(bill.toString());
        assertThat(view.get("vendorPartnerId").asString()).isEqualTo(yard.vendor().toString());
        assertThat(dec(billView(bill), "computedAmount")).isEqualByComparingTo("10300");

        JsonNode lines = kit.parse(get("/api/v1/freight-bills/" + bill + "/lines", "INVOICE_READ"));
        assertThat(lines).hasSize(2);
        assertThat(lines.get(1).get("chargeType").asString()).isEqualTo("MANUAL_CHARGE");
        assertThat(dec(lines.get(1), "amount")).isEqualByComparingTo("300");

        JsonNode settlement = kit.parse(get("/api/v1/freight-bills/" + bill + "/settlement", "INVOICE_READ"));
        assertThat(dec(settlement, "chargesAmount")).isEqualByComparingTo("300");
        assertThat(dec(settlement, "freightAmount")).isEqualByComparingTo("10000");
        assertThat(dec(settlement, "approvedAmount")).isEqualByComparingTo("10300");

        String path = "/api/v1/manual-charges/" + id;
        expect("cancelled", send("POST", path + "/cancellation", json("reason", q("Toll was waived")),
                etagOf(path, RECORD), RECORD), 204, null);
        assertThat(dec(billView(bill), "computedAmount")).isEqualByComparingTo("10000");
        assertThat(kit.parse(get("/api/v1/freight-bills/" + bill + "/lines", "INVOICE_READ"))).hasSize(1);
        assertThat(chargeView(id).get("status").asString()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("a payable after the bill is approved is not added to it: it waits and becomes a line of the vendor's next bill")
    void payableAfterApprovalWaitsForTheNextBill() {
        UUID approved = approvedBill(trip, "10000");
        UUID perUnit = entry("TOLL", "PER_UNIT", "100", "PAYABLE");
        String id = id(charge(perUnit, "TRIP", trip, "3", null, null));
        JsonNode waiting = chargeView(id);
        assertThat(waiting.hasNonNull("freightBillId")).as("the approved bill is left as it is").isFalse();
        assertThat(dec(billView(approved), "computedAmount")).isEqualByComparingTo("10000");
        assertThat(billView(approved).get("status").asString()).isEqualTo("APPROVED_FOR_PAYMENT");
        assertThat(kit.parse(get("/api/v1/freight-bills/" + approved + "/lines", "INVOICE_READ"))).hasSize(1);

        rateCard("2");
        UUID second = raiseSingleDropTrip();
        UUID nextBill = as(List.of(), () -> {
            UUID load = jdbc.sql("SELECT load_id FROM trip WHERE id = :id").param("id", second).query(UUID.class)
                    .single();
            return billing.raiseBillFor(second, load, yard.vendor(), "TR-NEXT" + second.toString().substring(0, 4),
                    Instant.now(), Duration.ZERO, new BigDecimal("5000")).orElseThrow();
        });
        JsonNode next = billView(nextBill);
        assertThat(next.get("status").asString()).isEqualTo("DRAFT");
        JsonNode lines = kit.parse(get("/api/v1/freight-bills/" + nextBill + "/lines", "INVOICE_READ"));
        assertThat(lines.toString()).contains("MANUAL_CHARGE");
        BigDecimal freightLines = BigDecimal.ZERO;
        for (JsonNode line : lines) {
            if (!"MANUAL_CHARGE".equals(line.get("chargeType").asString())) {
                freightLines = freightLines.add(dec(line, "amount"));
            }
        }
        assertThat(dec(next, "computedAmount")).as("the rate card's freight plus the waiting charge")
                .isEqualByComparingTo(freightLines.add(new BigDecimal("300")));
        assertThat(chargeView(id).get("freightBillId").asString()).isEqualTo(nextBill.toString());
        assertThat(dec(billView(approved), "computedAmount")).as("never re-priced").isEqualByComparingTo("10000");
    }

    // ----------------------------------------------------- isolation and rights

    @Test
    @DisplayName("another tenant sees none of it and cannot charge against it")
    void tenantIsolation() {
        UUID perUnit = entry("HALT", "PER_UNIT", "100", "RECEIVABLE");
        String id = id(charge(perUnit, "TRIP", trip, "2", null, null));
        Tenant other = kit.newTenant("other");
        expect("their read", kit.get(other, "/api/v1/manual-charges/" + id, RECORD), 404, null);
        expect("their catalogue read", kit.get(other, "/api/v1/charge-catalogue/" + perUnit, CATALOGUE), 404, null);
        assertThat(kit.parse(kit.get(other, "/api/v1/manual-charges", RECORD)).get("items")).isEmpty();
        assertThat(kit.parse(kit.get(other, "/api/v1/charge-catalogue", CATALOGUE)).get("items")).isEmpty();
        Outcome theirs = kit.post(other, "/api/v1/charge-catalogue", json("code", q("HALT"), "name", q("Halt"), "basis",
                q("PER_UNIT"), "defaultRate", "1", "direction", q("RECEIVABLE")), CATALOGUE);
        expect("the same code in another tenant", theirs, 201, null);
        expect("our trip", kit.post(other, "/api/v1/manual-charges", json("catalogueId", q(id(theirs)), "subjectType",
                q("TRIP"), "subjectId", q(trip), "quantity", "1", "reason", q("x")), RECORD), 404, null);
        expect("our entry", kit.post(other, "/api/v1/manual-charges", json("catalogueId", q(perUnit), "subjectType",
                q("TRIP"), "subjectId", q(trip), "quantity", "1", "reason", q("x")), RECORD), 404, null);
    }

    @Test
    @DisplayName("each right is its own: recording, reading and cancelling")
    void rights() {
        UUID perUnit = entry("HALT", "PER_UNIT", "100", "RECEIVABLE");
        expect("record without the right", charge(perUnit, "TRIP", trip, "1", null, null, "INVOICE_READ"), 403, null);
        String id = id(charge(perUnit, "TRIP", trip, "1", null, null));
        expect("read without any charge right", get("/api/v1/manual-charges", "PARTNER_READ"), 403, null);
        expect("read with the approval right", get("/api/v1/manual-charges/" + id, "MANUAL_CHARGE_APPROVE"), 200, null);
        String path = "/api/v1/manual-charges/" + id;
        expect("cancel without the right", send("POST", path + "/cancellation", json("reason", q("x")),
                etagOf(path, RECORD), "MANUAL_CHARGE_APPROVE"), 403, null);
        expect("cancel without If-Match", send("POST", path + "/cancellation", json("reason", q("x")), null, RECORD),
                400, null);
        expect("cancel against a stale version", send("POST", path + "/cancellation", json("reason", q("x")),
                "\"9\"", RECORD), 409, "concurrent-modification");
    }

    // --------------------------------------------------------------- webhooks

    @Test
    @DisplayName("a charge coming into force is published to a subscribed webhook")
    void webhook() {
        Outcome subscribed = kit.post(yard.tenant(), "/api/v1/webhooks/subscriptions", json("name", q("money-hook"),
                "url", q("https://203.0.113.10/hooks/lms"), "eventTypes", "[\"MANUAL_CHARGE_APPROVED\"]"),
                "WEBHOOK_MANAGE", "WEBHOOK_READ");
        assertThat(subscribed.status()).as(subscribed.body()).isEqualTo(201);
        UUID perUnit = entry("HALT", "PER_UNIT", "100", "RECEIVABLE");
        String id = id(charge(perUnit, "TRIP", trip, "2", null, null));
        JsonNode deliveries = kit.parse(kit.get(yard.tenant(), "/api/v1/webhooks/subscriptions/" + id(subscribed)
                + "/deliveries?limit=50", "WEBHOOK_READ")).get("items");
        assertThat(deliveries).hasSize(1);
        assertThat(deliveries.get(0).get("eventType").asString()).isEqualTo("MANUAL_CHARGE_APPROVED");
        assertThat(id).isNotNull();
    }
}
