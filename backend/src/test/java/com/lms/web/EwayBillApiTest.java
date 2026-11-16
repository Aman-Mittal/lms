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
import java.util.Map;
import java.util.UUID;

import com.lms.settings.api.SettingKey;
import com.lms.statutory.command.EwayBillSweep;
import com.lms.statutory.command.StatutorySettingDefinitions;
import com.lms.web.ApiTestKit.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E-way bills over HTTP (issue #231): the stub adapter behind the unit's
 * setting, the per-consignment bill and its actions, the lookup by number, the
 * trip-level Part-B with per-consignment results, the dispatch gate, the expiry
 * sweep, and tenant isolation.
 */
class EwayBillApiTest extends YardApiSupport {

    private static final String MANAGE = "EWAY_BILL_MANAGE";
    private static final String READ = "TRIP_READ";

    @Autowired
    private EwayBillSweep sweep;

    // ---------------------------------------------------------------- helpers

    private void stubOn() {
        setting(Map.<SettingKey<?>, Object>of(StatutorySettingDefinitions.ADAPTER, "STUB"));
    }

    private String loadOf(UUID trip) {
        return kit.parse(get("/api/v1/trips/" + trip, READ)).get("loadId").asString();
    }

    private List<String> consignmentsOf(String loadId) {
        JsonNode node = kit.parse(get("/api/v1/loads/" + loadId + "/consignments", "LOAD_READ"));
        JsonNode items = node.isArray() ? node : node.get("items");
        List<String> ids = new java.util.ArrayList<>();
        items.forEach(item -> ids.add(item.get("id").asString()));
        return ids;
    }

    /** A trip with one consignment; returns {trip, consignment}. */
    private String[] tripWithOneConsignment() {
        UUID trip = raiseSingleDropTrip();
        String load = loadOf(trip);
        return new String[] {trip.toString(), consignmentsOf(load).get(0)};
    }

    private String generate(String consignment) {
        Outcome made = post("/api/v1/consignments/" + consignment + "/eway-bills", "{}", MANAGE);
        assertThat(made.status()).as(made.body()).isEqualTo(201);
        return id(made);
    }

    private JsonNode bill(String id) {
        Outcome read = get("/api/v1/eway-bills/" + id, READ);
        assertThat(read.status()).as(read.body()).isEqualTo(200);
        return kit.parse(read);
    }

    private JsonNode act(String id, String action, String reason, String vehicle) {
        Outcome done = post("/api/v1/eway-bills/" + id + "/actions", actionBody(action, reason, vehicle), MANAGE);
        assertThat(done.status()).as(done.body()).isEqualTo(200);
        return kit.parse(done);
    }

    private static String actionBody(String action, String reason, String vehicle) {
        return json("action", q(action), "reason", reason == null ? "null" : q(reason), "vehicleNo",
                vehicle == null ? "null" : q(vehicle));
    }

    private static void refused(Outcome outcome, String code) {
        assertThat(outcome.status()).as(outcome.body()).isEqualTo(409);
        assertThat(outcome.code()).isEqualTo(code);
    }

    private static Instant at(JsonNode bill, String field) {
        return Instant.parse(bill.get(field).asString());
    }

    private long audited(String action, String outcome) {
        return jdbc.sql("""
                SELECT count(*) FROM audit_log WHERE tenant_id = :t AND action = :a
                   AND after_state ->> 'outcome' = :o
                """).param("t", yard.tenantId()).param("a", action).param("o", outcome).query(Long.class).single();
    }

    private long auditedAction(String action) {
        return jdbc.sql("SELECT count(*) FROM audit_log WHERE tenant_id = :t AND action = :a")
                .param("t", yard.tenantId()).param("a", action).query(Long.class).single();
    }

    private void setBillTimes(String billId, Instant generatedAt, Instant validUntil) {
        jdbc.sql("UPDATE eway_bill SET generated_at = :g, valid_until = :v WHERE id = :id")
                .param("g", java.sql.Timestamp.from(generatedAt)).param("v", java.sql.Timestamp.from(validUntil))
                .param("id", UUID.fromString(billId)).update();
    }

    /** Assigns, gates in, weighs, loads and attaches the papers, leaving only the dispatch itself. */
    private void readyToDispatch(String tripId) {
        String base = "/api/v1/trips/" + tripId;
        for (Outcome step : List.of(
                post(base + "/assignment", json("vehicleId", q(yard.vehicle()), "driverId", q(yard.driver())),
                        TRIP_RIGHTS),
                post(base + "/gate-in", json("terminalId", q(yard.origin()), "at", q(Instant.now().minusSeconds(7_200))),
                        TRIP_RIGHTS),
                post(base + "/weighings", json("terminalId", q(yard.origin()), "type", q("TARE"), "weightKg", "6000"),
                        TRIP_RIGHTS),
                post(base + "/weighings", json("terminalId", q(yard.origin()), "type", q("GROSS"), "weightKg",
                        "11000"), TRIP_RIGHTS),
                post(base + "/loaded", "{}", TRIP_RIGHTS),
                post(base + "/documents", json("documentType", q("CONSIGNMENT_NOTE"), "documentRef", q("LR-1")),
                        TRIP_RIGHTS),
                post(base + "/documents", json("documentType", q("EWAY_BILL"), "documentRef", q("EW-1")),
                        TRIP_RIGHTS))) {
            assertThat(step.status()).as(step.body()).isLessThan(300);
        }
    }

    private Outcome dispatchNow(String tripId) {
        return post("/api/v1/trips/" + tripId + "/dispatch", "{}", TRIP_RIGHTS);
    }

    // ------------------------------------------------------------------ switch

    @Test
    @DisplayName("with the default settings the feature is off: no bill can be raised, and dispatch is as before")
    void offByDefault() {
        String[] ids = tripWithOneConsignment();
        refused(post("/api/v1/consignments/" + ids[1] + "/eway-bills", "{}", MANAGE), "eway-bill-disabled");
        // Nothing was written, and dispatch needs no bill.
        assertThat(kit.parse(get("/api/v1/consignments/" + ids[1] + "/eway-bills", READ))).isEmpty();
        readyToDispatch(ids[0]);
        assertThat(dispatchNow(ids[0]).status()).isLessThan(300);
    }

    @Test
    @DisplayName("every threshold and toggle is a registered setting with the documented default")
    void settingsAreRegistered() {
        JsonNode catalogue = kit.parse(get("/api/v1/settings/definitions", "SETTINGS_READ"));
        JsonNode all = catalogue.isArray() ? catalogue : catalogue.has("items") ? catalogue.get("items")
                : catalogue.get("definitions");
        Map<String, String> defaults = new java.util.TreeMap<>();
        all.forEach(definition -> {
            String key = definition.has("key") ? definition.get("key").asString() : "";
            if (key.startsWith("statutory.ewayBill.")) {
                defaults.put(key, definition.get("defaultValue").toString());
            }
        });
        assertThat(defaults).containsEntry("statutory.ewayBill.adapter", "\"NONE\"")
                .containsEntry("statutory.ewayBill.requiredAtDispatch", "false")
                .containsEntry("statutory.ewayBill.cancelWindowHours", "24")
                .containsEntry("statutory.ewayBill.expiringSoonHours", "8")
                .containsEntry("statutory.ewayBill.adapterTimeoutSeconds", "10")
                .containsEntry("statutory.ewayBill.kmPerDay", "200")
                .containsEntry("statutory.ewayBill.defaultDistanceKm", "100")
                .containsEntry("statutory.ewayBill.extensionHours", "24")
                .containsKey("statutory.ewayBill.profile");
    }

    // ---------------------------------------------------------------- generate

    @Test
    @DisplayName("generating raises a bill with a number and a validity from the distance, once per consignment")
    void generate() {
        stubOn();
        String consignment = tripWithOneConsignment()[1];

        String id = generate(consignment);
        JsonNode bill = bill(id);
        assertThat(bill.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(bill.get("billNo").asString()).matches("\\d{12}");
        assertThat(bill.get("consignmentId").asString()).isEqualTo(consignment);
        assertThat(bill.get("source").asString()).isEqualTo("STUB");
        assertThat(bill.get("vehicleNo").isNull() || !bill.has("vehicleNo")).isTrue();
        // 100 km by default over 200 km a day is one day.
        assertThat(Duration.between(at(bill, "generatedAt"), at(bill, "validUntil"))).isEqualTo(Duration.ofDays(1));
        assertThat(audited("EWAY_BILL_ADAPTER_CALL", "OK")).isEqualTo(1);

        refused(post("/api/v1/consignments/" + consignment + "/eway-bills", "{}", MANAGE), "eway-bill-already-live");
        assertThat(kit.parse(get("/api/v1/consignments/" + consignment + "/eway-bills", READ))).hasSize(1);
    }

    @Test
    @DisplayName("the distance sets the validity, and a distance below one kilometre is refused")
    void distanceSetsValidity() {
        stubOn();
        String consignment = tripWithOneConsignment()[1];
        Outcome bad = post("/api/v1/consignments/" + consignment + "/eway-bills", json("distanceKm", "0"), MANAGE);
        assertThat(bad.status()).isEqualTo(400);

        Outcome made = post("/api/v1/consignments/" + consignment + "/eway-bills", json("distanceKm", "450"), MANAGE);
        JsonNode bill = bill(id(made));
        assertThat(Duration.between(at(bill, "generatedAt"), at(bill, "validUntil"))).isEqualTo(Duration.ofDays(3));
        assertThat(bill.get("distanceKm").asInt()).isEqualTo(450);
    }

    @Test
    @DisplayName("a refusal by the adapter changes nothing and is audited with its outcome")
    void adapterRefusal() {
        stubOn();
        String consignment = tripWithOneConsignment()[1];
        Outcome refusal = post("/api/v1/consignments/" + consignment + "/eway-bills",
                json("documentRef", q("LR-REFUSE-1")), MANAGE);
        refused(refusal, "eway-bill-refused");
        assertThat(refusal.body()).doesNotContain("secret");
        assertThat(kit.parse(get("/api/v1/consignments/" + consignment + "/eway-bills", READ))).isEmpty();
        assertThat(audited("EWAY_BILL_ADAPTER_CALL", "REFUSED")).isEqualTo(1);
        // The consignment can still be given a bill afterwards.
        generate(consignment);
    }

    @Test
    @DisplayName("fetching records the bill the system already holds, unless it holds none")
    void fetch() {
        stubOn();
        String consignment = tripWithOneConsignment()[1];
        refused(post("/api/v1/consignments/" + consignment + "/eway-bills",
                json("documentRef", q("LR-NOTFOUND-1"), "fetchOnly", "true"), MANAGE), "eway-bill-refused");
        Outcome fetched = post("/api/v1/consignments/" + consignment + "/eway-bills", json("fetchOnly", "true"),
                MANAGE);
        assertThat(fetched.status()).as(fetched.body()).isEqualTo(201);
        assertThat(bill(id(fetched)).get("status").asString()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("credentials are a reference: a unit's value that is not one is refused, a reference is used")
    void credentialsAreReferences() {
        String consignment = tripWithOneConsignment()[1];
        setting(Map.<SettingKey<?>, Object>of(StatutorySettingDefinitions.ADAPTER, "STUB",
                StatutorySettingDefinitions.PROFILE, Map.of("gstin", "GSTIN-UNIT-0001", "credentialRef",
                        "not-a-reference")));
        refused(post("/api/v1/consignments/" + consignment + "/eway-bills", "{}", MANAGE),
                "eway-credential-not-a-reference");
        setting(Map.<SettingKey<?>, Object>of(StatutorySettingDefinitions.PROFILE,
                Map.of("gstin", "GSTIN-UNIT-0001", "credentialRef", "secret-ref:eway/branch-1")));
        generate(consignment);
        // Whatever the unit holds, no reference or registration reaches the e-way bill audit entries
        // (the settings history records what an administrator set, which is the reference itself).
        Long leaked = jdbc.sql("""
                SELECT count(*) FROM audit_log WHERE tenant_id = :t AND action LIKE 'EWAY_BILL%'
                   AND (after_state::text LIKE '%secret-ref%' OR after_state::text LIKE '%GSTIN-UNIT%')
                """).param("t", yard.tenantId()).query(Long.class).single();
        assertThat(leaked).isZero();
    }

    // ------------------------------------------------------------------ record

    @Test
    @DisplayName("a bill keyed from the portal is recorded without an adapter, and numbers are unique")
    void recordManually() {
        String consignment = tripWithOneConsignment()[1];
        Instant until = Instant.now().plus(Duration.ofDays(2));
        Outcome recorded = post("/api/v1/consignments/" + consignment + "/eway-bills/record",
                json("billNo", q("441100220033"), "validUntil", q(until), "vehicleNo", q("MH 12 AB 1234")), MANAGE);
        assertThat(recorded.status()).as(recorded.body()).isEqualTo(201);
        JsonNode bill = bill(id(recorded));
        assertThat(bill.get("source").asString()).isEqualTo("MANUAL");
        assertThat(bill.get("vehicleNo").asString()).isEqualTo("MH12AB1234");

        refused(post("/api/v1/consignments/" + consignment + "/eway-bills/record",
                json("billNo", q("441100220034"), "validUntil", q(until)), MANAGE), "eway-bill-already-live");
        String other = tripWithOneConsignment()[1];
        refused(post("/api/v1/consignments/" + other + "/eway-bills/record",
                json("billNo", q("441100220033"), "validUntil", q(until)), MANAGE), "eway-bill-number-taken");
        refused(post("/api/v1/consignments/" + other + "/eway-bills/record",
                json("billNo", q("441100220035"), "validUntil", q(Instant.now().minusSeconds(60))), MANAGE),
                "eway-validity-invalid");
        refused(post("/api/v1/consignments/" + other + "/eway-bills/record",
                json("billNo", q("441100220035"), "generatedAt", q(Instant.now().minus(Duration.ofDays(3))),
                        "validUntil", q(Instant.now().minus(Duration.ofDays(1)))), MANAGE),
                "eway-bill-already-expired");
    }

    // ----------------------------------------------------------------- actions

    @Test
    @DisplayName("Part-B, change of vehicle, extension and cancellation each move the bill and are audited")
    void actions() {
        stubOn();
        String consignment = tripWithOneConsignment()[1];
        String id = generate(consignment);
        Instant validUntil = at(bill(id), "validUntil");

        // The vehicle cannot be changed before Part-B names one.
        refused(post("/api/v1/eway-bills/" + id + "/actions", actionBody("CHANGE_VEHICLE", "breakdown", "MH12CD5678"),
                MANAGE), "eway-part-b-missing");

        JsonNode partB = act(id, "PART_B", null, "mh 12 ab 1234");
        assertThat(partB.get("vehicleNo").asString()).isEqualTo("MH12AB1234");
        refused(post("/api/v1/eway-bills/" + id + "/actions", actionBody("CHANGE_VEHICLE", "breakdown", "MH12AB1234"),
                MANAGE), "eway-vehicle-unchanged");
        assertThat(act(id, "CHANGE_VEHICLE", "breakdown", "MH12CD5678").get("vehicleNo").asString())
                .isEqualTo("MH12CD5678");

        JsonNode extended = act(id, "extend", "road closed", null);
        assertThat(extended.get("status").asString()).isEqualTo("EXTENDED");
        assertThat(extended.get("extensionCount").asInt()).isEqualTo(1);
        assertThat(at(extended, "validUntil")).isEqualTo(validUntil.plus(Duration.ofHours(24)));

        JsonNode cancelled = act(id, "CANCEL", "goods not moved", null);
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("cancelReason").asString()).isEqualTo("goods not moved");
        refused(post("/api/v1/eway-bills/" + id + "/actions", actionBody("EXTEND", "again", null), MANAGE),
                "eway-bill-not-live");

        JsonNode history = kit.parse(get("/api/v1/eway-bills/" + id + "/history", READ));
        List<String> actions = new java.util.ArrayList<>();
        history.get("items").forEach(entry -> actions.add(entry.get("action").asString()));
        assertThat(actions).contains("EWAY_BILL_GENERATED", "EWAY_BILL_PART_B_UPDATED", "EWAY_BILL_VEHICLE_CHANGED",
                "EWAY_BILL_EXTENDED", "EWAY_BILL_CANCELLED");

        // A cancelled bill frees the consignment for a new one, with a new number.
        String again = generate(consignment);
        assertThat(bill(again).get("billNo").asString()).isNotEqualTo(cancelled.get("billNo").asString());
        assertThat(kit.parse(get("/api/v1/consignments/" + consignment + "/eway-bills", READ))).hasSize(2);
    }

    @Test
    @DisplayName("unknown and invalid actions, and missing reasons or vehicles, are problem codes, never silent")
    void invalidActions() {
        stubOn();
        String id = generate(tripWithOneConsignment()[1]);
        refused(post("/api/v1/eway-bills/" + id + "/actions", actionBody("REVOKE", "x", null), MANAGE),
                "eway-action-unknown");
        refused(post("/api/v1/eway-bills/" + id + "/actions", actionBody("GENERATE", "x", null), MANAGE),
                "eway-action-not-applicable");
        refused(post("/api/v1/eway-bills/" + id + "/actions", actionBody("EXTEND", null, null), MANAGE),
                "eway-reason-required");
        refused(post("/api/v1/eway-bills/" + id + "/actions", actionBody("CANCEL", " ", null), MANAGE),
                "eway-reason-required");
        refused(post("/api/v1/eway-bills/" + id + "/actions", actionBody("PART_B", null, null), MANAGE),
                "eway-vehicle-required");
        refused(post("/api/v1/eway-bills/" + id + "/actions", actionBody("PART_B", null, "a b!"), MANAGE),
                "eway-vehicle-invalid");
        assertThat(post("/api/v1/eway-bills/" + id + "/actions", json("reason", q("x")), MANAGE).status())
                .isEqualTo(400);
        assertThat(post("/api/v1/eway-bills/" + UUID.randomUUID() + "/actions", actionBody("PART_B", null, "MH12AB1234"),
                MANAGE).status()).isEqualTo(404);
        // None of it changed the bill.
        assertThat(bill(id).get("status").asString()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("cancelling is allowed only inside the window from generation, which is a setting")
    void cancelWindow() {
        stubOn();
        String id = generate(tripWithOneConsignment()[1]);
        Instant generated = Instant.now().minus(Duration.ofHours(30));
        setBillTimes(id, generated, generated.plus(Duration.ofDays(2)));

        refused(post("/api/v1/eway-bills/" + id + "/actions", actionBody("CANCEL", "late", null), MANAGE),
                "eway-cancel-window-passed");
        setting(Map.<SettingKey<?>, Object>of(StatutorySettingDefinitions.CANCEL_WINDOW_HOURS, 48));
        assertThat(act(id, "CANCEL", "late but allowed", null).get("status").asString()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("a bill past its validity cannot be changed")
    void expiredCannotBeChanged() {
        stubOn();
        String id = generate(tripWithOneConsignment()[1]);
        setBillTimes(id, Instant.now().minus(Duration.ofDays(3)), Instant.now().minus(Duration.ofHours(1)));
        refused(post("/api/v1/eway-bills/" + id + "/actions", actionBody("EXTEND", "late", null), MANAGE),
                "eway-bill-expired");
    }

    @Test
    @DisplayName("with the adapter off, actions are refused as disabled")
    void actionsNeedTheAdapter() {
        String consignment = tripWithOneConsignment()[1];
        Outcome recorded = post("/api/v1/consignments/" + consignment + "/eway-bills/record",
                json("billNo", q("551100220033"), "validUntil", q(Instant.now().plus(Duration.ofDays(2)))), MANAGE);
        refused(post("/api/v1/eway-bills/" + id(recorded) + "/actions", actionBody("PART_B", null, "MH12AB1234"),
                MANAGE), "eway-bill-disabled");
    }

    // ------------------------------------------------------------------ lookup

    @Test
    @DisplayName("a consignment is found by its e-way bill number, and the desk lists and filters the bills")
    void lookupAndList() {
        stubOn();
        String[] ids = tripWithOneConsignment();
        String billId = generate(ids[1]);
        String number = bill(billId).get("billNo").asString();

        JsonNode found = kit.parse(get("/api/v1/eway-bills/by-number/" + number, READ));
        assertThat(found.get("consignmentId").asString()).isEqualTo(ids[1]);
        assertThat(found.get("trackingRef").asString()).isNotBlank();
        assertThat(get("/api/v1/eway-bills/by-number/000000000000", READ).status()).isEqualTo(404);

        JsonNode listed = kit.parse(get("/api/v1/eway-bills?q=" + number.substring(0, 6), READ));
        assertThat(listed.get("items")).hasSize(1);
        assertThat(kit.parse(get("/api/v1/eway-bills?status=CANCELLED", READ)).get("items")).isEmpty();
        assertThat(kit.parse(get("/api/v1/eway-bills?status=ACTIVE&limit=1", READ)).get("hasMore").asBoolean())
                .isFalse();
        assertThat(get("/api/v1/eway-bills?cursor=garbage", READ).status()).isEqualTo(400);
    }

    // --------------------------------------------------------------- trip Part-B

    @Test
    @DisplayName("the trip-level Part-B update answers per consignment: updated, refused and skipped")
    void tripPartB() {
        stubOn();
        UUID trip = raiseMultiDropTrip();
        List<String> consignments = consignmentsOf(loadOf(trip));
        assertThat(consignments).hasSize(2);
        String first = generate(consignments.get(0));

        Outcome none = post("/api/v1/trips/" + trip + "/eway-bills/part-b", "{}", MANAGE);
        refused(none, "trip-vehicle-missing");

        post("/api/v1/trips/" + trip + "/assignment", json("vehicleId", q(yard.vehicle()), "driverId",
                q(yard.driver())), TRIP_RIGHTS);
        JsonNode result = kit.parse(post("/api/v1/trips/" + trip + "/eway-bills/part-b", "{}", MANAGE));
        assertThat(result.get("vehicleNo").asString()).isEqualTo(yard.registration().replace(" ", "").toUpperCase(java.util.Locale.ROOT));
        assertThat(result.get("updated").asInt()).isEqualTo(1);
        Map<String, String> outcomes = new java.util.HashMap<>();
        result.get("items").forEach(item -> outcomes.put(item.get("consignmentId").asString(),
                item.get("outcome").asString() + ":" + (item.has("code") && !item.get("code").isNull()
                        ? item.get("code").asString() : "")));
        assertThat(outcomes).containsEntry(consignments.get(0), "UPDATED:")
                .containsEntry(consignments.get(1), "SKIPPED:eway-bill-missing");
        assertThat(bill(first).get("vehicleNo").asString()).isEqualTo(yard.registration().replace(" ", "").toUpperCase(java.util.Locale.ROOT));

        // Give the second consignment a bill, then make the statutory system refuse that one's document.
        String second = generate(consignments.get(1));
        jdbc.sql("UPDATE consignment SET tracking_ref = 'LR-REFUSE-' || substr(id::text, 1, 6) WHERE id = :id")
                .param("id", UUID.fromString(consignments.get(1))).update();
        JsonNode mixed = kit.parse(post("/api/v1/trips/" + trip + "/eway-bills/part-b", json("vehicleNo",
                q("MH 14 ZZ 0001")), MANAGE));
        assertThat(mixed.get("updated").asInt()).isEqualTo(1);
        Map<String, String> kinds = new java.util.HashMap<>();
        mixed.get("items").forEach(item -> kinds.put(item.get("consignmentId").asString(),
                item.get("outcome").asString()));
        assertThat(kinds).containsEntry(consignments.get(0), "UPDATED").containsEntry(consignments.get(1), "REFUSED");
        assertThat(bill(first).get("vehicleNo").asString()).isEqualTo("MH14ZZ0001");
        assertThat(bill(second).get("vehicleNo").isNull() || !bill(second).has("vehicleNo")).isTrue();
    }

    @Test
    @DisplayName("with the adapter off, the trip-level Part-B skips every consignment")
    void tripPartBWithoutAdapter() {
        UUID trip = raiseSingleDropTrip();
        JsonNode result = kit.parse(post("/api/v1/trips/" + trip + "/eway-bills/part-b",
                json("vehicleNo", q("MH12AB1234")), MANAGE));
        assertThat(result.get("updated").asInt()).isZero();
        assertThat(result.get("items").get(0).get("outcome").asString()).isEqualTo("SKIPPED");
    }

    // ------------------------------------------------------------ dispatch gate

    @Test
    @DisplayName("when the unit requires a bill at dispatch, a trip leaves only with a live, unexpired bill on every consignment")
    void dispatchGate() {
        stubOn();
        setting(Map.<SettingKey<?>, Object>of(StatutorySettingDefinitions.REQUIRED_AT_DISPATCH, true));
        String[] ids = tripWithOneConsignment();
        readyToDispatch(ids[0]);

        Outcome blocked = dispatchNow(ids[0]);
        refused(blocked, "eway-bill-required");
        assertThat(blocked.body()).contains("no active e-way bill");

        String billId = generate(ids[1]);
        // A bill that lapses before the trip leaves does not count.
        setBillTimes(billId, Instant.now().minus(Duration.ofDays(2)), Instant.now().minus(Duration.ofMinutes(5)));
        Outcome lapsed = dispatchNow(ids[0]);
        refused(lapsed, "eway-bill-required");
        assertThat(lapsed.body()).contains("not valid at departure");

        // The sweep expires it; a new bill is raised for the consignment and the trip leaves.
        assertThat(sweep.sweep(Instant.now())).isGreaterThanOrEqualTo(1);
        assertThat(bill(billId).get("status").asString()).isEqualTo("EXPIRED");
        generate(ids[1]);
        Outcome left = dispatchNow(ids[0]);
        assertThat(left.status()).as(left.body()).isLessThan(300);
    }

    @Test
    @DisplayName("an extended bill counts at the gate, and other blockers keep their own code")
    void dispatchGateOtherBlockers() {
        stubOn();
        setting(Map.<SettingKey<?>, Object>of(StatutorySettingDefinitions.REQUIRED_AT_DISPATCH, true));
        String[] ids = tripWithOneConsignment();
        String billId = generate(ids[1]);
        act(billId, "EXTEND", "slow loading", null);

        // Not ready (no vehicle, never weighed): reported together, under the checklist's own code.
        Outcome early = dispatchNow(ids[0]);
        refused(early, "dispatch-blocked");
        assertThat(early.body()).doesNotContain("no active e-way bill");

        readyToDispatch(ids[0]);
        assertThat(dispatchNow(ids[0]).status()).isLessThan(300);
    }

    // -------------------------------------------------------------------- sweep

    @Test
    @DisplayName("the sweep flags bills inside the threshold once, lists them, and an extension clears the flag")
    void expiringSoon() {
        stubOn();
        String soon = generate(tripWithOneConsignment()[1]);
        String later = generate(tripWithOneConsignment()[1]);
        setBillTimes(soon, Instant.now().minus(Duration.ofHours(19)), Instant.now().plus(Duration.ofHours(5)));
        setBillTimes(later, Instant.now().minus(Duration.ofHours(4)), Instant.now().plus(Duration.ofHours(20)));

        assertThat(kit.parse(get("/api/v1/eway-bills/expiring", READ)).get("items")).isEmpty();
        assertThat(sweep.sweep(Instant.now())).isGreaterThanOrEqualTo(1);
        JsonNode flagged = kit.parse(get("/api/v1/eway-bills/expiring", READ)).get("items");
        assertThat(flagged).hasSize(1);
        assertThat(flagged.get(0).get("id").asString()).isEqualTo(soon);
        assertThat(flagged.get(0).get("expiringSoon").asBoolean()).isTrue();
        assertThat(auditedAction("EWAY_BILL_EXPIRING_SOON")).isEqualTo(1);

        // Once only: a second pass flags nothing new for this tenant's bills.
        sweep.sweep(Instant.now());
        assertThat(kit.parse(get("/api/v1/eway-bills/expiring", READ)).get("items")).hasSize(1);

        // The threshold is the unit's setting.
        setting(Map.<SettingKey<?>, Object>of(StatutorySettingDefinitions.EXPIRING_SOON_HOURS, 24));
        sweep.sweep(Instant.now());
        assertThat(kit.parse(get("/api/v1/eway-bills/expiring", READ)).get("items")).hasSize(2);

        assertThat(act(soon, "EXTEND", "held at the border", null).get("expiringSoon").asBoolean()).isFalse();
        assertThat(kit.parse(get("/api/v1/eway-bills/expiring", READ)).get("items")).hasSize(1);
    }

    @Test
    @DisplayName("the sweep marks live bills past their validity EXPIRED, and they leave the expiring list")
    void expiry() {
        stubOn();
        String id = generate(tripWithOneConsignment()[1]);
        setBillTimes(id, Instant.now().minus(Duration.ofDays(2)), Instant.now().plus(Duration.ofHours(2)));
        sweep.sweep(Instant.now());
        assertThat(bill(id).get("expiringSoon").asBoolean()).isTrue();

        setBillTimes(id, Instant.now().minus(Duration.ofDays(2)), Instant.now().minus(Duration.ofMinutes(1)));
        sweep.sweep(Instant.now());
        JsonNode expired = bill(id);
        assertThat(expired.get("status").asString()).isEqualTo("EXPIRED");
        assertThat(expired.get("expiringSoon").asBoolean()).isFalse();
        assertThat(kit.parse(get("/api/v1/eway-bills/expiring", READ)).get("items")).isEmpty();
        // Idempotent.
        sweep.sweep(Instant.now());
        assertThat(bill(id).get("status").asString()).isEqualTo("EXPIRED");
        assertThat(kit.parse(get("/api/v1/eway-bills?status=EXPIRED", READ)).get("items")).hasSize(1);
    }

    // ----------------------------------------------------------- rights and RLS

    @Test
    @DisplayName("changes need EWAY_BILL_MANAGE, and another tenant sees nothing of a tenant's bills")
    void rightsAndIsolation() {
        stubOn();
        String consignment = tripWithOneConsignment()[1];
        assertThat(post("/api/v1/consignments/" + consignment + "/eway-bills", "{}", READ).status()).isEqualTo(403);
        String id = generate(consignment);
        assertThat(post("/api/v1/eway-bills/" + id + "/actions", actionBody("EXTEND", "x", null), READ).status())
                .isEqualTo(403);
        assertThat(post("/api/v1/consignments/" + consignment + "/eway-bills/record",
                json("billNo", q("661100220033"), "validUntil", q(Instant.now().plus(Duration.ofDays(2)))), READ)
                .status()).isEqualTo(403);

        ApiTestKit.Tenant other = kit.newTenant("other");
        String number = bill(id).get("billNo").asString();
        assertThat(kit.get(other, "/api/v1/eway-bills/" + id, READ).status()).isEqualTo(404);
        assertThat(kit.get(other, "/api/v1/eway-bills/by-number/" + number, READ).status()).isEqualTo(404);
        assertThat(kit.parse(kit.get(other, "/api/v1/eway-bills", READ)).get("items")).isEmpty();
        assertThat(kit.post(other, "/api/v1/eway-bills/" + id + "/actions", actionBody("EXTEND", "x", null), MANAGE)
                .status()).isEqualTo(404);
        assertThat(kit.post(other, "/api/v1/consignments/" + consignment + "/eway-bills", "{}", MANAGE).status())
                .isEqualTo(404);
    }
}
