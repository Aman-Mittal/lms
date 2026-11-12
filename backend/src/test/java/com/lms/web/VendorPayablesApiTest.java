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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import com.lms.finance.command.FinanceSettingDefinitions;
import com.lms.web.ApiTestKit.Outcome;
import com.lms.web.ApiTestKit.Tenant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vendor payables over HTTP (issue #225): an invoice captured against approved
 * freight bills and matched with the same tolerance as the bill's own claim,
 * due by the vendor's payment terms, part and full payments with a balance, the
 * overdue list, a race for the last of the balance, isolation, rights and the
 * webhooks.
 */
class VendorPayablesApiTest extends MoneyApiSupport {

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

    private Outcome capture(String vendorNo, LocalDate date, String amount, String tax, UUID... bills) {
        List<String> ids = new ArrayList<>();
        for (UUID bill : bills) {
            ids.add(q(bill));
        }
        return post("/api/v1/vendor-invoices", json("vendorInvoiceNo", q(vendorNo), "invoiceDate", q(date),
                "amount", amount, "taxAmount", tax, "freightBillIds", ids.toString(), "documentRef", q("DOC-1")),
                PAYABLES);
    }

    private JsonNode invoice(String id) {
        Outcome got = get("/api/v1/vendor-invoices/" + id, PAYABLES);
        assertThat(got.status()).as(got.body()).isEqualTo(200);
        return kit.parse(got);
    }

    private Outcome pay(String invoice, String amount, LocalDate on, String reference) {
        return post("/api/v1/vendor-invoices/" + invoice + "/payments", json("amount", amount, "paidOn", q(on),
                "mode", q("BANK_TRANSFER"), "reference", q(reference)), PAYABLES);
    }

    // ---------------------------------------------------------------- capture

    @Test
    @DisplayName("an invoice is matched against what the bills were approved at, with the freight bill's own tolerance")
    void captureAndMatch() {
        UUID first = approvedBill("10000");
        UUID second = approvedBill("5000");
        UUID draft = draftBill(raiseSingleDropTrip(), "700");

        expect("far from what was approved", capture("V-001", TODAY, "16000", "0", first, second), 409,
                "vendor-invoice-mismatch");
        assertThat(jdbc.sql("SELECT count(*) FROM vendor_invoice WHERE tenant_id = :t").param("t", yard.tenantId())
                .query(Long.class).single()).as("nothing was recorded").isZero();
        expect("a bill not approved for payment", capture("V-001", TODAY, "700", "0", draft), 409,
                "vendor-invoice-bill-not-approved");
        expect("no bills", post("/api/v1/vendor-invoices", json("vendorInvoiceNo", q("V-0"), "invoiceDate", q(TODAY),
                "amount", "1", "freightBillIds", "[]"), PAYABLES), 400, null);
        expect("a bill that is not there", capture("V-001", TODAY, "100", "0", UUID.randomUUID()), 404, null);

        // 15 200 against 15 000 approved: inside 2 percent (300), the default allowance.
        Outcome captured = capture("V-001", TODAY.minusDays(40), "15200", "2736", first, second);
        expect("captured", captured, 201, null);
        JsonNode view = invoice(id(captured));
        assertThat(view.get("invoiceNo").asString()).startsWith("VI-");
        assertThat(view.get("vendorInvoiceNo").asString()).isEqualTo("V-001");
        assertThat(dec(view, "approvedAmount")).isEqualByComparingTo("15000");
        assertThat(dec(view, "varianceAmount")).isEqualByComparingTo("200");
        assertThat(dec(view, "totalAmount")).isEqualByComparingTo("17936");
        assertThat(dec(view, "outstanding")).isEqualByComparingTo("17936");
        assertThat(view.get("paymentTermsDays").asInt()).as("thirty days unless the vendor's terms say otherwise")
                .isEqualTo(30);
        assertThat(view.get("dueDate").asString()).isEqualTo(TODAY.minusDays(10).toString());
        assertThat(view.get("status").asString()).isEqualTo("OPEN");
        assertThat(view.get("overdue").asBoolean()).isTrue();
        assertThat(view.get("daysOverdue").asInt()).isEqualTo(10);
        assertThat(view.get("billCount").asInt()).isEqualTo(2);
        JsonNode bills = kit.parse(get("/api/v1/vendor-invoices/" + id(captured) + "/bills", PAYABLES));
        assertThat(bills).hasSize(2);
        assertThat(audited("VENDOR_INVOICE_CAPTURED", id(captured))).isEqualTo(1);

        expect("the same bill twice", capture("V-002", TODAY, "10000", "0", first), 409,
                "vendor-invoice-bill-already-invoiced");
        UUID third = approvedBill("900");
        expect("the vendor's number again, in another case", capture("v-001", TODAY, "900", "0", third), 409,
                "vendor-invoice-duplicate");
        expect("a payment before the invoice", pay(id(captured), "1", TODAY.minusDays(41), "REF-EARLY"), 409,
                "vendor-payment-date-invalid");
    }

    @Test
    @DisplayName("the tolerance is the tenant's: tightened to nothing, a rupee off is refused")
    void toleranceIsTheTenants() {
        setting(Map.of(FinanceSettingDefinitions.MATCH_TOLERANCE_PERCENT, BigDecimal.ZERO,
                FinanceSettingDefinitions.MATCH_TOLERANCE_ABSOLUTE, BigDecimal.ZERO));
        UUID bill = approvedBill("10000");
        expect("one rupee over", capture("V-T1", TODAY, "10001", "0", bill), 409, "vendor-invoice-mismatch");
        expect("one rupee under", capture("V-T1", TODAY, "9999", "0", bill), 409, "vendor-invoice-mismatch");
        expect("exact", capture("V-T1", TODAY, "10000", "0", bill), 201, null);
    }

    @Test
    @DisplayName("the due date follows the payment terms setting")
    void dueDateFollowsTheTerms() {
        setting(Map.of(FinanceSettingDefinitions.PAYMENT_TERMS_DAYS, 45));
        UUID bill = approvedBill("1000");
        JsonNode view = invoice(id(capture("V-D1", TODAY, "1000", "0", bill)));
        assertThat(view.get("paymentTermsDays").asInt()).isEqualTo(45);
        assertThat(view.get("dueDate").asString()).isEqualTo(TODAY.plusDays(45).toString());
        assertThat(view.get("overdue").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("a retried capture with the same Idempotency-Key is one invoice")
    void retriedCapture() {
        UUID bill = approvedBill("1000");
        String body = json("vendorInvoiceNo", q("V-K1"), "invoiceDate", q(TODAY), "amount", "1000",
                "freightBillIds", "[" + q(bill) + "]");
        String key = "inv-" + UUID.randomUUID();
        Outcome one = postKeyed("/api/v1/vendor-invoices", body, key, PAYABLES);
        Outcome two = postKeyed("/api/v1/vendor-invoices", body, key, PAYABLES);
        assertThat(one.status()).isEqualTo(201);
        assertThat(two.body()).isEqualTo(one.body());
        assertThat(jdbc.sql("SELECT count(*) FROM vendor_invoice WHERE tenant_id = :t").param("t", yard.tenantId())
                .query(Long.class).single()).isEqualTo(1);
    }

    // --------------------------------------------------------------- payments

    @Test
    @DisplayName("part payments reduce what is outstanding until the invoice is paid, and never go past it")
    void partPayments() {
        Outcome subscribed = kit.post(yard.tenant(), "/api/v1/webhooks/subscriptions", json("name", q("pay-hook"),
                "url", q("https://203.0.113.10/hooks/lms"), "eventTypes",
                "[\"VENDOR_INVOICE_CAPTURED\",\"VENDOR_PAYMENT_RECORDED\"]"), "WEBHOOK_MANAGE", "WEBHOOK_READ");
        assertThat(subscribed.status()).as(subscribed.body()).isEqualTo(201);

        UUID bill = approvedBill("10000");
        String invoice = id(capture("V-P1", TODAY.minusDays(40), "10000", "1800", bill));
        assertThat(dec(invoice(invoice), "outstanding")).isEqualByComparingTo("11800");

        expect("a payment", pay(invoice, "4000", TODAY, "NEFT-1"), 201, null);
        JsonNode part = invoice(invoice);
        assertThat(part.get("status").asString()).isEqualTo("PARTIALLY_PAID");
        assertThat(dec(part, "paidAmount")).isEqualByComparingTo("4000");
        assertThat(dec(part, "outstanding")).isEqualByComparingTo("7800");
        assertThat(part.get("overdue").asBoolean()).as("still owed past the due date").isTrue();

        expect("more than is owed", pay(invoice, "7800.01", TODAY, "NEFT-2"), 409, "vendor-payment-exceeds-balance");
        expect("the same reference twice", pay(invoice, "100", TODAY, "neft-1"), 409, "vendor-payment-reference-taken");
        expect("a future date", pay(invoice, "100", TODAY.plusDays(10), "NEFT-F"), 409, "vendor-payment-date-invalid");
        expect("no reference", post("/api/v1/vendor-invoices/" + invoice + "/payments", json("amount", "1", "paidOn",
                q(TODAY)), PAYABLES), 400, null);
        expect("nothing paid", pay(invoice, "0", TODAY, "NEFT-0"), 400, null);

        expect("the rest", pay(invoice, "7800", TODAY, "NEFT-3"), 201, null);
        JsonNode paid = invoice(invoice);
        assertThat(paid.get("status").asString()).isEqualTo("PAID");
        assertThat(dec(paid, "outstanding")).isEqualByComparingTo("0");
        assertThat(paid.get("overdue").asBoolean()).isFalse();
        expect("nothing is left to pay", pay(invoice, "1", TODAY, "NEFT-4"), 409, "vendor-payment-exceeds-balance");

        JsonNode payments = kit.parse(get("/api/v1/vendor-invoices/" + invoice + "/payments", PAYABLES)).get("items");
        assertThat(payments).hasSize(2);
        JsonNode all = kit.parse(get("/api/v1/vendor-payments?vendorId=" + yard.vendor() + "&limit=1", PAYABLES));
        assertThat(all.get("items")).hasSize(1);
        assertThat(all.get("hasMore").asBoolean()).isTrue();
        assertThat(audited("VENDOR_PAYMENT_RECORDED", invoice)).isEqualTo(2);

        JsonNode deliveries = kit.parse(kit.get(yard.tenant(), "/api/v1/webhooks/subscriptions/" + id(subscribed)
                + "/deliveries?limit=50", "WEBHOOK_READ")).get("items");
        List<String> types = new ArrayList<>();
        deliveries.forEach(d -> types.add(d.get("eventType").asString()));
        assertThat(types).containsExactlyInAnyOrder("VENDOR_INVOICE_CAPTURED", "VENDOR_PAYMENT_RECORDED",
                "VENDOR_PAYMENT_RECORDED");
    }

    @Test
    @DisplayName("two payments racing for the last of the balance: one is recorded, the other refused")
    void raceForTheBalance() {
        UUID bill = approvedBill("10000");
        String invoice = id(capture("V-R1", TODAY, "10000", "0", bill));
        List<Callable<Outcome>> calls = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String reference = "RACE-" + i;
            calls.add(() -> pay(invoice, "10000", TODAY, reference));
        }
        List<Outcome> outcomes = ApiTestKit.race(calls, Duration.ofSeconds(30));
        assertThat(outcomes.stream().filter(o -> o.status() == 201)).hasSize(1);
        assertThat(outcomes.stream().filter(o -> o.status() == 409 && "vendor-payment-exceeds-balance".equals(o.code())))
                .hasSize(3);
        assertThat(dec(invoice(invoice), "paidAmount")).isEqualByComparingTo("10000");
    }

    // ---------------------------------------------------------------- overdue

    @Test
    @DisplayName("the overdue list is what is still owed past its due date, earliest first, and drops what is paid")
    void overdueList() {
        String oldest = id(capture("V-O1", TODAY.minusDays(60), "1000", "0", approvedBill("1000")));
        String older = id(capture("V-O2", TODAY.minusDays(40), "2000", "0", approvedBill("2000")));
        String current = id(capture("V-O3", TODAY, "3000", "0", approvedBill("3000")));

        JsonNode page = kit.parse(get("/api/v1/vendor-invoices?overdue=true&limit=1", PAYABLES));
        assertThat(page.get("items")).hasSize(1);
        assertThat(page.get("items").get(0).get("id").asString()).isEqualTo(oldest);
        assertThat(page.get("hasMore").asBoolean()).isTrue();
        JsonNode next = kit.parse(get("/api/v1/vendor-invoices?overdue=true&limit=1&cursor="
                + page.get("nextCursor").asString(), PAYABLES));
        assertThat(next.get("items").get(0).get("id").asString()).isEqualTo(older);
        assertThat(next.get("hasMore").asBoolean()).isFalse();

        expect("paid in full", pay(older, "2000", TODAY, "OVD-1"), 201, null);
        List<String> ids = new ArrayList<>();
        kit.parse(get("/api/v1/vendor-invoices?overdue=true", PAYABLES)).get("items")
                .forEach(i -> ids.add(i.get("id").asString()));
        assertThat(ids).containsExactly(oldest);
        assertThat(ids).doesNotContain(current);

        List<String> all = new ArrayList<>();
        kit.parse(get("/api/v1/vendor-invoices?vendorId=" + yard.vendor(), PAYABLES)).get("items")
                .forEach(i -> all.add(i.get("id").asString()));
        assertThat(all).containsExactly(current, older, oldest);
        assertThat(kit.parse(get("/api/v1/vendor-invoices?status=PAID", PAYABLES)).get("items")).hasSize(1);
        assertThat(kit.parse(get("/api/v1/vendor-invoices?q=v-o3", PAYABLES)).get("items")).hasSize(1);
    }

    // ------------------------------------------------------ isolation and rights

    @Test
    @DisplayName("another tenant sees nothing of a vendor invoice, and the rights are separate")
    void isolationAndRights() {
        UUID bill = approvedBill("1000");
        expect("without the right", post("/api/v1/vendor-invoices", json("vendorInvoiceNo", q("V-X"), "invoiceDate",
                q(TODAY), "amount", "1000", "freightBillIds", "[" + q(bill) + "]"), "INVOICE_READ"), 403, null);
        String invoice = id(capture("V-X1", TODAY, "1000", "0", bill));
        expect("pay without the right", post("/api/v1/vendor-invoices/" + invoice + "/payments", json("amount", "1",
                "paidOn", q(TODAY), "reference", q("R-1")), "INVOICE_READ"), 403, null);
        expect("read with the invoice-read right", get("/api/v1/vendor-invoices/" + invoice, "INVOICE_READ"), 200, null);
        expect("read without either", get("/api/v1/vendor-invoices", "PARTNER_READ"), 403, null);

        Tenant other = kit.newTenant("other");
        expect("their read", kit.get(other, "/api/v1/vendor-invoices/" + invoice, PAYABLES), 404, null);
        assertThat(kit.parse(kit.get(other, "/api/v1/vendor-invoices", PAYABLES)).get("items")).isEmpty();
        assertThat(kit.parse(kit.get(other, "/api/v1/vendor-invoices?overdue=true", PAYABLES)).get("items")).isEmpty();
        expect("their payment", kit.post(other, "/api/v1/vendor-invoices/" + invoice + "/payments", json("amount", "1",
                "paidOn", q(TODAY), "reference", q("R-2")), PAYABLES), 404, null);
        expect("their capture of our bill", kit.post(other, "/api/v1/vendor-invoices", json("vendorInvoiceNo", q("V-Y"),
                "invoiceDate", q(TODAY), "amount", "1000", "freightBillIds", "[" + q(bill) + "]"), PAYABLES), 404, null);
    }
}
