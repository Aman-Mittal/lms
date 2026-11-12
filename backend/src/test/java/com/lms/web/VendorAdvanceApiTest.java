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
 * Vendor advances over HTTP (issue #225, YRD-028 to 032): requested against a
 * trip, approved through the approvals module, paid with a reference and
 * deducted when the vendor's invoice settles the trip, within the cap and one
 * per mode, cancellable only before payment.
 */
class VendorAdvanceApiTest extends MoneyApiSupport {

    private static final LocalDate TODAY = LocalDate.now();
    private UUID trip;

    @BeforeEach
    void aTrip() {
        trip = raiseSingleDropTrip();
    }

    private static void expect(String what, Outcome outcome, int status, String code) {
        assertThat(outcome.status()).as(what + ": " + outcome.body()).isEqualTo(status);
        if (code != null) {
            assertThat(outcome.code()).as(what).isEqualTo(code);
        }
    }

    private Outcome request(UUID forTrip, String mode, String amount, String... permissions) {
        return post("/api/v1/vendor-advances", json("tripId", q(forTrip), "mode", q(mode), "amount", amount,
                "reason", q("Fuel and tolls")), permissions.length == 0 ? new String[] {ADVANCE} : permissions);
    }

    private JsonNode advance(String id) {
        Outcome got = get("/api/v1/vendor-advances/" + id, ADVANCE);
        assertThat(got.status()).as(got.body()).isEqualTo(200);
        return kit.parse(got);
    }

    private Outcome act(String id, String verb, String body, String... permissions) {
        String path = "/api/v1/vendor-advances/" + id;
        return send("POST", path + "/" + verb, body, etagOf(path, ADVANCE), permissions);
    }

    /** Requests, approves and returns the advance's id. */
    private String approved(UUID forTrip, String mode, String amount) {
        Outcome asked = request(forTrip, mode, amount);
        expect("requested", asked, 202, null);
        approve(kit.parse(asked).get("approval"), "ADVANCE_APPROVE");
        return kit.parse(asked).get("id").asString();
    }

    private Outcome pay(String id, String reference) {
        return act(id, "payment", json("reference", q(reference), "paidOn", q(TODAY)), PAYABLES);
    }

    // ------------------------------------------------------------- the journey

    @Test
    @DisplayName("requested, approved, paid and then deducted from the vendor's invoice, with the net payable shown")
    void fromRequestToDeduction() {
        UUID bill = draftBill(trip, "10000");
        Outcome subscribed = kit.post(yard.tenant(), "/api/v1/webhooks/subscriptions", json("name", q("adv-hook"),
                "url", q("https://203.0.113.10/hooks/lms"), "eventTypes", "[\"VENDOR_ADVANCE_PAID\"]"),
                "WEBHOOK_MANAGE", "WEBHOOK_READ");
        assertThat(subscribed.status()).as(subscribed.body()).isEqualTo(201);

        Outcome asked = request(trip, "BANK", "4000");
        expect("waits for approval", asked, 202, null);
        String id = kit.parse(asked).get("id").asString();
        JsonNode waiting = advance(id);
        assertThat(waiting.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(waiting.get("advanceNo").asString()).startsWith("ADV-");
        assertThat(dec(waiting, "freightReference")).isEqualByComparingTo("10000");
        assertThat(waiting.get("freightSource").asString()).isEqualTo("BILL");
        assertThat(dec(waiting, "maxPercent")).isEqualByComparingTo("70");
        expect("paying what is not approved", pay(id, "NEFT-ADV-0"), 409, "advance-illegal-transition");

        approve(kit.parse(asked).get("approval"), "ADVANCE_APPROVE");
        assertThat(advance(id).get("status").asString()).isEqualTo("APPROVED");

        expect("a reference is needed", act(id, "payment", json("reference", q(" "), "paidOn", q(TODAY)), PAYABLES), 400,
                null);
        expect("without the payables right", act(id, "payment", json("reference", q("R"), "paidOn", q(TODAY)), ADVANCE),
                403, null);
        expect("paid", pay(id, "NEFT-ADV-1"), 204, null);
        JsonNode paid = advance(id);
        assertThat(paid.get("status").asString()).isEqualTo("PAID");
        assertThat(paid.get("paidReference").asString()).isEqualTo("NEFT-ADV-1");
        assertThat(paid.get("paidOn").asString()).isEqualTo(TODAY.toString());
        expect("paid twice", pay(id, "NEFT-ADV-2"), 409, "advance-illegal-transition");
        expect("cancelled after payment", act(id, "cancellation", json("reason", q("Changed mind")), ADVANCE), 409,
                "advance-already-paid");
        assertThat(audited("VENDOR_ADVANCE_REQUESTED", id)).isEqualTo(1);
        assertThat(audited("VENDOR_ADVANCE_APPROVED", id)).isEqualTo(1);
        assertThat(audited("VENDOR_ADVANCE_PAID", id)).isEqualTo(1);

        JsonNode before = kit.parse(get("/api/v1/freight-bills/" + bill + "/settlement", PAYABLES));
        assertThat(dec(before, "approvedAmount")).isEqualByComparingTo("10000");
        assertThat(dec(before, "advancesPaid")).isEqualByComparingTo("4000");
        assertThat(dec(before, "advancesDeducted")).isEqualByComparingTo("0");
        assertThat(dec(before, "netPayable")).isEqualByComparingTo("6000");
        assertThat(before.get("advances")).hasSize(1);

        approveBill(bill, "10000");
        Outcome invoiced = post("/api/v1/vendor-invoices", json("vendorInvoiceNo", q("V-ADV-1"), "invoiceDate",
                q(TODAY), "amount", "10000", "taxAmount", "0", "freightBillIds", "[" + q(bill) + "]"), PAYABLES);
        expect("invoiced", invoiced, 201, null);
        JsonNode invoice = kit.parse(get("/api/v1/vendor-invoices/" + id(invoiced), PAYABLES));
        assertThat(dec(invoice, "advancesDeducted")).isEqualByComparingTo("4000");
        assertThat(dec(invoice, "netPayable")).isEqualByComparingTo("6000");
        assertThat(dec(invoice, "outstanding")).isEqualByComparingTo("6000");
        JsonNode deducted = advance(id);
        assertThat(deducted.get("status").asString()).isEqualTo("DEDUCTED");
        assertThat(deducted.get("deductedInvoiceId").asString()).isEqualTo(id(invoiced));

        JsonNode after = kit.parse(get("/api/v1/freight-bills/" + bill + "/settlement", PAYABLES));
        assertThat(dec(after, "advancesDeducted")).isEqualByComparingTo("4000");
        assertThat(dec(after, "netPayable")).isEqualByComparingTo("6000");
        assertThat(after.get("invoiceNo").asString()).isEqualTo(invoice.get("invoiceNo").asString());
        assertThat(dec(after, "invoiceOutstanding")).isEqualByComparingTo("6000");

        expect("the balance", post("/api/v1/vendor-invoices/" + id(invoiced) + "/payments", json("amount", "6000",
                "paidOn", q(TODAY), "reference", q("NEFT-BAL")), PAYABLES), 201, null);
        expect("nothing more is owed", post("/api/v1/vendor-invoices/" + id(invoiced) + "/payments", json("amount",
                "1", "paidOn", q(TODAY), "reference", q("NEFT-BAL2")), PAYABLES), 409, "vendor-payment-exceeds-balance");
        assertThat(kit.parse(get("/api/v1/vendor-invoices/" + id(invoiced), PAYABLES)).get("status").asString())
                .isEqualTo("PAID");

        JsonNode deliveries = kit.parse(kit.get(yard.tenant(), "/api/v1/webhooks/subscriptions/" + id(subscribed)
                + "/deliveries?limit=50", "WEBHOOK_READ")).get("items");
        assertThat(deliveries).hasSize(1);
        assertThat(deliveries.get(0).get("eventType").asString()).isEqualTo("VENDOR_ADVANCE_PAID");
    }

    // ---------------------------------------------------------------- the cap

    @Test
    @DisplayName("all live advances on a trip together stay within the percentage of its freight, and cancelling gives it back")
    void theCap() {
        draftBill(trip, "10000");
        String bank = approved(trip, "BANK", "4000");
        String slip = approved(trip, "FUEL_SLIP", "3000");
        Outcome over = request(trip, "CASH", "1");
        expect("past seventy percent of 10 000", over, 409, "advance-exceeds-cap");
        assertThat(over.body()).contains("At most 0.00 more");

        expect("a pending advance is cancelled", act(bank, "cancellation", json("reason", q("Not needed")), ADVANCE), 204,
                null);
        JsonNode cancelled = advance(bank);
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("cancelReason").asString()).isEqualTo("Not needed");
        expect("the cap is given back", request(trip, "CASH", "4000"), 202, null);
        expect("only that much", request(trip, "BANK", "1.01"), 409, "advance-exceeds-cap");
        expect("cancelled twice", act(bank, "cancellation", json("reason", q("Again")), ADVANCE), 409,
                "advance-illegal-transition");
        assertThat(advance(slip).get("status").asString()).isEqualTo("APPROVED");
        assertThat(audited("VENDOR_ADVANCE_CANCELLED", bank)).isEqualTo(1);
    }

    @Test
    @DisplayName("the cap is the tenant's setting")
    void theCapIsASetting() {
        draftBill(trip, "10000");
        setting(Map.of(FinanceSettingDefinitions.ADVANCE_MAX_PERCENT_OF_FREIGHT, new BigDecimal("50")));
        expect("above fifty percent", request(trip, "BANK", "5000.01"), 409, "advance-exceeds-cap");
        expect("at fifty percent", request(trip, "BANK", "5000"), 202, null);
    }

    @Test
    @DisplayName("one advance per mode per trip while it is alive")
    void oneAdvancePerMode() {
        draftBill(trip, "10000");
        String first = approved(trip, "BANK", "1000");
        expect("the mode again", request(trip, "BANK", "1000"), 409, "advance-mode-already-used");
        expect("another mode", request(trip, "CASH", "1000"), 202, null);
        expect("cancelled", act(first, "cancellation", json("reason", q("Wrong vendor account")), ADVANCE), 204, null);
        expect("the mode is given back", request(trip, "BANK", "1000"), 202, null);
    }

    @Test
    @DisplayName("without a bill the cap is measured against the platform's price of the load on the rate card")
    void freightEstimate() {
        expect("no bill and no rate card", request(trip, "BANK", "100"), 409, "advance-freight-unknown");
        rateCard("2");
        Outcome ok = request(trip, "BANK", "7000");
        expect("seventy percent of the estimate", ok, 202, null);
        JsonNode view = advance(kit.parse(ok).get("id").asString());
        assertThat(view.get("freightSource").asString()).isEqualTo("ESTIMATE");
        assertThat(dec(view, "freightReference")).isEqualByComparingTo("10000");
        expect("past it", request(trip, "CASH", "0.01"), 409, "advance-exceeds-cap");
    }

    @Test
    @DisplayName("when the tenant's policy asks for no approval an advance is approved as it is requested")
    void withoutApproval() {
        draftBill(trip, "10000");
        Outcome off = send("PUT", "/api/v1/approvals/policies/VENDOR_ADVANCE", json("required", "false",
                "decidePermissions", "[\"ADVANCE_APPROVE\"]"), null, "APPROVAL_POLICY_MANAGE");
        assertThat(off.status()).as(off.body()).isEqualTo(200);
        Outcome asked = request(trip, "BANK", "1000");
        expect("approved at once", asked, 201, null);
        assertThat(advance(id(asked)).get("status").asString()).isEqualTo("APPROVED");
        expect("paid", pay(id(asked), "NEFT-X"), 204, null);
    }

    @Test
    @DisplayName("a cancelled trip takes no advance")
    void cancelledTrip() {
        draftBill(trip, "10000");
        expect("cancel the trip", post("/api/v1/trips/" + trip + "/cancellation", json("reason", q("No lorry")),
                TRIP_RIGHTS), 204, null);
        expect("an advance", request(trip, "BANK", "100"), 409, "advance-trip-cancelled");
    }

    // ------------------------------------------------------- isolation and rights

    @Test
    @DisplayName("a retry with the same Idempotency-Key is one advance")
    void retried() {
        draftBill(trip, "10000");
        String body = json("tripId", q(trip), "mode", q("BANK"), "amount", "1000", "reason", q("Fuel"));
        String key = "adv-" + UUID.randomUUID();
        Outcome one = postKeyed("/api/v1/vendor-advances", body, key, ADVANCE);
        Outcome two = postKeyed("/api/v1/vendor-advances", body, key, ADVANCE);
        assertThat(one.status()).isEqualTo(202);
        assertThat(two.body()).isEqualTo(one.body());
        assertThat(jdbc.sql("SELECT count(*) FROM vendor_advance WHERE tenant_id = :t").param("t", yard.tenantId())
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("each right is its own, and another tenant sees nothing")
    void isolationAndRights() {
        draftBill(trip, "10000");
        expect("request without the right", request(trip, "BANK", "100", "INVOICE_READ"), 403, null);
        String id = approved(trip, "BANK", "1000");
        expect("read without any advance right", get("/api/v1/vendor-advances", "PARTNER_READ"), 403, null);
        expect("read with the approval right", get("/api/v1/vendor-advances/" + id, "ADVANCE_APPROVE"), 200, null);
        expect("cancel without the right", act(id, "cancellation", json("reason", q("x")), "ADVANCE_APPROVE"), 403, null);
        expect("cancel without If-Match", send("POST", "/api/v1/vendor-advances/" + id + "/cancellation",
                json("reason", q("x")), null, ADVANCE), 400, null);
        expect("cancel against a stale version", send("POST", "/api/v1/vendor-advances/" + id + "/cancellation",
                json("reason", q("x")), "\"9\"", ADVANCE), 409, "concurrent-modification");
        List<String> listed = new java.util.ArrayList<>();
        kit.parse(get("/api/v1/vendor-advances?tripId=" + trip + "&status=APPROVED", ADVANCE)).get("items")
                .forEach(i -> listed.add(i.get("id").asString()));
        assertThat(listed).containsExactly(id);

        Tenant other = kit.newTenant("other");
        expect("their read", kit.get(other, "/api/v1/vendor-advances/" + id, ADVANCE), 404, null);
        assertThat(kit.parse(kit.get(other, "/api/v1/vendor-advances", ADVANCE)).get("items")).isEmpty();
        expect("their request on our trip", kit.post(other, "/api/v1/vendor-advances", json("tripId", q(trip), "mode",
                q("CASH"), "amount", "1", "reason", q("x")), ADVANCE), 404, null);
        expect("their settlement of our bill", kit.get(other, "/api/v1/freight-bills/" + UUID.randomUUID()
                + "/settlement", PAYABLES), 404, null);
    }
}
