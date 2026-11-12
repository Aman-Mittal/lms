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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import com.lms.execution.command.ExecutionSettingDefinitions;
import com.lms.web.ApiTestKit.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The yard gate (issue #223): the register of entries and exits, the gate
 * passes and the rule that can require one, over HTTP against a real database.
 */
class YardGateApiTest extends YardApiSupport {

    private static final String GATE = "GATE_REGISTER";
    private static final String PASSES = "GATE_PASS_MANAGE";

    private static boolean absent(JsonNode node, String field) {
        return node.get(field) == null || node.get(field).isNull();
    }

    private Outcome enterVisitor(String registration) {
        return post("/api/v1/gate/entries", json("orgUnitId", q(yard.orgUnitId()), "registrationNo", q(registration),
                "driverName", q("Visitor Driver"), "purpose", q("VISITOR")), GATE);
    }

    private Outcome enterRegistered(String... extra) {
        List<String> pairs = new ArrayList<>(List.of("terminalId", q(yard.origin()), "vehicleId", q(yard.vehicle()),
                "driverId", q(yard.driver()), "purpose", q("MAINTENANCE")));
        pairs.addAll(List.of(extra));
        return post("/api/v1/gate/entries", json(pairs.toArray(String[]::new)), GATE);
    }

    private JsonNode visit(String id) {
        return kit.parse(get("/api/v1/gate/visits/" + id, GATE));
    }

    private Outcome issuePass(String registration, Instant from, Instant until, String... extra) {
        List<String> pairs = new ArrayList<>(List.of("orgUnitId", q(yard.orgUnitId()), "registrationNo", q(registration),
                "purpose", q("VISITOR"), "validFrom", q(from), "validUntil", q(until)));
        pairs.addAll(List.of(extra));
        return post("/api/v1/gate/passes", json(pairs.toArray(String[]::new)), PASSES);
    }

    private long count(String sql, Object... params) {
        var statement = jdbc.sql(sql);
        for (int i = 0; i < params.length; i++) {
            statement = statement.param(params[i]);
        }
        return statement.query(Long.class).single();
    }

    private long audits(String action) {
        return jdbc.sql("SELECT count(*) FROM audit_log WHERE tenant_id = :t AND action = :a")
                .param("t", yard.tenantId()).param("a", action).query(Long.class).single();
    }

    // ---------------------------------------------------------------- register

    @Test
    @DisplayName("a registered vehicle enters at a terminal, and its exit closes the visit with seals, weights and dwell")
    void registeredVehicleEntersAndLeaves() {
        Instant in = Instant.now().minus(Duration.ofHours(2));
        Outcome entered = enterRegistered("at", q(in), "sealNos", "[\"S-1\",\"S-2\"]", "weightRef", q("WB-100"),
                "remarks", q("brake check"));
        assertThat(entered.status()).isEqualTo(201);
        String id = id(entered);

        JsonNode open = visit(id);
        assertThat(open.get("status").asString()).isEqualTo("OPEN");
        assertThat(open.get("purpose").asString()).isEqualTo("MAINTENANCE");
        assertThat(open.get("terminalId").asString()).isEqualTo(yard.origin().toString());
        assertThat(open.get("orgUnitId").asString()).isEqualTo(yard.orgUnitId().toString());
        assertThat(open.get("registrationNo").asString()).isEqualTo(yard.registration().replace(" ", "").toUpperCase());
        assertThat(open.get("driverName").asString()).isEqualTo("Ravi");
        assertThat(open.get("entrySealNos")).extracting(JsonNode::asString).containsExactly("S-1", "S-2");
        assertThat(open.get("entryWeightRef").asString()).isEqualTo("WB-100");
        assertThat(open.get("dwellMinutes").asLong()).isGreaterThanOrEqualTo(119);

        Instant out = in.plus(Duration.ofMinutes(90));
        Outcome left = post("/api/v1/gate/visits/" + id + "/exit", json("at", q(out), "sealNos", "[\"S-3\"]",
                "weightRef", q("WB-101")), GATE);
        assertThat(left.status()).isEqualTo(204);

        JsonNode closed = visit(id);
        assertThat(closed.get("status").asString()).isEqualTo("CLOSED");
        assertThat(closed.get("dwellMinutes").asLong()).isEqualTo(90);
        assertThat(closed.get("exitSealNos")).extracting(JsonNode::asString).containsExactly("S-3");
        assertThat(closed.get("exitWeightRef").asString()).isEqualTo("WB-101");
        assertThat(audits("GATE_ENTRY")).isEqualTo(1);
        assertThat(audits("GATE_EXIT")).isEqualTo(1);

        // A closed visit is closed once, and the vehicle may come in again.
        Outcome again = post("/api/v1/gate/visits/" + id + "/exit", "{}", GATE);
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("gate-visit-closed");
        assertThat(enterRegistered().status()).isEqualTo(201);
    }

    @Test
    @DisplayName("an exit before the entry is refused and leaves the visit open")
    void exitBeforeEntry() {
        Instant in = Instant.now().minus(Duration.ofHours(1));
        String id = id(enterRegistered("at", q(in)));

        Outcome early = post("/api/v1/gate/visits/" + id + "/exit", json("at", q(in.minusSeconds(60))), GATE);

        assertThat(early.status()).isEqualTo(409);
        assertThat(early.code()).isEqualTo("gate-exit-before-entry");
        assertThat(visit(id).get("status").asString()).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("a visitor is known by the registration number typed, with its blanks and case ignored, and a second entry is refused by name")
    void duplicateEntryIsRefused() {
        assertThat(enterVisitor("up 32 ab 9999").status()).isEqualTo(201);

        Outcome second = enterVisitor("UP32AB9999");
        assertThat(second.status()).isEqualTo(409);
        assertThat(second.code()).isEqualTo("gate-entry-already-open");
        assertThat(count("SELECT count(*) FROM gate_visit WHERE tenant_id = ? AND registration_no = 'UP32AB9999'",
                yard.tenantId())).isEqualTo(1);

        // A registered vehicle is refused the same way, whichever way it is named.
        assertThat(enterRegistered().status()).isEqualTo(201);
        Outcome typed = enterVisitor(yard.registration());
        assertThat(typed.code()).isEqualTo("gate-entry-already-open");
    }

    @Test
    @DisplayName("ten entries of one vehicle at once: one is let in, the rest are refused by name, never a 500")
    void concurrentEntries() {
        List<Callable<Outcome>> calls = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            calls.add(() -> enterVisitor("RACE 1"));
        }

        List<Outcome> outcomes = ApiTestKit.race(calls, Duration.ofSeconds(60));

        assertThat(outcomes).extracting(Outcome::status).doesNotContain(500).containsOnlyOnce(201)
                .isSubsetOf(201, 409);
        assertThat(outcomes).filteredOn(o -> o.status() == 409).extracting(Outcome::code)
                .containsOnly("gate-entry-already-open");
        assertThat(count("SELECT count(*) FROM gate_visit WHERE tenant_id = ? AND registration_no = 'RACE1'",
                yard.tenantId())).isEqualTo(1);
    }

    @Test
    @DisplayName("the register pages newest entry first without repeating or skipping a visit, and filters by status and registration")
    void registerPaging() {
        Instant base = Instant.now().minus(Duration.ofHours(5));
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(id(post("/api/v1/gate/entries", json("orgUnitId", q(yard.orgUnitId()), "registrationNo",
                    q("PAGE " + i), "purpose", q("VISITOR"), "at", q(base.plusSeconds(i * 600L))), GATE)));
        }
        // The middle one has left.
        post("/api/v1/gate/visits/" + ids.get(2) + "/exit", "{}", GATE);

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = kit.parse(get("/api/v1/gate/visits?limit=2" + (cursor == null ? "" : "&cursor=" + cursor),
                    GATE));
            page.get("items").forEach(item -> seen.add(item.get("id").asString()));
            cursor = page.get("hasMore").asBoolean() ? page.get("nextCursor").asString() : null;
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(seen).containsExactly(ids.get(4), ids.get(3), ids.get(2), ids.get(1), ids.get(0));

        JsonNode openOnly = kit.parse(get("/api/v1/gate/visits?status=OPEN", GATE));
        assertThat(openOnly.get("items")).hasSize(4);
        JsonNode byNumber = kit.parse(get("/api/v1/gate/visits?q=page3", GATE));
        assertThat(byNumber.get("items")).hasSize(1);
        assertThat(byNumber.get("items").get(0).get("id").asString()).isEqualTo(ids.get(3));
        JsonNode inside = kit.parse(get("/api/v1/gate/open", GATE));
        assertThat(inside).hasSize(4);
        assertThat(inside.get(0).get("id").asString()).isEqualTo(ids.get(0));
        assertThat(get("/api/v1/gate/visits?cursor=garbage", GATE).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("an entry names its site, its vehicle and its purpose, and a terminal of another unit is refused")
    void entryValidation() {
        assertThat(post("/api/v1/gate/entries", json("registrationNo", q("NOSITE 1"), "purpose", q("VISITOR")), GATE)
                .status()).isEqualTo(400);
        assertThat(post("/api/v1/gate/entries", json("orgUnitId", q(yard.orgUnitId()), "purpose", q("VISITOR")), GATE)
                .status()).isEqualTo(400);
        assertThat(post("/api/v1/gate/entries", json("orgUnitId", q(yard.orgUnitId()), "registrationNo", q("X 1"),
                "purpose", q("TOURISM")), GATE).status()).isEqualTo(400);
        assertThat(post("/api/v1/gate/entries", json("orgUnitId", q(yard.orgUnitId()), "vehicleId",
                q(UUID.randomUUID()), "purpose", q("VISITOR")), GATE).status()).isEqualTo(404);
        assertThat(post("/api/v1/gate/entries", json("terminalId", q(UUID.randomUUID()), "registrationNo", q("X 1"),
                "purpose", q("VISITOR")), GATE).status()).isEqualTo(404);
        Outcome elsewhere = post("/api/v1/gate/entries", json("orgUnitId", q(UUID.randomUUID()), "terminalId",
                q(yard.origin()), "registrationNo", q("X 1"), "purpose", q("VISITOR")), GATE);
        assertThat(elsewhere.status()).isIn(403, 409);
    }

    @Test
    @DisplayName("a trip's own gate-in enters its vehicle in the register and its dispatch closes the visit")
    void tripMirrorsIntoTheRegister() {
        UUID trip = raiseSingleDropTrip();
        Instant gateIn = Instant.now().minus(Duration.ofHours(6));
        Instant dispatched = Instant.now().minus(Duration.ofHours(5));
        String base = "/api/v1/trips/" + trip;
        post(base + "/assignment", json("vehicleId", q(yard.vehicle()), "driverId", q(yard.driver())), TRIP_RIGHTS);
        assertThat(post(base + "/gate-in", json("terminalId", q(yard.origin()), "at", q(gateIn)), TRIP_RIGHTS).status())
                .isEqualTo(204);

        JsonNode inside = kit.parse(get("/api/v1/gate/visits?tripId=" + trip, GATE)).get("items").get(0);
        assertThat(inside.get("status").asString()).isEqualTo("OPEN");
        assertThat(inside.get("purpose").asString()).isEqualTo("TRIP");
        assertThat(inside.get("tripId").asString()).isEqualTo(trip.toString());
        assertThat(inside.get("enteredAt").asString()).startsWith(gateIn.toString().substring(0, 16));
        assertThat(enterRegistered().code()).isEqualTo("gate-entry-already-open");

        // Dispatching it takes the lorry out of the register, as the trip's gate-out does.
        post(base + "/weighings", json("terminalId", q(yard.origin()), "type", q("TARE"), "weightKg", "6000"), TRIP_RIGHTS);
        post(base + "/weighings", json("terminalId", q(yard.origin()), "type", q("GROSS"), "weightKg", "11000"),
                TRIP_RIGHTS);
        post(base + "/loaded", "{}", TRIP_RIGHTS);
        post(base + "/documents", json("documentType", q("CONSIGNMENT_NOTE"), "documentRef", q("LR-1")), TRIP_RIGHTS);
        post(base + "/documents", json("documentType", q("EWAY_BILL"), "documentRef", q("EW-1")), TRIP_RIGHTS);
        assertThat(post(base + "/dispatch", json("at", q(dispatched)), TRIP_RIGHTS).status()).isEqualTo(204);

        JsonNode left = visit(inside.get("id").asString());
        assertThat(left.get("status").asString()).isEqualTo("CLOSED");
        assertThat(left.get("dwellMinutes").asLong()).isEqualTo(60);
        assertThat(kit.parse(get("/api/v1/gate/open", GATE))).isEmpty();
    }

    @Test
    @DisplayName("a visit linked to a trip must be on the trip's own vehicle; a vehicle already inside becomes the trip's visit at its gate-in")
    void tripLink() {
        UUID trip = raiseSingleDropTrip();
        post("/api/v1/trips/" + trip + "/assignment", json("vehicleId", q(yard.vehicle()), "driverId", q(yard.driver())),
                TRIP_RIGHTS);

        String body = json("orgUnitId", q(yard.orgUnitId()), "registrationNo", q("OTHER 77"), "purpose", q("TRIP"),
                "tripId", q(trip));
        Outcome mismatch = post("/api/v1/gate/entries", body, GATE);
        assertThat(mismatch.status()).isEqualTo(409);
        assertThat(mismatch.code()).isEqualTo("gate-trip-vehicle-mismatch");

        // Let in as a visitor first, the lorry is the trip's visit when the trip is gated in at that terminal.
        String id = id(enterRegistered("purpose", q("TRIP")));
        assertThat(absent(visit(id), "tripId")).isTrue();
        post("/api/v1/trips/" + trip + "/gate-in", json("terminalId", q(yard.origin())), TRIP_RIGHTS);
        assertThat(visit(id).get("tripId").asString()).isEqualTo(trip.toString());
        assertThat(count("SELECT count(*) FROM gate_visit WHERE tenant_id = ? AND status = 'OPEN'", yard.tenantId()))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------ passes

    @Test
    @DisplayName("a pass is numbered from the GATE_PASS series that covers its unit, in the issuing transaction")
    void passNumberedFromTheSeries() {
        assertThat(kit.post(yard.tenant(), "/api/v1/number-series", json("code", q("gate-passes"), "documentType",
                q("GATE_PASS"), "prefix", q("GP"), "yearCode", q("NONE")), "NUMBER_SERIES_MANAGE").status())
                .isEqualTo(201);
        Instant from = Instant.now().minus(Duration.ofHours(1));
        Instant until = Instant.now().plus(Duration.ofHours(8));

        String first = id(issuePass("MH 14 GP 1", from, until));
        String second = id(issuePass("MH 14 GP 2", from, until, "passNo", q("IGNORED-1")));

        assertThat(kit.parse(get("/api/v1/gate/passes/" + first, PASSES)).get("passNo").asString()).isEqualTo("GP-00001");
        assertThat(kit.parse(get("/api/v1/gate/passes/" + second, PASSES)).get("passNo").asString())
                .isEqualTo("GP-00002");
        assertThat(audits("GATE_PASS_ISSUED")).isEqualTo(2);
    }

    @Test
    @DisplayName("with no series covering the unit the issuer gives the number, once")
    void passNumberGivenByTheIssuer() {
        Instant from = Instant.now().minus(Duration.ofHours(1));
        Instant until = Instant.now().plus(Duration.ofHours(8));

        Outcome none = issuePass("MH 14 GP 3", from, until);
        assertThat(none.status()).isEqualTo(409);
        assertThat(none.code()).isEqualTo("gate-pass-number-required");

        assertThat(issuePass("MH 14 GP 3", from, until, "passNo", q("MANUAL-7")).status()).isEqualTo(201);
        Outcome taken = issuePass("MH 14 GP 4", from, until, "passNo", q("MANUAL-7"));
        assertThat(taken.status()).isEqualTo(409);
        assertThat(taken.code()).isEqualTo("gate-pass-number-taken");

        Outcome backwards = issuePass("MH 14 GP 5", until, from, "passNo", q("MANUAL-8"));
        assertThat(backwards.status()).isEqualTo(409);
        assertThat(backwards.code()).isEqualTo("gate-pass-window-invalid");
    }

    @Test
    @DisplayName("where the unit requires a pass, an entry without a valid one is refused by name and writes nothing")
    void passRequired() {
        setting(Map.of(ExecutionSettingDefinitions.GATE_PASS_REQUIRED, true));
        Instant now = Instant.now();

        Outcome bare = enterVisitor("NEED 1");
        assertThat(bare.status()).isEqualTo(409);
        assertThat(bare.code()).isEqualTo("gate-pass-required");
        assertThat(count("SELECT count(*) FROM gate_visit WHERE tenant_id = ?", yard.tenantId())).isZero();

        // A pass for another vehicle is no pass for this one.
        issuePass("OTHER 1", now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(4)), "passNo", q("P-OTHER"));
        assertThat(enterVisitor("NEED 1").code()).isEqualTo("gate-pass-required");
        assertThat(post("/api/v1/gate/entries", json("orgUnitId", q(yard.orgUnitId()), "registrationNo", q("NEED 1"),
                "purpose", q("VISITOR"), "gatePassNo", q("P-OTHER")), GATE).code()).isEqualTo("gate-pass-required");

        // Expired: the window closed an hour ago.
        issuePass("NEED 1", now.minus(Duration.ofHours(5)), now.minus(Duration.ofHours(1)), "passNo", q("P-OLD"));
        Outcome expired = enterVisitor("NEED 1");
        assertThat(expired.status()).isEqualTo(409);
        assertThat(expired.code()).isEqualTo("gate-pass-expired");

        // A valid one admits, and is used up by it.
        String valid = id(issuePass("NEED 1", now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(4)),
                "passNo", q("P-GOOD")));
        Outcome admitted = enterVisitor("NEED 1");
        assertThat(admitted.status()).isEqualTo(201);
        JsonNode visit = visit(id(admitted));
        assertThat(visit.get("gatePassNo").asString()).isEqualTo("P-GOOD");
        JsonNode used = kit.parse(get("/api/v1/gate/passes/" + valid, PASSES));
        assertThat(used.get("status").asString()).isEqualTo("USED");
        assertThat(used.get("usedVisitId").asString()).isEqualTo(id(admitted));

        // Leaving and coming back needs a new pass: this one is used.
        post("/api/v1/gate/visits/" + id(admitted) + "/exit", "{}", GATE);
        Outcome reuse = post("/api/v1/gate/entries", json("orgUnitId", q(yard.orgUnitId()), "registrationNo",
                q("NEED 1"), "purpose", q("VISITOR"), "gatePassNo", q("P-GOOD")), GATE);
        assertThat(reuse.status()).isEqualTo(409);
        assertThat(reuse.code()).isEqualTo("gate-pass-used");
        assertThat(enterVisitor("NEED 1").code()).isEqualTo("gate-pass-used");
    }

    @Test
    @DisplayName("without the requirement an entry needs no pass, but a pass that is presented is still judged")
    void passNotRequired() {
        Instant now = Instant.now();
        assertThat(enterVisitor("FREE 1").status()).isEqualTo(201);

        issuePass("FREE 2", now.minus(Duration.ofHours(5)), now.minus(Duration.ofHours(1)), "passNo", q("P-STALE"));
        Outcome stale = post("/api/v1/gate/entries", json("orgUnitId", q(yard.orgUnitId()), "registrationNo",
                q("FREE 2"), "purpose", q("VISITOR"), "gatePassNo", q("P-STALE")), GATE);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("gate-pass-expired");
        assertThat(enterVisitor("FREE 2").status()).isEqualTo(201);
    }

    @Test
    @DisplayName("a pass is for a trip when it says so, and a pass cannot admit a vehicle on another trip")
    void passForATrip() {
        UUID trip = raiseSingleDropTrip();
        UUID other = raiseSingleDropTrip();
        post("/api/v1/trips/" + trip + "/assignment", json("vehicleId", q(yard.vehicle())), TRIP_RIGHTS);
        Instant now = Instant.now();
        issuePass(yard.registration(), now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(4)), "passNo",
                q("P-TRIP"), "tripId", q(trip));

        Outcome wrongTrip = post("/api/v1/gate/entries", json("terminalId", q(yard.origin()), "vehicleId",
                q(yard.vehicle()), "purpose", q("TRIP"), "tripId", q(other), "gatePassNo", q("P-TRIP")), GATE);
        assertThat(wrongTrip.status()).isEqualTo(409);
        assertThat(wrongTrip.code()).isIn("gate-pass-trip-mismatch", "gate-trip-vehicle-mismatch");

        // Presented without naming a trip, the pass supplies it.
        Outcome admitted = post("/api/v1/gate/entries", json("terminalId", q(yard.origin()), "vehicleId",
                q(yard.vehicle()), "purpose", q("TRIP"), "gatePassNo", q("P-TRIP")), GATE);
        assertThat(admitted.status()).isEqualTo(201);
        assertThat(visit(id(admitted)).get("tripId").asString()).isEqualTo(trip.toString());
    }

    @Test
    @DisplayName("the check says what the gate would decide, and consumes nothing")
    void passCheck() {
        setting(Map.of(ExecutionSettingDefinitions.GATE_PASS_REQUIRED, true));
        Instant now = Instant.now();
        String base = "/api/v1/gate/passes/check?orgUnitId=" + yard.orgUnitId() + "&registrationNo=";

        JsonNode none = kit.parse(get(base + "CHK%201", GATE));
        assertThat(none.get("admits").asBoolean()).isFalse();
        assertThat(none.get("passRequired").asBoolean()).isTrue();
        assertThat(none.get("code").asString()).isEqualTo("gate-pass-required");

        String id = id(issuePass("CHK 1", now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(2)), "passNo",
                q("P-CHK")));
        JsonNode ok = kit.parse(get(base + "chk1", GATE));
        assertThat(ok.get("admits").asBoolean()).isTrue();
        assertThat(ok.get("passNo").asString()).isEqualTo("P-CHK");
        assertThat(absent(ok, "code")).isTrue();
        // Asked again, still good: nothing was used.
        assertThat(kit.parse(get(base + "CHK1&passNo=P-CHK", GATE)).get("admits").asBoolean()).isTrue();
        assertThat(kit.parse(get("/api/v1/gate/passes/" + id, PASSES)).get("status").asString()).isEqualTo("ISSUED");

        JsonNode later = kit.parse(get(base + "CHK1&at=" + now.plus(Duration.ofHours(3)), GATE));
        assertThat(later.get("code").asString()).isEqualTo("gate-pass-expired");
        assertThat(get("/api/v1/gate/passes/check?registrationNo=X", GATE).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("cancelling a pass takes the version it was read at, keeps the reason, gives the number back and is audited")
    void cancelling() {
        assertThat(kit.post(yard.tenant(), "/api/v1/number-series", json("code", q("gate-passes"), "documentType",
                q("GATE_PASS"), "prefix", q("GP"), "yearCode", q("NONE")), "NUMBER_SERIES_MANAGE").status())
                .isEqualTo(201);
        Instant now = Instant.now();
        String id = id(issuePass("CAN 1", now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(2))));
        String path = "/api/v1/gate/passes/" + id;
        String etag = etagOf(path, PASSES);

        assertThat(send("POST", path + "/cancellation", json("reason", q("no longer coming")), null, PASSES).status())
                .isEqualTo(400);
        assertThat(send("POST", path + "/cancellation", json("reason", q(" ")), etag, PASSES).status()).isEqualTo(400);
        Outcome stale = send("POST", path + "/cancellation", json("reason", q("no longer coming")), "\"99\"", PASSES);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("concurrent-modification");

        assertThat(send("POST", path + "/cancellation", json("reason", q("no longer coming")), etag, PASSES).status())
                .isEqualTo(204);
        JsonNode cancelled = kit.parse(get(path, PASSES));
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("cancelReason").asString()).isEqualTo("no longer coming");
        assertThat(audits("GATE_PASS_CANCELLED")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM number_series_release WHERE tenant_id = ? AND number = 'GP-00001'",
                yard.tenantId())).isEqualTo(1);

        Outcome twice = send("POST", path + "/cancellation", json("reason", q("again")),
                etagOf(path, PASSES), PASSES);
        assertThat(twice.status()).isEqualTo(409);
        assertThat(twice.code()).isEqualTo("gate-pass-cancelled");

        // A cancelled pass admits nobody.
        Outcome entry = post("/api/v1/gate/entries", json("orgUnitId", q(yard.orgUnitId()), "registrationNo",
                q("CAN 1"), "purpose", q("VISITOR"), "gatePassNo", q("GP-00001")), GATE);
        assertThat(entry.status()).isEqualTo(409);
        assertThat(entry.code()).isEqualTo("gate-pass-cancelled");
    }

    @Test
    @DisplayName("a pass that has admitted a vehicle cannot be cancelled")
    void usedPassCannotBeCancelled() {
        Instant now = Instant.now();
        String id = id(issuePass("USE 1", now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(2)), "passNo",
                q("P-USE")));
        assertThat(post("/api/v1/gate/entries", json("orgUnitId", q(yard.orgUnitId()), "registrationNo", q("USE 1"),
                "purpose", q("VISITOR"), "gatePassNo", q("P-USE")), GATE).status()).isEqualTo(201);

        String path = "/api/v1/gate/passes/" + id;
        Outcome refused = send("POST", path + "/cancellation", json("reason", q("oops")), etagOf(path, PASSES), PASSES);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("gate-pass-used");
    }

    @Test
    @DisplayName("the pass list pages newest first and filters by status and text")
    void passList() {
        Instant now = Instant.now();
        for (int i = 0; i < 3; i++) {
            issuePass("LST " + i, now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(2)), "passNo", q("P-LST-" + i));
        }

        JsonNode first = kit.parse(get("/api/v1/gate/passes?limit=2", PASSES));
        assertThat(first.get("items")).hasSize(2);
        assertThat(first.get("hasMore").asBoolean()).isTrue();
        JsonNode second = kit.parse(get("/api/v1/gate/passes?limit=2&cursor=" + first.get("nextCursor").asString(), PASSES));
        assertThat(second.get("items")).hasSize(1);
        assertThat(second.get("items").get(0).get("passNo").asString()).isEqualTo("P-LST-0");
        assertThat(kit.parse(get("/api/v1/gate/passes?q=lst1", PASSES)).get("items")).hasSize(1);
        assertThat(kit.parse(get("/api/v1/gate/passes?status=USED", PASSES)).get("items")).isEmpty();
    }

    @Test
    @DisplayName("where the unit requires a pass, a trip's own gate-in is refused without one and uses one when presented")
    void tripGateInNeedsAPass() {
        setting(Map.of(ExecutionSettingDefinitions.GATE_PASS_REQUIRED, true));
        UUID trip = raiseSingleDropTrip();
        post("/api/v1/trips/" + trip + "/assignment", json("vehicleId", q(yard.vehicle())), TRIP_RIGHTS);

        Outcome refused = post("/api/v1/trips/" + trip + "/gate-in", json("terminalId", q(yard.origin())), TRIP_RIGHTS);
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("gate-pass-required");
        assertThat(count("SELECT count(*) FROM gate_event WHERE trip_id = ?", trip)).isZero();
        assertThat(jdbc.sql("SELECT status FROM trip WHERE id = :id").param("id", trip).query(String.class).single())
                .isEqualTo("ASSIGNED");

        Instant now = Instant.now();
        issuePass(yard.registration(), now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(4)), "passNo",
                q("P-GATEIN"));
        assertThat(post("/api/v1/trips/" + trip + "/gate-in", json("terminalId", q(yard.origin()), "gatePassNo",
                q("P-GATEIN")), TRIP_RIGHTS).status()).isEqualTo(204);
        assertThat(kit.parse(get("/api/v1/gate/passes?q=P-GATEIN", PASSES)).get("items").get(0).get("status")
                .asString()).isEqualTo("USED");
    }

    // ------------------------------------------------------------ rights, tenants

    @Test
    @DisplayName("each right is its own: entering needs GATE_REGISTER, issuing and cancelling GATE_PASS_MANAGE, reading either")
    void rights() {
        Instant now = Instant.now();
        assertThat(post("/api/v1/gate/entries", json("orgUnitId", q(yard.orgUnitId()), "registrationNo", q("RIGHT 1"),
                "purpose", q("VISITOR")), PASSES).status()).isEqualTo(403);
        assertThat(post("/api/v1/gate/entries", json("orgUnitId", q(yard.orgUnitId()), "registrationNo", q("RIGHT 1"),
                "purpose", q("VISITOR")), "TRIP_EXECUTE").status()).isEqualTo(403);
        assertThat(post("/api/v1/gate/passes", json("orgUnitId", q(yard.orgUnitId()), "registrationNo", q("RIGHT 1"),
                "purpose", q("VISITOR"), "validFrom", q(now), "validUntil", q(now.plusSeconds(60)), "passNo", q("P-R")),
                GATE).status()).isEqualTo(403);
        String id = id(issuePass("RIGHT 2", now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)), "passNo",
                q("P-RIGHT")));
        String path = "/api/v1/gate/passes/" + id;
        assertThat(send("POST", path + "/cancellation", json("reason", q("x")), etagOf(path, PASSES), GATE).status())
                .isEqualTo(403);

        assertThat(get("/api/v1/gate/visits", "TRIP_EXECUTE").status()).isEqualTo(403);
        assertThat(get("/api/v1/gate/open", "TRIP_EXECUTE").status()).isEqualTo(403);
        assertThat(get("/api/v1/gate/passes", "TRIP_EXECUTE").status()).isEqualTo(403);
        assertThat(get("/api/v1/gate/visits", GATE).status()).isEqualTo(200);
        assertThat(get("/api/v1/gate/visits", PASSES).status()).isEqualTo(200);
        assertThat(get("/api/v1/gate/passes", GATE).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("another tenant sees none of a tenant's visits or passes, and cannot name its vehicles or trips")
    void tenantIsolation() {
        Instant now = Instant.now();
        String visitId = id(enterVisitor("ISO 1"));
        String passId = id(issuePass("ISO 2", now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)), "passNo",
                q("P-ISO")));
        UUID trip = raiseSingleDropTrip();

        Yard other = newYard();
        var kitTenant = other.tenant();
        assertThat(kit.get(kitTenant, "/api/v1/gate/visits/" + visitId, GATE).status()).isEqualTo(404);
        assertThat(kit.get(kitTenant, "/api/v1/gate/passes/" + passId, PASSES).status()).isEqualTo(404);
        assertThat(kit.parse(kit.get(kitTenant, "/api/v1/gate/visits", GATE)).get("items")).isEmpty();
        assertThat(kit.parse(kit.get(kitTenant, "/api/v1/gate/passes", GATE)).get("items")).isEmpty();
        assertThat(kit.parse(kit.get(kitTenant, "/api/v1/gate/open", GATE))).isEmpty();
        assertThat(kit.post(kitTenant, "/api/v1/gate/visits/" + visitId + "/exit", "{}", GATE).status()).isEqualTo(404);
        assertThat(kit.get(kitTenant, "/api/v1/trips/" + trip + "/stops", TRIP_RIGHTS).status()).isEqualTo(404);

        // Its own vehicle is not theirs to let in, nor its trip theirs to link, nor its terminal theirs to enter at.
        assertThat(kit.post(kitTenant, "/api/v1/gate/entries", json("orgUnitId", q(other.orgUnitId()), "vehicleId",
                q(yard.vehicle()), "purpose", q("VISITOR")), GATE).status()).isEqualTo(404);
        assertThat(kit.post(kitTenant, "/api/v1/gate/entries", json("orgUnitId", q(other.orgUnitId()),
                "registrationNo", q("ISO 3"), "purpose", q("TRIP"), "tripId", q(trip)), GATE).status()).isEqualTo(404);
        assertThat(kit.post(kitTenant, "/api/v1/gate/entries", json("terminalId", q(yard.origin()),
                "registrationNo", q("ISO 3"), "purpose", q("VISITOR")), GATE).status()).isIn(403, 404);
        assertThat(kit.post(kitTenant, "/api/v1/gate/passes", json("orgUnitId", q(yard.orgUnitId()),
                "registrationNo", q("ISO 3"), "purpose", q("VISITOR"), "validFrom", q(now), "validUntil",
                q(now.plusSeconds(60)), "passNo", q("P-X")), PASSES).status()).isIn(403, 404);

        // The same registration number is free in the other tenant: one open visit per vehicle per tenant.
        assertThat(kit.post(kitTenant, "/api/v1/gate/entries", json("orgUnitId", q(other.orgUnitId()),
                "registrationNo", q("ISO 1"), "purpose", q("VISITOR")), GATE).status()).isEqualTo(201);
    }
}
