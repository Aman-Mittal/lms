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

import com.lms.controltower.command.DetectorSettingDefinitions;
import com.lms.execution.command.ExecutionSettingDefinitions;
import com.lms.web.ApiTestKit.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A trip's ordered stops (issue #223): derived from the load, moved by geofence
 * crossings where the trip's settings say so and by hand otherwise, flagged when
 * reached out of order, published, and judged late by the control tower.
 */
class TripStopsApiTest extends YardApiSupport {

    private JsonNode stops(UUID trip) {
        Outcome outcome = get("/api/v1/trips/" + trip + "/stops", TRIP_RIGHTS);
        assertThat(outcome.status()).as(outcome.body()).isEqualTo(200);
        return kit.parse(outcome).get("stops");
    }

    private String status(UUID trip) {
        return jdbc.sql("SELECT status FROM trip WHERE id = :id").param("id", trip).query(String.class).single();
    }

    private static boolean absent(JsonNode node, String field) {
        return node.get(field) == null || node.get(field).isNull();
    }

    private Outcome act(UUID trip, JsonNode stop, String action, String body) {
        return post("/api/v1/trips/" + trip + "/stops/" + stop.get("id").asString() + "/" + action, body, TRIP_RIGHTS);
    }

    private long audits(UUID trip, String action) {
        return jdbc.sql("SELECT count(*) FROM audit_log WHERE resource_id = :id AND action = :a")
                .param("id", trip).param("a", action).query(Long.class).single();
    }

    private long staleTickets(UUID trip) {
        return jdbc.sql("SELECT count(*) FROM exception_ticket WHERE trip_id = :id AND category = 'STOP_LATE'")
                .param("id", trip).query(Long.class).single();
    }

    private String planBody(UUID mid, Instant midPlanned, UUID dest, Instant destPlanned) {
        return "{\"stops\":[" + json("kind", q("PICKUP"), "terminalId", q(yard.origin())) + ","
                + json("kind", q("DROP"), "terminalId", q(mid), "plannedArrivalAt", midPlanned == null ? "null" : q(midPlanned))
                + "," + json("kind", q("DROP"), "terminalId", q(dest), "plannedArrivalAt",
                destPlanned == null ? "null" : q(destPlanned)) + "]}";
    }

    private Outcome replace(UUID trip, String body, String ifMatch) {
        return send("PUT", "/api/v1/trips/" + trip + "/stops", body, ifMatch, TRIP_RIGHTS);
    }

    // ---------------------------------------------------------------- deriving

    @Test
    @DisplayName("a new trip's stops are the origin pickup and a drop at each place its load delivers to, destination last")
    void derivedFromTheLoad() {
        UUID multi = raiseMultiDropTrip();
        JsonNode stops = stops(multi);

        assertThat(stops).hasSize(3);
        assertThat(stops.get(0).get("kind").asString()).isEqualTo("PICKUP");
        assertThat(stops.get(0).get("terminalId").asString()).isEqualTo(yard.origin().toString());
        assertThat(stops.get(1).get("kind").asString()).isEqualTo("DROP");
        assertThat(stops.get(1).get("terminalId").asString()).isEqualTo(yard.mid().toString());
        assertThat(stops.get(2).get("terminalId").asString()).isEqualTo(yard.dest().toString());
        for (int i = 0; i < 3; i++) {
            assertThat(stops.get(i).get("seq").asInt()).isEqualTo(i + 1);
            assertThat(stops.get(i).get("status").asString()).isEqualTo("PLANNED");
            assertThat(stops.get(i).get("lat")).isNotNull();
            assertThat(stops.get(i).get("terminalCode").asString()).isNotBlank();
            assertThat(stops.get(i).get("outOfOrder").asBoolean()).isFalse();
        }

        assertThat(stops(raiseSingleDropTrip())).extracting(n -> n.get("kind").asString())
                .containsExactly("PICKUP", "DROP");
    }

    // ----------------------------------------------------- geofence and trip

    @Test
    @DisplayName("with the automatic switches on, geofence crossings and the trip's own milestones arrive, depart and complete the stops")
    void geofenceDrivesTheStops() {
        setting(Map.of(ExecutionSettingDefinitions.AUTO_COMPLETION, true));
        UUID trip = raiseMultiDropTrip();
        Instant now = Instant.now();
        dispatch(trip, now.minus(Duration.ofHours(6)), now.minus(Duration.ofHours(5)));

        // The trip's own gate-in and dispatch moved the pickup.
        JsonNode atDispatch = stops(trip);
        assertThat(atDispatch.get(0).get("status").asString()).isEqualTo("DEPARTED");
        assertThat(atDispatch.get(0).get("arrivalSource").asString()).isEqualTo("TRIP");
        assertThat(atDispatch.get(0).get("departureSource").asString()).isEqualTo("TRIP");
        assertThat(atDispatch.get(1).get("status").asString()).isEqualTo("PLANNED");

        driveTo(now.minus(Duration.ofHours(4)), true, true);

        JsonNode afterDrive = stops(trip);
        assertThat(status(trip)).isEqualTo("AT_DESTINATION");
        JsonNode mid = afterDrive.get(1);
        assertThat(mid.get("status").asString()).isEqualTo("DEPARTED");
        assertThat(mid.get("arrivalSource").asString()).isEqualTo("GEOFENCE");
        assertThat(mid.get("departureSource").asString()).isEqualTo("GEOFENCE");
        assertThat(mid.get("outOfOrder").asBoolean()).isFalse();
        assertThat(Instant.parse(mid.get("departedAt").asString())).isAfter(Instant.parse(mid.get("arrivedAt").asString()));
        JsonNode last = afterDrive.get(2);
        assertThat(last.get("status").asString()).isEqualTo("ARRIVED");
        assertThat(last.get("arrivalSource").asString()).isEqualTo("GEOFENCE");
        assertThat(last.get("outOfOrder").asBoolean()).isFalse();

        // Driving out of the destination completes the trip, and its last stop with it.
        assertThat(ping(19.35, 73.15, now.minus(Duration.ofHours(2))).status()).isEqualTo(202);
        assertThat(status(trip)).isEqualTo("COMPLETED");
        JsonNode done = stops(trip).get(2);
        assertThat(done.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(absent(done, "completedAt")).isFalse();
        assertThat(audits(trip, "TRIP_STOP_ARRIVED")).isEqualTo(3);
        assertThat(audits(trip, "TRIP_STOP_DEPARTED")).isEqualTo(2);
        assertThat(audits(trip, "TRIP_STOP_COMPLETED")).isEqualTo(1);
    }

    @Test
    @DisplayName("an arrival ahead of a stop still waiting is recorded and flagged out of order, never refused")
    void outOfOrderIsFlagged() {
        UUID trip = raiseMultiDropTrip();
        Instant now = Instant.now();
        dispatch(trip, now.minus(Duration.ofHours(6)), now.minus(Duration.ofHours(5)));

        driveTo(now.minus(Duration.ofHours(4)), false, true);

        JsonNode stops = stops(trip);
        assertThat(status(trip)).isEqualTo("AT_DESTINATION");
        assertThat(stops.get(1).get("status").asString()).isEqualTo("PLANNED");
        assertThat(stops.get(2).get("status").asString()).isEqualTo("ARRIVED");
        assertThat(stops.get(2).get("outOfOrder").asBoolean()).isTrue();
        assertThat(stops.get(0).get("outOfOrder").asBoolean()).isFalse();

        // And the manual recording is the same: the skipped-over stop can still be reached late.
        assertThat(act(trip, stops.get(1), "arrival", "{}").status()).isEqualTo(204);
        assertThat(stops(trip).get(1).get("outOfOrder").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("with the automatic switches off, a crossing records nothing on the stops and a person arrives, departs and skips them")
    void manualWhenAutoIsOff() {
        setting(Map.of(ExecutionSettingDefinitions.AUTO_ARRIVAL, false, ExecutionSettingDefinitions.AUTO_DEPARTURE, false));
        UUID trip = raiseMultiDropTrip();
        Instant now = Instant.now();
        dispatch(trip, now.minus(Duration.ofHours(6)), now.minus(Duration.ofHours(5)));

        driveTo(now.minus(Duration.ofHours(4)), true, false);

        JsonNode untouched = stops(trip);
        assertThat(status(trip)).isEqualTo("DISPATCHED");
        assertThat(untouched.get(1).get("status").asString()).isEqualTo("PLANNED");
        assertThat(untouched.get(2).get("status").asString()).isEqualTo("PLANNED");

        Instant arrived = now.minus(Duration.ofHours(3)).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        assertThat(act(trip, untouched.get(1), "arrival", "{\"at\":\"" + arrived + "\"}").status()).isEqualTo(204);
        JsonNode reached = stops(trip).get(1);
        assertThat(reached.get("status").asString()).isEqualTo("ARRIVED");
        assertThat(reached.get("arrivalSource").asString()).isEqualTo("MANUAL");
        assertThat(Instant.parse(reached.get("arrivedAt").asString())).isEqualTo(arrived);

        // Repeats and impossible moves are refused by name.
        Outcome again = act(trip, reached, "arrival", "{}");
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("trip-stop-illegal-transition");
        assertThat(act(trip, reached, "skip", json("reason", q("too late"))).code()).isEqualTo("trip-stop-illegal-transition");
        assertThat(act(trip, untouched.get(2), "departure", "{}").code()).isEqualTo("trip-stop-illegal-transition");
        Outcome before = act(trip, reached, "departure", "{\"at\":\"" + arrived.minusSeconds(60) + "\"}");
        assertThat(before.status()).isEqualTo(409);
        assertThat(before.code()).isEqualTo("trip-stop-time-invalid");

        assertThat(act(trip, reached, "departure", "{\"at\":\"" + arrived.plusSeconds(900) + "\"}").status()).isEqualTo(204);
        JsonNode left = stops(trip).get(1);
        assertThat(left.get("status").asString()).isEqualTo("DEPARTED");
        assertThat(left.get("departureSource").asString()).isEqualTo("MANUAL");

        // The last stop is left as COMPLETED.
        JsonNode lastStop = untouched.get(2);
        assertThat(act(trip, lastStop, "arrival", "{}").status()).isEqualTo(204);
        assertThat(act(trip, lastStop, "departure", "{}").status()).isEqualTo(204);
        assertThat(stops(trip).get(2).get("status").asString()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("a stop passed by is skipped with a reason, which is kept; it is no longer waiting, and cannot be reached afterwards")
    void skipping() {
        UUID trip = raiseMultiDropTrip();
        JsonNode mid = stops(trip).get(1);

        assertThat(act(trip, mid, "skip", "{}").status()).isEqualTo(400);
        assertThat(act(trip, mid, "skip", json("reason", q(" "))).status()).isEqualTo(400);
        assertThat(act(trip, mid, "skip", json("reason", q("customer closed for the day"))).status()).isEqualTo(204);

        JsonNode skipped = stops(trip).get(1);
        assertThat(skipped.get("status").asString()).isEqualTo("SKIPPED");
        assertThat(skipped.get("skipReason").asString()).isEqualTo("customer closed for the day");
        assertThat(audits(trip, "TRIP_STOP_SKIPPED")).isEqualTo(1);
        assertThat(act(trip, skipped, "arrival", "{}").code()).isEqualTo("trip-stop-illegal-transition");
        assertThat(act(trip, skipped, "skip", json("reason", q("again"))).code()).isEqualTo("trip-stop-illegal-transition");

        // With the second stop skipped the third is next, not ahead of anything but the pickup.
        JsonNode first = stops(trip).get(0);
        assertThat(act(trip, first, "arrival", "{}").status()).isEqualTo(204);
        assertThat(act(trip, stops(trip).get(2), "arrival", "{}").status()).isEqualTo(204);
        assertThat(stops(trip).get(2).get("outOfOrder").asBoolean()).isFalse();
        assertThat(act(trip, mid, "arrival", "{}").status()).isEqualTo(409);
        assertThat(act(UUID.randomUUID(), mid, "arrival", "{}").status()).isEqualTo(404);
        assertThat(post("/api/v1/trips/" + trip + "/stops/" + UUID.randomUUID() + "/arrival", "{}", TRIP_RIGHTS)
                .status()).isEqualTo(404);
        assertThat(post("/api/v1/trips/" + trip + "/stops/" + mid.get("id").asString() + "/skip",
                json("reason", q("x")), "TRIP_READ").status()).isEqualTo(403);
    }

    // ------------------------------------------------------------------- plan

    @Test
    @DisplayName("the plan is replaced whole under If-Match, refused when stale, invalid, in progress or on a closed trip")
    void replacingThePlan() {
        UUID trip = raiseMultiDropTrip();
        String path = "/api/v1/trips/" + trip + "/stops";
        String etag = etagOf(path, TRIP_RIGHTS);
        Instant planned = Instant.now().plus(Duration.ofHours(8)).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        String checkpointed = "{\"stops\":[" + json("kind", q("PICKUP"), "terminalId", q(yard.origin())) + ","
                + json("kind", q("CHECKPOINT"), "terminalId", q(yard.mid()), "plannedArrivalAt", q(planned)) + ","
                + json("kind", q("DROP"), "terminalId", q(yard.dest())) + "]}";

        assertThat(replace(trip, checkpointed, null).status()).isEqualTo(400);
        Outcome stale = replace(trip, checkpointed, "\"nonsense\"");
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("concurrent-modification");

        Outcome adjacent = replace(trip, "{\"stops\":[" + json("kind", q("PICKUP"), "terminalId", q(yard.origin())) + ","
                + json("kind", q("DROP"), "terminalId", q(yard.origin())) + "]}", etag);
        assertThat(adjacent.status()).isEqualTo(409);
        assertThat(adjacent.code()).isEqualTo("trip-stops-invalid");
        assertThat(replace(trip, "{\"stops\":[]}", etag).status()).isEqualTo(400);
        assertThat(replace(trip, "{\"stops\":[" + json("kind", q("DROP"), "terminalId", q(UUID.randomUUID())) + "]}",
                etag).status()).isEqualTo(404);

        Outcome replaced = replace(trip, checkpointed, etag);
        assertThat(replaced.status()).as(replaced.body()).isEqualTo(200);
        JsonNode body = kit.parse(replaced).get("stops");
        assertThat(body.get(1).get("kind").asString()).isEqualTo("CHECKPOINT");
        assertThat(Instant.parse(body.get(1).get("plannedArrivalAt").asString())).isEqualTo(planned);
        assertThat(stops(trip)).hasSize(3);
        assertThat(audits(trip, "TRIP_STOPS_PLANNED")).isEqualTo(1);

        // The tag moved on: the old one no longer opens the plan.
        assertThat(replace(trip, checkpointed, etag).code()).isEqualTo("concurrent-modification");

        // Once a stop has been reached the plan is history.
        String fresh = etagOf(path, TRIP_RIGHTS);
        assertThat(act(trip, stops(trip).get(0), "arrival", "{}").status()).isEqualTo(204);
        Outcome inProgress = replace(trip, checkpointed, etagOf(path, TRIP_RIGHTS));
        assertThat(inProgress.status()).isEqualTo(409);
        assertThat(inProgress.code()).isEqualTo("trip-stops-in-progress");
        assertThat(fresh).isNotEqualTo(etagOf(path, TRIP_RIGHTS));

        // A cancelled trip's stops are no longer changed.
        UUID cancelled = raiseSingleDropTrip();
        post("/api/v1/trips/" + cancelled + "/cancellation", json("reason", q("not needed")), TRIP_RIGHTS);
        Outcome closed = replace(cancelled, planBody(yard.mid(), null, yard.dest(), null),
                etagOf("/api/v1/trips/" + cancelled + "/stops", TRIP_RIGHTS));
        assertThat(closed.status()).isEqualTo(409);
        assertThat(closed.code()).isEqualTo("trip-stops-trip-closed");
        assertThat(act(cancelled, stops(cancelled).get(0), "arrival", "{}").code()).isEqualTo("trip-stops-trip-closed");
    }

    @Test
    @DisplayName("changing the plan needs TRIP_EXECUTE, and a trip with no stops yet takes one")
    void planRights() {
        UUID trip = raiseMultiDropTrip();
        String path = "/api/v1/trips/" + trip + "/stops";
        assertThat(send("PUT", path, planBody(yard.mid(), null, yard.dest(), null), etagOf(path, TRIP_RIGHTS), "TRIP_READ")
                .status()).isEqualTo(403);

        // A trip raised before stops existed has none.
        jdbc.sql("DELETE FROM trip_stop WHERE trip_id = :id").param("id", trip).update();
        assertThat(stops(trip)).isEmpty();
        Outcome planned = replace(trip, planBody(yard.mid(), null, yard.dest(), null), etagOf(path, TRIP_RIGHTS));
        assertThat(planned.status()).as(planned.body()).isEqualTo(200);
        assertThat(stops(trip)).hasSize(3);
    }

    // ----------------------------------------------------------- late at stop

    @Test
    @DisplayName("reaching a stop later than planned by more than the tolerance raises one late-at-stop exception")
    void lateAtAStop() {
        UUID trip = raiseMultiDropTrip();
        Instant now = Instant.now();
        // The drop on the way was due five hours ago; the lorry reaches it about three hours and twenty minutes ago.
        Instant midDue = now.minus(Duration.ofHours(5));
        Instant destDue = now.minus(Duration.ofHours(2)).minus(Duration.ofMinutes(30));
        String path = "/api/v1/trips/" + trip + "/stops";
        assertThat(replace(trip, planBody(yard.mid(), midDue, yard.dest(), destDue), etagOf(path, TRIP_RIGHTS)).status())
                .isEqualTo(200);
        dispatch(trip, now.minus(Duration.ofHours(6)), now.minus(Duration.ofHours(5)));

        driveTo(now.minus(Duration.ofHours(4)), true, true);

        assertThat(staleTickets(trip)).isEqualTo(1);
        Map<String, Object> ticket = jdbc.sql("""
                SELECT source, severity, subject_type, subject_id, source_key, description
                  FROM exception_ticket WHERE trip_id = :id AND category = 'STOP_LATE'
                """).param("id", trip).query().singleRow();
        assertThat(ticket.get("source")).isEqualTo("STOP_LATE_DETECTED");
        assertThat(ticket.get("severity")).isEqualTo("P3");
        assertThat(ticket.get("subject_type")).isEqualTo("TRIP_STOP");
        JsonNode mid = stops(trip).get(1);
        assertThat(ticket.get("subject_id").toString()).isEqualTo(mid.get("id").asString());
        assertThat(ticket.get("source_key")).isEqualTo("stop-late:" + mid.get("id").asString());
        assertThat(ticket.get("description").toString()).contains("Stop 2").contains("30 minutes");
        assertThat(mid.get("lateMinutes").asLong()).isBetween(90L, 110L);
        // The destination was reached before its planned time: no ticket, and no lateness.
        assertThat(stops(trip).get(2).get("lateMinutes").asLong()).isZero();
        // It holds no settlement: P3.
        assertThat(jdbc.sql("SELECT suspends_settlement FROM exception_ticket WHERE trip_id = :id")
                .param("id", trip).query(Boolean.class).single()).isFalse();
    }

    @Test
    @DisplayName("the tolerance is a setting of the unit: lateness within it raises nothing, beyond it raises a ticket")
    void toleranceIsASetting() {
        setting(Map.of(DetectorSettingDefinitions.STOP_LATE, Duration.ofHours(3)));
        UUID trip = raiseMultiDropTrip();
        Instant now = Instant.now();
        Instant due = now.minus(Duration.ofHours(10));
        String path = "/api/v1/trips/" + trip + "/stops";
        replace(trip, planBody(yard.mid(), due, yard.dest(), due), etagOf(path, TRIP_RIGHTS));
        JsonNode planned = stops(trip);

        // Two hours late is inside three; four hours is not.
        assertThat(act(trip, planned.get(0), "arrival", "{}").status()).isEqualTo(204);
        assertThat(act(trip, planned.get(1), "arrival", "{\"at\":\"" + due.plus(Duration.ofHours(2)) + "\"}").status())
                .isEqualTo(204);
        assertThat(staleTickets(trip)).isZero();
        assertThat(act(trip, planned.get(2), "arrival", "{\"at\":\"" + due.plus(Duration.ofHours(4)) + "\"}").status())
                .isEqualTo(204);
        assertThat(staleTickets(trip)).isEqualTo(1);
    }

    @Test
    @DisplayName("a stop with no planned arrival is never late")
    void noPlanNoLateness() {
        UUID trip = raiseMultiDropTrip();
        assertThat(act(trip, stops(trip).get(0), "arrival", "{\"at\":\"" + Instant.now().minus(Duration.ofDays(2)) + "\"}")
                .status()).isEqualTo(204);

        assertThat(staleTickets(trip)).isZero();
        assertThat(absent(stops(trip).get(0), "lateMinutes")).isTrue();
    }

    // ---------------------------------------------------------------- webhooks

    @Test
    @DisplayName("gate entries and exits and stop arrivals and departures are published to subscribed webhooks")
    void webhooks() {
        Outcome subscribed = kit.post(yard.tenant(), "/api/v1/webhooks/subscriptions", json("name", q("yard-hook"),
                "url", q("https://203.0.113.10/hooks/lms"), "eventTypes",
                "[\"GATE_ENTRY_RECORDED\",\"GATE_EXIT_RECORDED\",\"TRIP_STOP_ARRIVED\",\"TRIP_STOP_DEPARTED\"]"),
                "WEBHOOK_MANAGE", "WEBHOOK_READ");
        assertThat(subscribed.status()).as(subscribed.body()).isEqualTo(201);
        String subscription = id(subscribed);

        String visit = id(post("/api/v1/gate/entries", json("orgUnitId", q(yard.orgUnitId()), "registrationNo",
                q("HOOK 1"), "purpose", q("VISITOR")), "GATE_REGISTER"));
        post("/api/v1/gate/visits/" + visit + "/exit", "{}", "GATE_REGISTER");
        UUID trip = raiseSingleDropTrip();
        JsonNode first = stops(trip).get(0);
        act(trip, first, "arrival", "{}");
        act(trip, first, "departure", "{}");

        JsonNode deliveries = kit.parse(kit.get(yard.tenant(),
                "/api/v1/webhooks/subscriptions/" + subscription + "/deliveries?limit=50", "WEBHOOK_READ")).get("items");
        List<String> types = new java.util.ArrayList<>();
        deliveries.forEach(d -> types.add(d.get("eventType").asString()));
        assertThat(types).containsExactlyInAnyOrder("GATE_ENTRY_RECORDED", "GATE_EXIT_RECORDED", "TRIP_STOP_ARRIVED",
                "TRIP_STOP_DEPARTED");
    }
}
