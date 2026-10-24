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
package com.lms.e2e;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.lms.client.api.ControlTowerApi;
import com.lms.client.api.TelematicsApi;
import com.lms.client.api.TripsApi;
import com.lms.client.model.AuthResult;
import com.lms.client.model.Deviation;
import com.lms.client.model.Exception;
import com.lms.client.model.GeofenceCrossing;
import com.lms.client.model.IngestResult;
import com.lms.client.model.IngestResultRejectionsInner;
import com.lms.client.model.Ping;
import com.lms.client.model.PingBatchRequest;
import com.lms.client.model.RegisterPartnerRequest;
import com.lms.client.model.RegisterPointRadiusTerminalRequest;
import com.lms.client.model.TripProgress;
import com.lms.client.model.VehiclePosition;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The telematics read side over HTTP: positions posted along a trip, then read
 * back as the latest fix, the track, the fleet map, geofence crossings,
 * progress and route deviations.
 *
 * <p>Latitude band 55, which no other mode uses. The origin and destination
 * are about 25 km apart on the diagonal, and the route corridor is 25 km wide
 * either side of the straight line between them, so a fix near the middle is
 * on route and one 30 km beyond the destination is not.
 *
 * <p>The contract and the server disagree on the shape of some of these
 * answers (the fleet map is an array that the contract calls one object;
 * crossings, progress and deviations use other field names). Where a
 * generated model would drop the real field these steps read the JSON itself,
 * and say so.
 */
public class TelematicsReadSteps {

    private static final double ORIGIN_LAT = 55.10;
    private static final double ORIGIN_LON = 72.90;
    private static final double DEST_LAT = 55.30;
    private static final double DEST_LON = 73.10;
    private static final double MID_LAT = 55.20;
    private static final double MID_LON = 73.00;
    private static final double LATE_LAT = 55.25;
    private static final double LATE_LON = 73.05;
    private static final double FAR_LAT = 55.60;
    private static final double FAR_LON = 73.00;
    private static final double STRAY_DEST_LAT = 55.35;
    private static final double STRAY_DEST_LON = 73.15;
    private static final int CORRIDOR_M = 25_000;

    @Value("${local.server.port}")
    private int port;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private HttpWorld world;
    private MovementSupport support;
    private OffsetDateTime now;
    private UUID origin;
    private UUID destination;
    private UUID vendor;
    private UUID customer;
    private UUID consignee;
    private UUID lorry;
    private UUID idleLorry;
    private String lorryRegistration;
    private String idleRegistration;
    private UUID tripId;
    private String tripNo;
    private UUID strayLorry;
    private UUID strayTripId;
    private String strayTripNo;
    private final List<Ping> goodPings = new ArrayList<>();
    private Ping stray;
    private AuthResult otherTenant;

    @Given("the demo administrator is signed in at the telematics read desk")
    public void signIn() {
        world = new HttpWorld(port);
        world.signInAsDemoAdministrator();
        support = new MovementSupport(world, port, jdbc, passwordEncoder, transactionManager);
        now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
    }

    @Given("an origin, a destination, an idle lorry and a lorry on a dispatched trip are registered for the telematics reads")
    public void network() {
        var site = RegisterPointRadiusTerminalRequest.CategoryEnum.CUSTOMER_SITE;
        origin = world.terminal(site, "TL-ORIGIN", ORIGIN_LAT, ORIGIN_LON);
        destination = world.terminal(site, "TL-DEST", DEST_LAT, DEST_LON);
        vendor = world.activeVendor("TLV");
        customer = world.partner("TLC", RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER);
        consignee = world.partner("TLN", RegisterPartnerRequest.PartnerTypeEnum.CONSIGNEE);
        lorry = world.lorry("TLA", 20000);
        idleLorry = world.lorry("TLB", 20000);
        lorryRegistration = "MH-TLA-" + world.salt;
        idleRegistration = "MH-TLB-" + world.salt;
        tripNo = "TR-TL-" + world.salt;
        tripId = world.assignedTrip("TL", customer, consignee, origin, destination, vendor, lorry,
                world.driver("TL"), 5000);
        world.dispatch(tripId, origin, 5000, now.minusHours(5));
    }

    @Given("a second lorry is dispatched from the same origin to a second destination for the telematics reads")
    public void strayTrip() {
        strayLorry = world.lorry("TLS", 20000);
        strayTripNo = "TR-TS-" + world.salt;
        // Its own destination: a lane may have only one routing guide per vehicle type.
        UUID strayDestination = world.terminal(
                RegisterPointRadiusTerminalRequest.CategoryEnum.CUSTOMER_SITE, "TL-DEST2",
                STRAY_DEST_LAT, STRAY_DEST_LON);
        strayTripId = world.assignedTrip("TS", customer, consignee, origin, strayDestination,
                vendor, strayLorry, world.driver("TS"), 5000);
        world.dispatch(strayTripId, origin, 5000, now.minusHours(5));
    }

    // ------------------------------------------------------- posting pings

    @When("the carrying lorry reports leaving the origin and moving on, with four bad pings mixed in and the batch out of order")
    public void goodAndBad() {
        Ping atOrigin = ping(lorry, ORIGIN_LAT, ORIGIN_LON, now.minusHours(4));
        Ping midway = ping(lorry, MID_LAT, MID_LON, now.minusHours(3));
        Ping beyond = ping(lorry, LATE_LAT, LATE_LON, now.minusHours(2));
        goodPings.addAll(List.of(atOrigin, midway, beyond));

        Ping tooFast = ping(lorry, MID_LAT, MID_LON, now.minusMinutes(200))
                .speedKph(new BigDecimal("300"));
        Ping imprecise = ping(lorry, MID_LAT, MID_LON, now.minusMinutes(190))
                .accuracyM(new BigDecimal("900"));
        Ping noLock = new Ping().vehicleId(lorry).recordedAt(now.minusMinutes(180))
                .lat(BigDecimal.ZERO).lon(BigDecimal.ZERO);
        Ping teleport = ping(lorry, 56.90, LATE_LON, now.minusMinutes(119));
        lastIngest = world.api(TelematicsApi.class).ingestPings(new PingBatchRequest().pings(
                List.of(beyond, teleport, atOrigin, noLock, midway, tooFast, imprecise)), null);
    }

    private IngestResult lastIngest;

    @Then("three pings are accepted and four are rejected, each with its reason")
    public void rejections() {
        assertThat(lastIngest.getAccepted()).isEqualTo(3);
        assertThat(lastIngest.getRejected()).isEqualTo(4);
        assertThat(lastIngest.getRejections()).extracting(IngestResultRejectionsInner::getReason)
                .anySatisfy(reason -> assertThat(reason).contains("implausible speed of 300"))
                .anySatisfy(reason -> assertThat(reason).contains("cannot decide a geofence"))
                .anySatisfy(reason -> assertThat(reason).contains("null island"))
                .anySatisfy(reason -> assertThat(reason).contains("kph since the previous fix"));
        assertThat(lastIngest.getRejections())
                .extracting(IngestResultRejectionsInner::getVehicleId).containsOnly(lorry);
    }

    @When("the carrying lorry reports arriving at the destination")
    public void arrives() {
        Ping atDestination = ping(lorry, DEST_LAT, DEST_LON, now.minusHours(1));
        goodPings.add(atDestination);
        post(atDestination);
    }

    @When("the carrying lorry reports leaving the origin, passing the midpoint and reaching three-quarters of the way")
    public void alongTheWay() {
        Ping atOrigin = ping(lorry, ORIGIN_LAT, ORIGIN_LON, now.minusHours(4));
        Ping midway = ping(lorry, MID_LAT, MID_LON, now.minusHours(3));
        Ping beyond = ping(lorry, LATE_LAT, LATE_LON, now.minusHours(2));
        goodPings.addAll(List.of(atOrigin, midway, beyond));
        post(atOrigin, midway, beyond);
    }

    @When("the idle lorry reports twice and then a delayed older fix arrives")
    public void idleReports() {
        post(ping(idleLorry, MID_LAT, MID_LON, now.minusHours(2)),
                ping(idleLorry, LATE_LAT, LATE_LON, now.minusHours(1)));
        post(ping(idleLorry, 55.22, 73.02, now.minusMinutes(90)));
    }

    // ------------------------------------------------------- latest position

    @Then("the carrying lorry's latest position is its newest good fix")
    public void latestIsNewest() {
        assertLatest(goodPings.get(goodPings.size() - 1));
    }

    @Then("the carrying lorry's latest position is the destination fix")
    public void latestIsDestination() {
        assertLatest(goodPings.get(goodPings.size() - 1));
        assertThat(goodPings.get(goodPings.size() - 1).getLat().doubleValue()).isEqualTo(DEST_LAT);
    }

    private void assertLatest(Ping expected) {
        VehiclePosition position = world.api(TelematicsApi.class).getPosition(lorry);
        assertThat(position.getVehicleId()).isEqualTo(lorry);
        assertThat(position.getRegistrationNo()).isEqualTo(lorryRegistration);
        assertThat(position.getTripNo()).isEqualTo(tripNo);
        assertThat(position.getLat().doubleValue())
                .isCloseTo(expected.getLat().doubleValue(), within(1e-6));
        assertThat(position.getLon().doubleValue())
                .isCloseTo(expected.getLon().doubleValue(), within(1e-6));
        assertThat(position.getRecordedAt().toInstant())
                .isEqualTo(expected.getRecordedAt().toInstant());
        assertThat(position.getSpeedKph()).isEqualByComparingTo("55");
    }

    // --------------------------------------------------------------- track

    @Then("the track holds the good fixes in time order and none of the bad ones")
    public void track() {
        List<List<BigDecimal>> track = world.api(TelematicsApi.class).getTrack(tripId);
        assertThat(track).hasSize(goodPings.size());
        for (int i = 0; i < goodPings.size(); i++) {
            // GeoJSON order: longitude first.
            assertThat(track.get(i)).hasSize(2);
            assertThat(track.get(i).get(0).doubleValue())
                    .isCloseTo(goodPings.get(i).getLon().doubleValue(), within(1e-6));
            assertThat(track.get(i).get(1).doubleValue())
                    .isCloseTo(goodPings.get(i).getLat().doubleValue(), within(1e-6));
        }
    }

    // ----------------------------------------------------------- fleet map

    @Then("the fleet map holds the carrying lorry with its trip and the idle lorry without one, in registration order")
    public void fleetMap() {
        // The contract says one VehiclePosition; the server answers an array.
        // Read it as the array it is (see the defect noted in the report).
        JsonNode fleet = support.rawGet("/api/v1/telematics/positions");
        assertThat(fleet.isArray()).isTrue();
        List<String> registrations = MovementSupport.texts(fleet, "registrationNo");
        assertThat(registrations).containsOnlyOnce(lorryRegistration, idleRegistration);
        assertThat(registrations.indexOf(lorryRegistration))
                .isLessThan(registrations.indexOf(idleRegistration));

        JsonNode carrying = MovementSupport.elementWhere(fleet, "registrationNo", lorryRegistration);
        assertThat(carrying.path("vehicleId").asText()).isEqualTo(lorry.toString());
        assertThat(carrying.path("tripNo").asText()).isEqualTo(tripNo);
        assertThat(carrying.path("tripStatus").asText()).isEqualTo("IN_TRANSIT");
        assertThat(carrying.path("lat").asDouble()).isCloseTo(LATE_LAT, within(1e-6));

        JsonNode idle = MovementSupport.elementWhere(fleet, "registrationNo", idleRegistration);
        assertThat(idle.path("vehicleId").asText()).isEqualTo(idleLorry.toString());
        assertThat(MovementSupport.absent(idle.path("tripNo"))).isTrue();
    }

    @Then("the fleet map shows the idle lorry at its newest fix and not the delayed one")
    public void idleLatest() {
        JsonNode fleet = support.rawGet("/api/v1/telematics/positions");
        JsonNode idle = MovementSupport.elementWhere(fleet, "registrationNo", idleRegistration);
        assertThat(idle.path("lat").asDouble()).isCloseTo(LATE_LAT, within(1e-6));
        assertThat(MovementSupport.instant(idle.path("recordedAt")))
                .isEqualTo(now.minusHours(1).toInstant());

        VehiclePosition position = world.api(TelematicsApi.class).getPosition(idleLorry);
        assertThat(position.getLat().doubleValue()).isCloseTo(LATE_LAT, within(1e-6));
        assertThat(position.getTripNo()).isNull();
    }

    // ------------------------------------------------------------ crossings

    @Then("the crossings show the lorry entering and then leaving the origin and the trip in transit")
    public void leftOrigin() {
        List<GeofenceCrossing> crossings = world.api(TelematicsApi.class).listCrossings(tripId);
        assertThat(crossings).extracting(GeofenceCrossing::getTerminalCode)
                .containsExactly("TL-ORIGIN-" + world.salt, "TL-ORIGIN-" + world.salt);
        assertThat(crossings.get(0).getOccurredAt().toInstant())
                .isEqualTo(now.minusHours(4).toInstant());
        assertThat(crossings.get(1).getOccurredAt().toInstant())
                .isEqualTo(now.minusHours(3).toInstant());
        // The contract names the direction `crossing`; the server sends `eventType`.
        JsonNode raw = support.rawGet("/api/v1/telematics/trips/" + tripId + "/crossings");
        assertThat(MovementSupport.texts(raw, "eventType")).containsExactly("ENTERED", "EXITED");
        assertThat(world.api(TripsApi.class).getTrip(tripId).getStatus()).hasToString("IN_TRANSIT");
    }

    @Then("the crossings end with the lorry entering the destination and the trip is at its destination")
    public void reachedDestination() {
        JsonNode raw = support.rawGet("/api/v1/telematics/trips/" + tripId + "/crossings");
        assertThat(MovementSupport.texts(raw, "eventType"))
                .containsExactly("ENTERED", "EXITED", "ENTERED");
        assertThat(MovementSupport.texts(raw, "terminalCode")).containsExactly(
                "TL-ORIGIN-" + world.salt, "TL-ORIGIN-" + world.salt, "TL-DEST-" + world.salt);
        assertThat(MovementSupport.instant(raw.get(2).path("occurredAt")))
                .isEqualTo(now.minusHours(1).toInstant());
        assertThat(world.api(TelematicsApi.class).listCrossings(tripId)).hasSize(3);
        assertThat(world.api(TripsApi.class).getTrip(tripId).getStatus()).hasToString("AT_DESTINATION");
    }

    // ------------------------------------------------------------- progress

    @Then("the progress reports the distance still to go and the completion implied by the latest fix")
    public void progressUnderway() {
        assertProgress(LATE_LAT, LATE_LON);
    }

    @Then("the progress reports the trip as complete with nothing left to go")
    public void progressDone() {
        assertProgress(DEST_LAT, DEST_LON);
        JsonNode raw = support.rawGet("/api/v1/telematics/trips/" + tripId + "/progress");
        assertThat(raw.path("completionPct").asDouble()).isEqualTo(100.0);
        assertThat(raw.path("remainingDistanceM").asDouble()).isLessThan(1.0);
        assertThat(raw.path("status").asText()).isEqualTo("AT_DESTINATION");
    }

    private void assertProgress(double lastLat, double lastLon) {
        double lonShift = world.shiftedLon(0).doubleValue();
        double toGo = MovementSupport.haversineMetres(lastLat, lastLon + lonShift,
                DEST_LAT, DEST_LON + lonShift);
        double total = MovementSupport.haversineMetres(ORIGIN_LAT, ORIGIN_LON + lonShift,
                DEST_LAT, DEST_LON + lonShift);
        double expectedPct = (total - toGo) / total * 100;

        TripProgress viaClient = world.api(TelematicsApi.class).getProgress(tripId);
        assertThat(viaClient.getTripId()).isEqualTo(tripId);

        // The contract calls these progressPct, remainingMetres and
        // estimatedArrivalAt; the server sends completionPct,
        // remainingDistanceM and estimatedMinutesRemaining.
        JsonNode raw = support.rawGet("/api/v1/telematics/trips/" + tripId + "/progress");
        assertThat(raw.path("tripId").asText()).isEqualTo(tripId.toString());
        assertThat(raw.path("tripNo").asText()).isEqualTo(tripNo);
        assertThat(raw.path("completionPct").asDouble()).isCloseTo(expectedPct, within(0.05));
        assertThat(raw.path("remainingDistanceM").asDouble()).isCloseTo(toGo, within(0.5));
        assertThat(raw.path("estimatedMinutesRemaining").asInt())
                .isEqualTo((int) Math.round(toGo / 1000.0 / 40.0 * 60));
        assertThat(raw.path("lat").asDouble()).isCloseTo(lastLat, within(1e-6));
        assertThat(raw.path("lon").asDouble()).isCloseTo(lastLon + lonShift, within(1e-6));
        assertThat(raw.path("offRoute").asBoolean()).isFalse();
        assertThat(MovementSupport.absent(raw.path("deviationM"))).isTrue();
        assertThat(raw.path("completionPct").asDouble()).isBetween(0.0, 100.0);
    }

    // ----------------------------------------------------------- deviations

    @When("the on-route lorry reports along the corridor and the second lorry reports thirty kilometres beyond the destination")
    public void deviate() {
        post(ping(lorry, ORIGIN_LAT, ORIGIN_LON, now.minusHours(4)),
                ping(lorry, MID_LAT, MID_LON, now.minusHours(3)),
                ping(lorry, LATE_LAT, LATE_LON, now.minusHours(2)));
        post(ping(strayLorry, MID_LAT, MID_LON, now.minusHours(3)));
        stray = ping(strayLorry, FAR_LAT, FAR_LON, now.minusHours(2));
        post(stray);
    }

    @Then("only the second lorry's trip is an open deviation, beyond the corridor, at the fix that opened it")
    public void openDeviation() {
        List<Deviation> open = HttpWorld.eventually("the deviation listed", () -> {
            List<Deviation> found = world.api(TelematicsApi.class).listOpenDeviations();
            assertThat(found).extracting(Deviation::getTripNo).contains(strayTripNo);
            return found;
        });
        assertThat(open).extracting(Deviation::getTripNo).doesNotContain(tripNo);
        assertThat(open).extracting(Deviation::getTripNo).containsOnlyOnce(strayTripNo);

        // Contract: vehicleRegistrationNo, offRouteMetres, openedAt, closedAt.
        // Server: detectedAt, distanceM, corridorM, lat, lon (and tripId).
        JsonNode raw = MovementSupport.elementWhere(
                support.rawGet("/api/v1/telematics/deviations"), "tripNo", strayTripNo);
        assertThat(raw.path("tripId").asText()).isEqualTo(strayTripId.toString());
        assertThat(raw.path("distanceM").asDouble()).isGreaterThan(CORRIDOR_M).isLessThan(60_000);
        assertThat(raw.path("corridorM").asDouble()).isEqualTo(CORRIDOR_M);
        assertThat(raw.path("lat").asDouble()).isCloseTo(FAR_LAT, within(1e-6));
        assertThat(raw.path("lon").asDouble()).isCloseTo(stray.getLon().doubleValue(), within(1e-6));
        assertThat(MovementSupport.instant(raw.path("detectedAt")))
                .isEqualTo(now.minusHours(2).toInstant());
    }

    @Then("the second lorry's progress says it is off route by that distance and the first lorry's says it is not")
    public void progressOffRoute() {
        JsonNode straying = support.rawGet("/api/v1/telematics/trips/" + strayTripId + "/progress");
        JsonNode deviation = MovementSupport.elementWhere(
                support.rawGet("/api/v1/telematics/deviations"), "tripNo", strayTripNo);
        assertThat(straying.path("offRoute").asBoolean()).isTrue();
        assertThat(straying.path("deviationM").asDouble())
                .isEqualTo(deviation.path("distanceM").asDouble());

        JsonNode onRoute = support.rawGet("/api/v1/telematics/trips/" + tripId + "/progress");
        assertThat(onRoute.path("offRoute").asBoolean()).isFalse();
        assertThat(MovementSupport.absent(onRoute.path("deviationM"))).isTrue();
    }

    @Then("the control tower raises one route deviation ticket for the second lorry's trip and none for the first")
    public void deviationTicket() {
        List<Exception> tickets = HttpWorld.eventually("the route deviation ticket", () -> {
            List<Exception> found = world.api(ControlTowerApi.class)
                    .listExceptions(null, null, null, 200).getItems().stream()
                    .filter(ticket -> strayTripId.equals(ticket.getTripId())
                            || tripId.equals(ticket.getTripId()))
                    .toList();
            assertThat(found).isNotEmpty();
            return found;
        });
        assertThat(tickets).extracting(Exception::getTripId).containsOnly(strayTripId);
        assertThat(tickets).hasSize(1);
        assertThat(tickets.get(0).getCategory().getValue()).isEqualTo("ROUTE_DEVIATION");
        assertThat(tickets.get(0).getSource()).isEqualTo(Exception.SourceEnum.ROUTE_DEVIATION);
        assertThat(tickets.get(0).getSeverity()).isEqualTo(Exception.SeverityEnum.P2);
    }

    @When("the second lorry reports back on the corridor")
    public void backOnRoute() {
        post(ping(strayLorry, LATE_LAT, LATE_LON, now.minusHours(1)));
    }

    @Then("the deviation is no longer open and the second lorry's progress says it is on route")
    public void deviationClosed() {
        assertThat(world.api(TelematicsApi.class).listOpenDeviations())
                .extracting(Deviation::getTripNo).doesNotContain(strayTripNo, tripNo);
        JsonNode straying = support.rawGet("/api/v1/telematics/trips/" + strayTripId + "/progress");
        assertThat(straying.path("offRoute").asBoolean()).isFalse();
        assertThat(MovementSupport.absent(straying.path("deviationM"))).isTrue();
    }

    // ------------------------------------------------------------- refusals

    @Then("a vehicle that never reported and a trip that does not exist are answered as the contract says")
    public void unknowns() {
        UUID nobody = UUID.randomUUID();
        TelematicsApi telematics = world.api(TelematicsApi.class);
        MovementSupport.assertRefused(() -> telematics.getPosition(nobody), 404, "not-found");
        MovementSupport.assertRefused(() -> telematics.getPosition(idleLorry), 404, "not-found");
        MovementSupport.assertRefused(() -> telematics.getProgress(nobody), 404, "not-found");
        assertThat(telematics.getTrack(nobody)).isEmpty();
        assertThat(telematics.listCrossings(nobody)).isEmpty();
        // A trip that exists but has not been tracked yet has an empty track too.
        assertThat(telematics.getTrack(tripId)).isEmpty();
        assertThat(telematics.listCrossings(tripId)).isEmpty();
        JsonNode untracked = support.rawGet("/api/v1/telematics/trips/" + tripId + "/progress");
        assertThat(MovementSupport.absent(untracked.path("lat"))).isTrue();
        assertThat(MovementSupport.absent(untracked.path("lastFixAt"))).isTrue();
        assertThat(MovementSupport.absent(untracked.path("completionPct"))).isTrue();
        assertThat(untracked.path("offRoute").asBoolean()).isFalse();
    }

    @Then("a batch with no pings and a ping with no time are refused as invalid with a code")
    public void invalidBatches() {
        TelematicsApi telematics = world.api(TelematicsApi.class);
        MovementSupport.assertRefused(
                () -> telematics.ingestPings(new PingBatchRequest().pings(List.of()), null),
                400, "validation-failed");
        Throwable untimed = MovementSupport.refusalOf(() -> telematics.ingestPings(
                new PingBatchRequest().pings(List.of(new Ping().vehicleId(idleLorry)
                        .lat(new BigDecimal("55.2")).lon(new BigDecimal("73.0")))), null));
        MovementSupport.assertProblem(untimed, 400, "validation-failed");
        assertThat(((feign.FeignException) untimed).contentUTF8()).contains("recordedAt");
        MovementSupport.assertStatus(() -> telematics.getPosition(idleLorry), 404);
    }

    @Then("a caller without the ingest permission is refused as forbidden and the lorry stays unseen")
    public void forbidden() {
        AuthResult reader = support.callerWith("tlreader", "TRIP_READ");
        MovementSupport.assertRefused(() -> support.as(reader, TelematicsApi.class).ingestPings(
                new PingBatchRequest().pings(List.of(
                        ping(idleLorry, MID_LAT, MID_LON, now.minusHours(1)))), null),
                403, "access-denied");
        MovementSupport.assertStatus(() -> world.api(TelematicsApi.class).getPosition(idleLorry), 404);
    }

    @Then("another tenant's administrator cannot see the lorry, its trip, its track or its crossings")
    public void otherTenantCannotSee() {
        otherTenant = support.otherTenantAdministrator();
        TelematicsApi theirs = support.as(otherTenant, TelematicsApi.class);
        MovementSupport.assertRefused(() -> theirs.getPosition(lorry), 404, "not-found");
        MovementSupport.assertRefused(() -> theirs.getProgress(tripId), 404, "not-found");
        assertThat(theirs.getTrack(tripId)).isEmpty();
        assertThat(theirs.listCrossings(tripId)).isEmpty();
        assertThat(theirs.listOpenDeviations()).isEmpty();
        JsonNode fleet = support.rawGet(otherTenant, "/api/v1/telematics/positions");
        assertThat(MovementSupport.texts(fleet, "vehicleId")).doesNotContain(lorry.toString());

        // Positions cannot be filed against another tenant's lorry either.
        IngestResult attempt = theirs.ingestPings(new PingBatchRequest().pings(List.of(
                ping(lorry, MID_LAT, MID_LON, now.minusMinutes(30)))), null);
        assertThat(attempt.getAccepted()).isZero();
        assertThat(attempt.getRejected()).isEqualTo(1);
        assertThat(attempt.getRejections().get(0).getReason())
                .contains("no such vehicle on this tenant's register");
        assertThat(world.api(TelematicsApi.class).getPosition(lorry).getRecordedAt().toInstant())
                .isEqualTo(goodPings.get(goodPings.size() - 1).getRecordedAt().toInstant());
    }

    // -------------------------------------------------------------- helpers

    private Ping ping(UUID vehicle, double lat, double lon, OffsetDateTime at) {
        return new Ping().vehicleId(vehicle).recordedAt(at)
                .lat(BigDecimal.valueOf(lat)).lon(world.shiftedLon(lon))
                .speedKph(new BigDecimal("55")).accuracyM(new BigDecimal("5"))
                .ignitionOn(true).source("DEVICE");
    }

    private void post(Ping... pings) {
        IngestResult result = world.api(TelematicsApi.class)
                .ingestPings(new PingBatchRequest().pings(List.of(pings)), null);
        assertThat(result.getRejections()).as("rejected pings").isEmpty();
        assertThat(result.getAccepted()).isEqualTo(pings.length);
    }
}
