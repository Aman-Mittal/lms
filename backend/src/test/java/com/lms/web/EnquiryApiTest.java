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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import com.lms.order.command.enquiry.EnquiryDeadlines;
import com.lms.order.command.enquiry.EnquirySettingDefinitions;
import com.lms.settings.api.Settings;
import com.lms.shared.tenant.TenantContext;
import com.lms.web.ApiTestKit.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The enquiry desk over HTTP with real permissions and a tenant of its own per
 * scenario: creation with cost discovery and a fixed deadline, quotes issued,
 * revised and revoked under {@code If-Match}, outcomes per line, idempotent
 * conversion to a draft order, cancellation, the deadline sweep that never
 * touches what was won, and isolation between tenants.
 */
class EnquiryApiTest extends OrderIntakeApiBase {

    @Autowired
    private EnquiryDeadlines deadlines;
    @Autowired
    private Settings settings;

    // ------------------------------------------------------------- helpers

    private String line(UUID from, UUID to, String quantity, String uom, String weight) {
        return "{\"originTerminalId\":\"" + from + "\",\"destinationTerminalId\":\"" + to + "\",\"quantity\":"
                + quantity + ",\"uom\":\"" + uom + "\"" + (weight == null ? "" : ",\"weightKg\":" + weight) + "}";
    }

    private String body(String... lines) {
        return "{\"orgUnitId\":\"" + orgUnitId + "\",\"customerPartnerId\":\"" + client
                + "\",\"reference\":\"RFQ-" + salt + "\",\"lines\":[" + String.join(",", lines) + "]}";
    }

    private UUID create(String... lines) {
        JsonNode created = ok(call("POST", "/api/v1/enquiries", body(lines), null), 201);
        return UUID.fromString(created.get("id").asString());
    }

    private JsonNode get(UUID id) {
        return ok(call("GET", "/api/v1/enquiries/" + id, null, null), 200);
    }

    private String version(UUID id) {
        return "\"" + get(id).get("version").asLong() + "\"";
    }

    private UUID lineId(UUID id, int index) {
        return UUID.fromString(get(id).get("lines").get(index).get("id").asString());
    }

    private String path(UUID id, UUID line, String suffix) {
        return "/api/v1/enquiries/" + id + "/lines/" + line + suffix;
    }

    private Outcome quote(UUID id, UUID line, String amount) {
        return call("POST", path(id, line, "/quote"), "{\"amount\":" + amount + "}", version(id));
    }

    private Outcome decide(UUID id, UUID line, String outcome, String extra) {
        return call("POST", path(id, line, "/outcome"),
                "{\"outcome\":\"" + outcome + "\"" + (extra == null ? "" : "," + extra) + "}", version(id));
    }

    private void expireNow(UUID id) {
        jdbc.sql("UPDATE enquiry SET deadline_at = now() - interval '1 hour' WHERE id = :id").param("id", id).update();
    }

    // -------------------------------------------------------------- create

    @Test
    @DisplayName("an enquiry is made with lines, a deadline from the policy and a rate suggested from the customer's contract lane")
    void createWithCostDiscovery() {
        activeContractWithLane("DISC-" + salt, "5.00");
        Instant before = Instant.now();
        UUID id = create(line(origin, destination, "10", "EA", "1000"), line(origin, elsewhere, "5", "EA", "400"));

        JsonNode enquiry = get(id);
        assertThat(enquiry.get("status").asString()).isEqualTo("OPEN");
        assertThat(enquiry.get("enquiryNo").asString()).startsWith("ENQ-");
        assertThat(enquiry.get("reference").asString()).isEqualTo("RFQ-" + salt);
        assertThat(Duration.between(before, Instant.parse(enquiry.get("deadlineAt").asString())))
                .as("72 hours by default").isBetween(Duration.ofHours(71), Duration.ofHours(73));
        assertThat(enquiry.get("lines")).hasSize(2);

        JsonNode served = enquiry.get("lines").get(0);
        assertThat(served.get("suggestedLaneCode").asString()).isEqualTo("MUM-DEL");
        assertThat(served.get("suggestedContractCode").asString()).isEqualTo("DISC-" + salt);
        assertThat(served.get("suggestedRateBasis").asString()).isEqualTo("PER_KG");
        assertThat(served.get("suggestedRate").decimalValue()).isEqualByComparingTo("5");
        assertThat(served.get("suggestedAmount").decimalValue()).isEqualByComparingTo("5000");
        assertThat(absent(enquiry.get("lines").get(1), "suggestedLaneCode"))
                .as("no lane runs to the second destination").isTrue();

        JsonNode listed = ok(call("GET", "/api/v1/enquiries?status=OPEN", null, null), 200).get("items").get(0);
        assertThat(listed.get("lineCount").asInt()).isEqualTo(2);
        assertThat(listed.get("lines")).isEmpty();
        assertThat(audited("ENQUIRY_CREATED")).isEqualTo(1);
    }

    @Test
    @DisplayName("the deadline is stored when the enquiry is made and never moves, whatever the policy or the edits")
    void deadlineIsFixed() {
        setDeadlineHours(24);
        Instant before = Instant.now();
        UUID id = create(line(origin, destination, "1", "EA", "100"));
        Instant deadline = Instant.parse(get(id).get("deadlineAt").asString());
        assertThat(Duration.between(before, deadline)).isBetween(Duration.ofHours(23), Duration.ofHours(25));

        setDeadlineHours(500);
        ok(quote(id, lineId(id, 0), "900"), 201);
        assertThat(Instant.parse(get(id).get("deadlineAt").asString())).as("not a sliding window").isEqualTo(deadline);
    }

    @Test
    @DisplayName("a bad enquiry is refused: no lines, an unknown terminal, a zero quantity, no right to create")
    void refusals() {
        refused(call("POST", "/api/v1/enquiries", body(), null), 400, null);
        refused(call("POST", "/api/v1/enquiries", body(line(origin, UUID.randomUUID(), "1", "EA", null)), null),
                404, null);
        refused(call("POST", "/api/v1/enquiries", body(line(origin, destination, "0", "EA", null)), null), 400, null);
        refused(call("POST", "/api/v1/enquiries", body(line(origin, origin, "1", "EA", null)), null), 400, null);
        refused(call(caller, "POST", "/api/v1/enquiries", body(line(origin, destination, "1", "EA", null)), null,
                "ORDER_UPDATE"), 403, null);
        assertThat(jdbc.sql("SELECT count(*) FROM enquiry WHERE tenant_id = :t").param("t", tenantId)
                .query(Long.class).single()).isZero();
    }

    // -------------------------------------------------------------- quotes

    @Test
    @DisplayName("a quote is issued, revised into a new revision and revoked, each under If-Match and audited")
    void quotes() {
        UUID id = create(line(origin, destination, "10", "EA", "1000"));
        UUID line = lineId(id, 0);

        refused(call("POST", path(id, line, "/quote"), "{\"amount\":100}", null), 400, null);
        refused(call("POST", path(id, line, "/quote"), "{\"amount\":100}", "\"99\""), 409, "concurrent-modification");
        refused(call(caller, "POST", path(id, line, "/quote"), "{\"amount\":100}", version(id), "ORDER_CREATE"),
                403, null);
        refused(quote(id, line, "0"), 400, null);
        refused(call("POST", path(id, line, "/quote"), "{\"amount\":100,\"validUntil\":\"2020-01-01T00:00:00Z\"}",
                version(id)), 409, "enquiry-quote-validity-invalid");
        refused(call("PUT", path(id, line, "/quote"), "{\"amount\":100}", version(id)), 409, "enquiry-quote-missing");

        String before = version(id);
        ok(quote(id, line, "950.50"), 201);
        assertThat(version(id)).as("every change moves the version").isNotEqualTo(before);
        refused(quote(id, line, "900"), 409, "enquiry-quote-exists");

        ok(call("PUT", path(id, line, "/quote"), "{\"amount\":920,\"note\":\"fuel included\"}", version(id)), 200);
        JsonNode quotes = get(id).get("lines").get(0).get("quotes");
        assertThat(quotes).hasSize(2);
        assertThat(quotes.get(0).get("status").asString()).isEqualTo("SUPERSEDED");
        assertThat(quotes.get(0).get("amount").decimalValue()).isEqualByComparingTo("950.50");
        assertThat(quotes.get(1).get("revision").asInt()).isEqualTo(2);
        assertThat(quotes.get(1).get("status").asString()).isEqualTo("ISSUED");
        assertThat(quotes.get(1).get("note").asString()).isEqualTo("fuel included");
        assertThat(Instant.parse(quotes.get(1).get("validUntil").asString())).isAfter(Instant.now().plusSeconds(3600));

        ok(call("POST", path(id, line, "/quote/revocation"), "{\"reason\":\"customer withdrew\"}", version(id)), 204);
        JsonNode revoked = get(id).get("lines").get(0).get("quotes").get(1);
        assertThat(revoked.get("status").asString()).isEqualTo("REVOKED");
        assertThat(revoked.get("revokeReason").asString()).isEqualTo("customer withdrew");
        refused(call("POST", path(id, line, "/quote/revocation"), null, version(id)), 409, "enquiry-quote-missing");

        ok(quote(id, line, "880"), 201);
        assertThat(get(id).get("lines").get(0).get("quotes").get(2).get("revision").asInt()).isEqualTo(3);
        assertThat(audited("ENQUIRY_QUOTE_ISSUED")).isEqualTo(2);
        assertThat(audited("ENQUIRY_QUOTE_REVISED")).isEqualTo(1);
        assertThat(audited("ENQUIRY_QUOTE_REVOKED")).isEqualTo(1);
    }

    // ------------------------------------------------------------ outcomes

    @Test
    @DisplayName("a line is decided once: lost needs a reason, partial a quantity inside the line, and the enquiry follows its lines")
    void outcomes() {
        UUID id = create(line(origin, destination, "10", "EA", "1000"), line(origin, destination, "10", "EA", "1000"),
                line(origin, destination, "10", "EA", "1000"));
        UUID won = lineId(id, 0);
        UUID partial = lineId(id, 1);
        UUID lost = lineId(id, 2);

        refused(decide(id, lost, "LOST", null), 409, "enquiry-lost-reason-required");
        refused(decide(id, partial, "PARTIAL", null), 409, "enquiry-partial-quantity-invalid");
        refused(decide(id, partial, "PARTIAL", "\"wonQuantity\":10"), 409, "enquiry-partial-quantity-invalid");
        refused(call("POST", path(id, won, "/outcome"), "{}", version(id)), 400, null);

        ok(decide(id, won, "WON", null), 204);
        ok(decide(id, partial, "PARTIAL", "\"wonQuantity\":4"), 204);
        assertThat(get(id).get("status").asString()).as("one line still open").isEqualTo("OPEN");
        refused(decide(id, won, "LOST", "\"reason\":\"changed mind\""), 409, "enquiry-line-not-open");
        refused(quote(id, won, "100"), 409, "enquiry-line-not-open");
        ok(decide(id, lost, "LOST", "\"reason\":\"a cheaper carrier\""), 204);

        JsonNode decided = get(id);
        assertThat(decided.get("status").asString()).isEqualTo("PARTIAL");
        assertThat(decided.get("wonLineCount").asInt()).isEqualTo(2);
        assertThat(decided.get("lines").get(0).get("wonQuantity").decimalValue()).isEqualByComparingTo("10");
        assertThat(decided.get("lines").get(1).get("status").asString()).isEqualTo("PARTIAL");
        assertThat(decided.get("lines").get(1).get("wonQuantity").decimalValue()).isEqualByComparingTo("4");
        assertThat(decided.get("lines").get(2).get("lostReason").asString()).isEqualTo("a cheaper carrier");
        refused(call("POST", "/api/v1/enquiries/" + id + "/cancel", "{\"reason\":\"x\"}", version(id)), 409,
                "enquiry-not-open");

        UUID everyoneWon = create(line(origin, destination, "1", "EA", "10"));
        ok(decide(everyoneWon, lineId(everyoneWon, 0), "WON", null), 204);
        assertThat(get(everyoneWon).get("status").asString()).isEqualTo("WON");
        UUID allLost = create(line(origin, destination, "1", "EA", "10"));
        ok(decide(allLost, lineId(allLost, 0), "LOST", "\"reason\":\"price\""), 204);
        assertThat(get(allLost).get("status").asString()).isEqualTo("LOST");
        assertThat(audited("ENQUIRY_LINE_WON")).isEqualTo(2);
        assertThat(audited("ENQUIRY_LINE_PARTIAL")).isEqualTo(1);
        assertThat(audited("ENQUIRY_LINE_LOST")).isEqualTo(2);
    }

    // ------------------------------------------------------------- convert

    @Test
    @DisplayName("converting makes one draft order from the won lines, and a second or concurrent convert returns that order")
    void convertIsIdempotent() {
        UUID id = create(line(origin, destination, "10", "EA", "1000"), line(origin, elsewhere, "10", "EA", "500"),
                line(origin, destination, "3", "EA", "30"));
        refused(call("POST", "/api/v1/enquiries/" + id + "/convert", null, null), 409, "enquiry-not-decided");
        ok(decide(id, lineId(id, 0), "WON", null), 204);
        ok(decide(id, lineId(id, 1), "PARTIAL", "\"wonQuantity\":4"), 204);
        refused(call("POST", "/api/v1/enquiries/" + id + "/convert", null, null), 409, "enquiry-not-decided");
        ok(decide(id, lineId(id, 2), "LOST", "\"reason\":\"price\""), 204);
        refused(call(caller, "POST", "/api/v1/enquiries/" + id + "/convert", null, null, "ORDER_CREATE"), 403, null);

        JsonNode first = ok(call("POST", "/api/v1/enquiries/" + id + "/convert", null, null), 200);
        assertThat(first.get("alreadyConverted").asBoolean()).isFalse();
        UUID order = UUID.fromString(first.get("orderId").asString());

        JsonNode second = ok(call("POST", "/api/v1/enquiries/" + id + "/convert", null, null), 200);
        assertThat(second.get("alreadyConverted").asBoolean()).isTrue();
        assertThat(second.get("orderId").asString()).isEqualTo(order.toString());

        JsonNode made = ok(call("GET", "/api/v1/orders/" + order, null, null), 200);
        assertThat(made.get("status").asString()).isEqualTo("DRAFT");
        JsonNode lines = ok(call("GET", "/api/v1/orders/" + order + "/lines", null, null), 200);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).get("quantity").decimalValue()).isEqualByComparingTo("10");
        assertThat(lines.get(0).get("deadWeightKg").decimalValue()).isEqualByComparingTo("1000");
        assertThat(lines.get(1).get("quantity").decimalValue()).as("the part won").isEqualByComparingTo("4");
        assertThat(lines.get(1).get("deadWeightKg").decimalValue()).as("weight in proportion")
                .isEqualByComparingTo("200");

        JsonNode linked = get(id);
        assertThat(linked.get("status").asString()).isEqualTo("CONVERTED");
        assertThat(linked.get("orderId").asString()).isEqualTo(order.toString());
        assertThat(linked.get("orderNo").asString()).isEqualTo(made.get("orderNo").asString());
        assertThat(jdbc.sql("SELECT count(*) FROM sales_order WHERE tenant_id = :t").param("t", tenantId)
                .query(Long.class).single()).isEqualTo(1L);
        assertThat(audited("ENQUIRY_CONVERTED")).isEqualTo(1);
        refused(call("POST", "/api/v1/enquiries/" + id + "/cancel", "{\"reason\":\"x\"}", version(id)), 409,
                "enquiry-not-open");
    }

    @Test
    @DisplayName("six people pressing convert at once make exactly one order and all are given it")
    void convertRace() {
        UUID id = create(line(origin, destination, "10", "EA", "1000"));
        ok(decide(id, lineId(id, 0), "WON", null), 204);
        List<Callable<Outcome>> calls = java.util.stream.IntStream.range(0, 6)
                .<Callable<Outcome>>mapToObj(i -> () -> call("POST", "/api/v1/enquiries/" + id + "/convert", null, null))
                .toList();
        List<Outcome> results = ApiTestKit.race(calls, Duration.ofSeconds(60));
        assertThat(results).extracting(Outcome::status).containsOnly(200);
        assertThat(results.stream().map(o -> kit.parse(o).get("orderId").asString()).distinct()).hasSize(1);
        assertThat(results.stream().filter(o -> !kit.parse(o).get("alreadyConverted").asBoolean())).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM sales_order WHERE tenant_id = :t").param("t", tenantId)
                .query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    @DisplayName("won lines that start at different terminals are not squeezed into one order")
    void originsDiffer() {
        UUID id = create(line(origin, destination, "1", "EA", "10"), line(otherOrigin, destination, "1", "EA", "10"));
        ok(decide(id, lineId(id, 0), "WON", null), 204);
        ok(decide(id, lineId(id, 1), "WON", null), 204);
        refused(call("POST", "/api/v1/enquiries/" + id + "/convert", null, null), 409, "enquiry-origins-differ");
        assertThat(get(id).get("status").asString()).isEqualTo("WON");
        assertThat(jdbc.sql("SELECT count(*) FROM sales_order WHERE tenant_id = :t").param("t", tenantId)
                .query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("a won line without a weight in kilograms cannot be weighed into an order, and nothing is half-made")
    void weightNeeded() {
        UUID id = create(line(origin, destination, "2", "PALLET", null));
        ok(decide(id, lineId(id, 0), "WON", null), 204);
        refused(call("POST", "/api/v1/enquiries/" + id + "/convert", null, null), 409, "enquiry-line-weight-missing");
        assertThat(jdbc.sql("SELECT count(*) FROM sales_order WHERE tenant_id = :t").param("t", tenantId)
                .query(Long.class).single()).as("the order raised first was rolled back").isZero();
        assertThat(get(id).get("status").asString()).isEqualTo("WON");
    }

    // -------------------------------------------------------------- cancel

    @Test
    @DisplayName("cancelling closes the open lines and withdraws the quotes, is safe to repeat, and is refused beside a won line")
    void cancelling() {
        UUID id = create(line(origin, destination, "1", "EA", "10"), line(origin, destination, "1", "EA", "10"));
        ok(quote(id, lineId(id, 0), "100"), 201);

        refused(call("POST", "/api/v1/enquiries/" + id + "/cancel", "{}", version(id)), 400, null);
        refused(call("POST", "/api/v1/enquiries/" + id + "/cancel", "{\"reason\":\"x\"}", "\"42\""), 409,
                "concurrent-modification");
        ok(call("POST", "/api/v1/enquiries/" + id + "/cancel", "{\"reason\":\"customer went away\"}", version(id)), 204);

        JsonNode cancelled = get(id);
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("cancelReason").asString()).isEqualTo("customer went away");
        assertThat(cancelled.get("lines").get(0).get("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("lines").get(0).get("quotes").get(0).get("status").asString()).isEqualTo("REVOKED");
        ok(call("POST", "/api/v1/enquiries/" + id + "/cancel", "{\"reason\":\"again\"}", "\"1\""), 204);
        assertThat(audited("ENQUIRY_CANCELLED")).as("the repeat changed nothing").isEqualTo(1);
        refused(decide(id, lineId(id, 0), "WON", null), 409, "enquiry-not-open");

        UUID withWin = create(line(origin, destination, "1", "EA", "10"), line(origin, destination, "1", "EA", "10"));
        ok(decide(withWin, lineId(withWin, 0), "WON", null), 204);
        refused(call("POST", "/api/v1/enquiries/" + withWin + "/cancel", "{\"reason\":\"x\"}", version(withWin)), 409,
                "enquiry-has-won-lines");
    }

    // --------------------------------------------------------------- sweep

    @Test
    @DisplayName("the sweep cancels what is open past its deadline, keeps every won line, and leaves enquiries not yet due")
    void deadlineSweep() {
        UUID untouched = create(line(origin, destination, "1", "EA", "10"));
        UUID allOpen = create(line(origin, destination, "1", "EA", "10"), line(origin, destination, "1", "EA", "10"));
        ok(quote(allOpen, lineId(allOpen, 0), "100"), 201);
        UUID someWon = create(line(origin, destination, "1", "EA", "10"), line(origin, destination, "1", "EA", "10"));
        ok(decide(someWon, lineId(someWon, 0), "WON", null), 204);
        ok(quote(someWon, lineId(someWon, 1), "100"), 201);
        Instant deadline = Instant.parse(get(untouched).get("deadlineAt").asString());
        expireNow(allOpen);
        expireNow(someWon);

        assertThat(deadlines.sweep(Instant.now())).isGreaterThanOrEqualTo(2);

        JsonNode none = get(allOpen);
        assertThat(none.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(none.get("cancelReason").asString()).isEqualTo(EnquiryDeadlines.DEADLINE_REASON);
        assertThat(none.get("lines").get(0).get("status").asString()).isEqualTo("CANCELLED");
        assertThat(none.get("lines").get(0).get("quotes").get(0).get("status").asString()).isEqualTo("REVOKED");

        JsonNode kept = get(someWon);
        assertThat(kept.get("status").asString()).isEqualTo("PARTIAL");
        assertThat(absent(kept, "cancelReason")).as("an enquiry with a win is not cancelled").isTrue();
        assertThat(kept.get("lines").get(0).get("status").asString()).as("the won line is never cancelled")
                .isEqualTo("WON");
        assertThat(kept.get("lines").get(0).get("wonQuantity").decimalValue()).isEqualByComparingTo("1");
        assertThat(kept.get("lines").get(1).get("status").asString()).isEqualTo("CANCELLED");
        assertThat(kept.get("lines").get(1).get("quotes").get(0).get("status").asString()).isEqualTo("REVOKED");

        JsonNode notDue = get(untouched);
        assertThat(notDue.get("status").asString()).isEqualTo("OPEN");
        assertThat(Instant.parse(notDue.get("deadlineAt").asString())).isEqualTo(deadline);
        assertThat(deadlines.sweep(Instant.now())).as("nothing is closed twice").isZero();
        assertThat(audited("ENQUIRY_DEADLINE_PASSED")).isEqualTo(2);

        // The won part still converts after the sweep.
        JsonNode converted = ok(call("POST", "/api/v1/enquiries/" + someWon + "/convert", null, null), 200);
        assertThat(converted.get("alreadyConverted").asBoolean()).isFalse();
    }

    // ----------------------------------------------------------- isolation

    @Test
    @DisplayName("another tenant sees no enquiry, can change none and cannot convert one")
    void tenantsAreIsolated() {
        UUID id = create(line(origin, destination, "1", "EA", "10"));
        String otherSalt = UUID.randomUUID().toString().substring(0, 8);
        UUID otherTenant = fixtures.createTenant("globex-" + otherSalt, "Globex");
        UUID otherUnit = fixtures.createOrgUnit(otherTenant, "/globex-" + otherSalt + "/", "Globex HQ", "HQ", null);
        ApiTestKit.Tenant outsider = kit.tenantOf(otherTenant, otherUnit, "/globex-" + otherSalt + "/");

        refused(call(outsider, "GET", "/api/v1/enquiries/" + id, null, null, DESK), 404, null);
        assertThat(ok(call(outsider, "GET", "/api/v1/enquiries", null, null, DESK), 200).get("items")).isEmpty();
        refused(call(outsider, "POST", "/api/v1/enquiries/" + id + "/cancel", "{\"reason\":\"x\"}", version(id), DESK),
                404, null);
        refused(call(outsider, "POST", "/api/v1/enquiries/" + id + "/convert", null, null, DESK), 404, null);
        refused(call(outsider, "POST", path(id, lineId(id, 0), "/quote"), "{\"amount\":1}", version(id), DESK), 404,
                null);
        assertThat(get(id).get("status").asString()).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("row-level security confines the enquiry tables to their tenant, even for a query with no tenant predicate")
    void rowLevelSecurity() {
        UUID id = create(line(origin, destination, "1", "EA", "10"));
        ok(quote(id, lineId(id, 0), "100"), 201);
        ok(submitOne(), 200);
        String otherSalt = UUID.randomUUID().toString().substring(0, 8);
        UUID otherTenant = fixtures.createTenant("initech-" + otherSalt, "Initech");
        fixtures.createOrgUnit(otherTenant, "/initech-" + otherSalt + "/", "Initech HQ", "HQ", null);

        for (String table : List.of("enquiry", "enquiry_line", "enquiry_quote", "bulk_intake_key")) {
            String count = "SELECT count(*) FROM " + table;
            assertThat(scopedCount(tenantId, orgPath, count)).as(table + " in its own scope").isPositive();
            assertThat(scopedCount(otherTenant, "/initech-" + otherSalt + "/", count))
                    .as(table + " in another tenant's scope").isZero();
        }
    }

    private Outcome submitOne() {
        String item = "{\"idempotencyKey\":\"rls-" + salt + "\",\"customerPartnerId\":\"" + client
                + "\",\"originTerminalId\":\"" + origin + "\",\"lines\":[{\"materialCode\":\"M\","
                + "\"materialDescription\":\"Goods\",\"quantity\":1,\"uom\":\"EA\",\"deadWeightKg\":10,"
                + "\"destinationTerminalId\":\"" + destination + "\"}]}";
        return call("POST", "/api/v1/order-intake/batches",
                "{\"orgUnitId\":\"" + orgUnitId + "\",\"items\":[" + item + "]}", null);
    }

    private long scopedCount(UUID scope, String path, String sql) {
        return TenantContext.callWith(scope, path, () -> new TransactionTemplate(transactionManager)
                .execute(s -> jdbc.sql(sql).query(Long.class).single()));
    }

    // ------------------------------------------------------------ settings

    private void setDeadlineHours(int hours) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("setup", "n/a",
                java.util.Set.of(new SimpleGrantedAuthority("SETTINGS_MANAGE"))));
        try {
            TenantContext.callWith(tenantId, orgPath, () -> new TransactionTemplate(transactionManager).execute(s -> {
                settings.setOrgDefaults(java.util.Map.of(EnquirySettingDefinitions.RESPONSE_DEADLINE_HOURS, hours));
                return null;
            }));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
