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

import java.time.LocalDate;
import java.util.UUID;

import com.lms.web.ApiTestKit.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Voiding a vendor invoice over HTTP (issue 247): only before anything is paid,
 * with a reason, releasing the bills and the advances it held.
 */
class VendorInvoiceVoidApiTest extends MoneyApiSupport {

    private static final LocalDate TODAY = LocalDate.now();

    private static void expect(String what, Outcome outcome, int status, String code) {
        assertThat(outcome.status()).as(what + ": " + outcome.body()).isEqualTo(status);
        if (code != null) {
            assertThat(outcome.code()).as(what).isEqualTo(code);
        }
    }

    /** An approved bill on a fresh trip. */
    private UUID approvedBill(String amount) {
        return approvedBill(raiseSingleDropTrip(), amount);
    }

    private Outcome capture(String vendorNo, String amount, UUID bill) {
        return post("/api/v1/vendor-invoices", json("vendorInvoiceNo", q(vendorNo), "invoiceDate", q(TODAY.minusDays(60)),
                "amount", amount, "taxAmount", "0", "freightBillIds", "[" + q(bill) + "]"), PAYABLES);
    }

    private JsonNode invoice(String id) {
        return kit.parse(get("/api/v1/vendor-invoices/" + id, PAYABLES));
    }

    private Outcome voidIt(String id, String reason, String ifMatch) {
        return send("POST", "/api/v1/vendor-invoices/" + id + "/void", reason == null ? "{}" : json("reason", q(reason)),
                ifMatch, PAYABLES);
    }

    private Outcome pay(String invoice, String amount, String reference) {
        return post("/api/v1/vendor-invoices/" + invoice + "/payments", json("amount", amount, "paidOn", q(TODAY),
                "mode", q("BANK_TRANSFER"), "reference", q(reference)), PAYABLES);
    }

    @Test
    @DisplayName("an unpaid invoice is voided with a reason: audited, no longer owed, and its bill can be invoiced again")
    void voidReleasesTheBill() {
        UUID bill = approvedBill("10000");
        String id = id(capture("V-VOID-1", "10000", bill));
        assertThat(invoice(id).get("overdue").asBoolean()).isTrue();
        expect("the bill is held", capture("V-VOID-2", "10000", bill), 409, "vendor-invoice-bill-already-invoiced");

        expect("no reason", voidIt(id, null, null), 400, null);
        expect("a blank reason", voidIt(id, " ", null), 400, null);
        expect("a stale version", voidIt(id, "Wrong vendor", "\"9\""), 409, "concurrent-modification");
        assertThat(invoice(id).get("status").asString()).isEqualTo("OPEN");
        expect("without the payables right", send("POST", "/api/v1/vendor-invoices/" + id + "/void",
                json("reason", q("x")), null, "INVOICE_READ"), 403, null);

        expect("voided", voidIt(id, "Wrong vendor", etagOf("/api/v1/vendor-invoices/" + id, PAYABLES)), 204, null);
        JsonNode voided = invoice(id);
        assertThat(voided.get("status").asString()).isEqualTo("VOID");
        assertThat(voided.get("voidReason").asString()).isEqualTo("Wrong vendor");
        assertThat(voided.get("overdue").asBoolean()).as("a void invoice is owed nothing").isFalse();
        assertThat(audited("VENDOR_INVOICE_VOIDED", id)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT after_state ->> 'reason' FROM audit_log WHERE action = 'VENDOR_INVOICE_VOIDED'"
                + " AND resource_id = CAST(:r AS uuid)").param("r", id).query(String.class).single())
                .isEqualTo("Wrong vendor");
        JsonNode overdue = kit.parse(get("/api/v1/vendor-invoices?overdue=true", PAYABLES)).get("items");
        overdue.forEach(item -> assertThat(item.get("id").asString()).isNotEqualTo(id));

        expect("voided twice", voidIt(id, "Again", null), 409, "vendor-invoice-already-void");
        expect("a payment on a void invoice", pay(id, "100", "NEFT-VOID"), 409, "vendor-invoice-void");
        Outcome again = capture("V-VOID-2", "10000", bill);
        expect("the released bill is invoiced afresh", again, 201, null);
        assertThat(invoice(id(again)).get("status").asString()).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("an invoice with a payment against it is not voidable")
    void paidIsNotVoidable() {
        UUID bill = approvedBill("10000");
        String id = id(capture("V-VOID-3", "10000", bill));
        expect("part paid", pay(id, "4000", "NEFT-P1"), 201, null);
        expect("partly paid", voidIt(id, "Too late", null), 409, "vendor-invoice-not-voidable");
        expect("the rest", pay(id, "6000", "NEFT-P2"), 201, null);
        expect("paid", voidIt(id, "Too late", null), 409, "vendor-invoice-not-voidable");
        assertThat(invoice(id).get("status").asString()).isEqualTo("PAID");
        assertThat(audited("VENDOR_INVOICE_VOIDED", id)).isZero();
        expect("an invoice that is not there", voidIt(UUID.randomUUID().toString(), "x", null), 404, null);
    }

    @Test
    @DisplayName("the advances a voided invoice took off are paid again, ready for the next invoice")
    void voidGivesTheAdvanceBack() {
        UUID trip = raiseSingleDropTrip();
        UUID bill = draftBill(trip, "10000");
        Outcome asked = post("/api/v1/vendor-advances", json("tripId", q(trip), "mode", q("BANK"), "amount", "4000",
                "reason", q("Fuel and tolls")), ADVANCE);
        expect("requested", asked, 202, null);
        approve(kit.parse(asked).get("approval"), "ADVANCE_APPROVE");
        String advance = kit.parse(asked).get("id").asString();
        String advancePath = "/api/v1/vendor-advances/" + advance;
        expect("paid", send("POST", advancePath + "/payment", json("reference", q("NEFT-VOID-ADV"), "paidOn", q(TODAY)),
                etagOf(advancePath, ADVANCE), PAYABLES), 204, null);
        approveBill(bill, "10000");

        String id = id(capture("V-VOID-4", "10000", bill));
        assertThat(kit.parse(get(advancePath, ADVANCE)).get("status").asString()).isEqualTo("DEDUCTED");
        assertThat(dec(invoice(id), "advancesDeducted")).isEqualByComparingTo("4000");

        expect("voided", voidIt(id, "Raised against the wrong bill", null), 204, null);
        JsonNode restored = kit.parse(get(advancePath, ADVANCE));
        assertThat(restored.get("status").asString()).isEqualTo("PAID");
        assertThat(restored.has("deductedInvoiceId") && !restored.get("deductedInvoiceId").isNull()).isFalse();

        String next = id(capture("V-VOID-5", "10000", bill));
        assertThat(kit.parse(get(advancePath, ADVANCE)).get("status").asString()).isEqualTo("DEDUCTED");
        assertThat(dec(invoice(next), "advancesDeducted")).isEqualByComparingTo("4000");
    }
}
