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

import com.lms.web.ApiTestKit.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Route corridors (issue #223): imported polylines per lane that route deviation
 * is measured against, with the straight line as the fallback.
 */
class RouteCorridorApiTest extends YardApiSupport {

    private static final String INGEST = "TELEMATICS_INGEST";

    /** An L: up the origin's meridian to the destination's parallel, then across. About 22 km, none of it on the diagonal. */
    private static final String L_SHAPED = "[[72.90,19.10],[72.90,19.30],[73.10,19.30]]";

    private Outcome importCorridor(UUID origin, UUID destination, String points, String... extra) {
        java.util.ArrayList<String> pairs = new java.util.ArrayList<>(List.of("originTerminalId", q(origin),
                "destinationTerminalId", q(destination), "name", q("Mumbai - Pune via NH"), "points", points));
        pairs.addAll(List.of(extra));
        return send("PUT", "/api/v1/telematics/corridors", json(pairs.toArray(String[]::new)), null, INGEST);
    }

    private List<Map<String, Object>> deviations(UUID trip) {
        return jdbc.sql("SELECT reference, corridor_m, distance_m, resolved_at FROM route_deviation WHERE trip_id = :id "
                + "ORDER BY detected_at").param("id", trip).query().listOfRows();
    }

    // -------------------------------------------------------------- the store

    @Test
    @DisplayName("a corridor is imported for a lane, listed without its positions, read with them, replaced in place and cleared")
    void importListReplaceClear() {
        Outcome imported = importCorridor(yard.origin(), yard.dest(), L_SHAPED, "widthMetres", "2000");
        assertThat(imported.status()).as(imported.body()).isEqualTo(200);
        String id = id(imported);

        JsonNode list = kit.parse(get("/api/v1/telematics/corridors"));
        assertThat(list.get("items")).hasSize(1);
        JsonNode listed = list.get("items").get(0);
        assertThat(listed.get("id").asString()).isEqualTo(id);
        assertThat(listed.get("pointCount").asInt()).isEqualTo(3);
        assertThat(listed.get("widthMetres").asDouble()).isEqualTo(2000.0);
        assertThat(listed.get("originTerminalCode").asString()).startsWith("O-");
        assertThat(listed.get("points") == null || listed.get("points").isNull()).isTrue();

        JsonNode read = kit.parse(get("/api/v1/telematics/corridors/" + id));
        assertThat(read.get("points")).hasSize(3);
        assertThat(read.get("points").get(1).get(0).asDouble()).isEqualTo(72.90);
        assertThat(read.get("points").get(1).get(1).asDouble()).isEqualTo(19.30);

        // The same lane again replaces the corridor: one per lane, the same id, the version moves on.
        Outcome replaced = importCorridor(yard.origin(), yard.dest(), "[[72.90,19.10],[73.10,19.30]]");
        assertThat(id(replaced)).isEqualTo(id);
        JsonNode again = kit.parse(get("/api/v1/telematics/corridors/" + id));
        assertThat(again.get("pointCount").asInt()).isEqualTo(2);
        assertThat(again.get("version").asLong()).isGreaterThan(read.get("version").asLong());
        assertThat(kit.parse(get("/api/v1/telematics/corridors")).get("items")).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE resource_id = :id").param("id", UUID.fromString(id))
                .query(Long.class).single()).isEqualTo(2);

        assertThat(send("DELETE", "/api/v1/telematics/corridors/" + id, null, null, INGEST).status()).isEqualTo(204);
        assertThat(get("/api/v1/telematics/corridors/" + id).status()).isEqualTo(404);
        assertThat(kit.parse(get("/api/v1/telematics/corridors")).get("items")).isEmpty();
        assertThat(send("DELETE", "/api/v1/telematics/corridors/" + id, null, null, INGEST).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("a corridor runs between two different terminals of the tenant, with two to five thousand valid positions")
    void importRules() {
        assertThat(importCorridor(yard.origin(), yard.origin(), L_SHAPED).code()).isEqualTo("route-corridor-lane-invalid");
        assertThat(importCorridor(yard.origin(), UUID.randomUUID(), L_SHAPED).status()).isEqualTo(404);
        assertThat(importCorridor(yard.origin(), yard.dest(), "[[72.9,19.1]]").status()).isEqualTo(400);
        assertThat(importCorridor(yard.origin(), yard.dest(), "[[72.9,19.1,3.0],[73.1,19.3]]").status()).isEqualTo(400);
        Outcome impossible = importCorridor(yard.origin(), yard.dest(), "[[72.9,19.1],[73.1,95.0]]");
        assertThat(impossible.status()).isEqualTo(409);
        assertThat(impossible.code()).isEqualTo("route-corridor-points-invalid");
        Outcome negative = importCorridor(yard.origin(), yard.dest(), L_SHAPED, "widthMetres", "-5");
        assertThat(negative.status()).isEqualTo(409);
        assertThat(negative.code()).isEqualTo("route-corridor-width-invalid");
        assertThat(importCorridor(yard.origin(), yard.dest(), L_SHAPED, "name", q("")).status()).isEqualTo(400);
        assertThat(kit.parse(get("/api/v1/telematics/corridors")).get("items")).isEmpty();
    }

    @Test
    @DisplayName("importing and clearing need TELEMATICS_INGEST, and another tenant sees nothing of a corridor")
    void rightsAndTenants() {
        String id = id(importCorridor(yard.origin(), yard.dest(), L_SHAPED));
        assertThat(send("PUT", "/api/v1/telematics/corridors", json("originTerminalId", q(yard.origin()),
                "destinationTerminalId", q(yard.dest()), "name", q("x"), "points", L_SHAPED), null, "TRIP_EXECUTE")
                .status()).isEqualTo(403);
        assertThat(send("DELETE", "/api/v1/telematics/corridors/" + id, null, null, "TRIP_EXECUTE").status())
                .isEqualTo(403);

        Yard other = newYard();
        assertThat(kit.get(other.tenant(), "/api/v1/telematics/corridors/" + id).status()).isEqualTo(404);
        assertThat(kit.parse(kit.get(other.tenant(), "/api/v1/telematics/corridors")).get("items")).isEmpty();
        assertThat(kit.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port
                        + "/api/v1/telematics/corridors/" + id)).header("Authorization", "Bearer "
                        + kit.token(other.tenant(), INGEST)).DELETE().build()).status()).isEqualTo(404);
        // The other tenant cannot lay a corridor between this tenant's terminals.
        assertThat(kit.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port
                        + "/api/v1/telematics/corridors")).header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + kit.token(other.tenant(), INGEST))
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(json("originTerminalId", q(yard.origin()),
                        "destinationTerminalId", q(yard.dest()), "name", q("x"), "points", L_SHAPED))).build())
                .status()).isEqualTo(404);
    }

    // ------------------------------------------------------ route deviation

    @Test
    @DisplayName("a trip on a lane with a corridor is measured against it; clearing the corridor returns it to the straight line")
    void deviationAgainstACorridorAndTheFallback() {
        UUID trip = raiseSingleDropTrip();
        Instant now = Instant.now();
        dispatch(trip, now.minus(Duration.ofHours(6)), now.minus(Duration.ofHours(5)));
        Instant t = now.minus(Duration.ofHours(4));

        // No corridor: the straight line O-D is the reference. The L's corner is 7.6 km from it, inside the 25 km setting.
        assertThat(ping(ORIGIN_AT, t).status()).isEqualTo(202);
        assertThat(ping(19.30, 72.90, t = t.plus(Duration.ofMinutes(30))).status()).isEqualTo(202);
        assertThat(deviations(trip)).isEmpty();

        // The lane gets its road, two kilometres wide. The corner is on it; the middle of the diagonal is not.
        assertThat(importCorridor(yard.origin(), yard.dest(), L_SHAPED, "widthMetres", "2000").status()).isEqualTo(200);
        assertThat(ping(19.30, 72.95, t = t.plus(Duration.ofMinutes(30))).status()).isEqualTo(202);
        assertThat(deviations(trip)).isEmpty();
        assertThat(ping(19.20, 73.00, t = t.plus(Duration.ofMinutes(30))).status()).isEqualTo(202);

        List<Map<String, Object>> found = deviations(trip);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).get("reference")).isEqualTo("CORRIDOR");
        assertThat(((Number) found.get(0).get("corridor_m")).doubleValue()).isEqualTo(2000.0);
        assertThat(((Number) found.get(0).get("distance_m")).doubleValue()).isBetween(9_000.0, 12_000.0);
        assertThat(found.get(0).get("resolved_at")).isNull();
        JsonNode open = kit.parse(get("/api/v1/telematics/deviations")).get(0);
        assertThat(open.get("reference").asString()).isEqualTo("CORRIDOR");

        // Clear it: the same place is on the straight line, so the excursion closes.
        String id = kit.parse(get("/api/v1/telematics/corridors")).get("items").get(0).get("id").asString();
        assertThat(send("DELETE", "/api/v1/telematics/corridors/" + id, null, null, INGEST).status()).isEqualTo(204);
        assertThat(ping(19.21, 73.01, t.plus(Duration.ofMinutes(30))).status()).isEqualTo(202);
        assertThat(deviations(trip).get(0).get("resolved_at")).isNotNull();
        assertThat(kit.parse(get("/api/v1/telematics/deviations"))).isEmpty();
    }

    @Test
    @DisplayName("without a width the corridor is held to the trip's own deviation setting, and the straight line stays the fallback on a lane without one")
    void deviationFallsBackOnLanesWithoutACorridor() {
        UUID trip = raiseSingleDropTrip();
        Instant now = Instant.now();
        dispatch(trip, now.minus(Duration.ofHours(6)), now.minus(Duration.ofHours(5)));
        // A corridor for the reverse lane means nothing to this trip.
        assertThat(importCorridor(yard.dest(), yard.origin(), "[[73.10,19.30],[73.10,19.10],[72.90,19.10]]").status())
                .isEqualTo(200);
        Instant t = now.minus(Duration.ofHours(4));

        assertThat(ping(ORIGIN_AT, t).status()).isEqualTo(202);
        assertThat(ping(19.20, 73.05, t.plus(Duration.ofMinutes(30))).status()).isEqualTo(202);

        assertThat(deviations(trip)).isEmpty();
    }
}
