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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.lms.client.api.PlanningApi;
import com.lms.client.api.TerminalsApi;
import com.lms.client.api.TripsApi;
import com.lms.client.model.AuthResult;
import com.lms.client.model.CancelTripRequest;
import com.lms.client.model.Point;
import com.lms.client.model.RaiseTripRequest;
import com.lms.client.model.RegisterPartnerRequest;
import com.lms.client.model.RegisterPointRadiusTerminalRequest;
import com.lms.client.model.Terminal;
import com.lms.client.model.Trip;
import com.lms.client.model.UpdateTerminalRequest;
import feign.FeignException;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

import static com.lms.e2e.MovementSupport.assertRefused;
import static com.lms.e2e.MovementSupport.assertStatus;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trip cancellation and terminal maintenance over HTTP.
 *
 * <p>Latitude band 75: no other mode uses it, and the scenario's own east
 * shift keeps its terminals clear of the other scenarios' on the shared demo
 * tenant. The spare site sits in the band too, far enough from the others
 * that the overlap rule never touches it.
 */
public class TripCancelTerminalSteps {

    private static final double ORIGIN_LAT = 75.10;
    private static final double ORIGIN_LON = 72.90;
    private static final double DEST_LAT = 75.30;
    private static final double DEST_LON = 73.10;

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
    private UUID origin;
    private UUID destination;
    private UUID spare;
    private UUID tripId;
    private UUID loadId;
    private String firstCode;
    private long versionRead;

    @Given("the demo administrator is signed in at the cancellation desk")
    public void signIn() {
        world = new HttpWorld(port);
        world.signInAsDemoAdministrator();
        support = new MovementSupport(world, port, jdbc, passwordEncoder, transactionManager);
    }

    @Given("two sites, a lorry and an assigned trip are registered for the cancellation desk")
    public void assigned() {
        network();
    }

    @Given("two sites, a lorry and a dispatched trip are registered for the cancellation desk")
    public void dispatched() {
        network();
        world.dispatch(tripId, origin, 5000, OffsetDateTime.now(ZoneOffset.UTC).minusHours(2));
    }

    @Given("a spare site is registered for the cancellation desk")
    public void spareSite() {
        spare = site("TCS-SPARE", 75.60, 72.90);
    }

    // ------------------------------------------------------------ cancelling

    @When("the cancellation-desk trip is cancelled because {string}")
    public void cancel(String reason) {
        world.api(TripsApi.class).cancelTrip(tripId, new CancelTripRequest().reason(reason), null);
    }

    @Then("the cancellation-desk trip reads CANCELLED with the reason {string} and a cancellation time")
    public void readsCancelled(String reason) {
        Trip trip = world.api(TripsApi.class).getTrip(tripId);
        assertThat(trip.getStatus()).hasToString("CANCELLED");
        assertThat(trip.getCancellationReason()).isEqualTo(reason);
        assertThat(trip.getCancelledAt()).isNotNull();
    }

    @Then("cancelling it again succeeds and keeps the first reason")
    public void cancelAgain() {
        String first = world.api(TripsApi.class).getTrip(tripId).getCancellationReason();
        world.api(TripsApi.class).cancelTrip(tripId,
                new CancelTripRequest().reason("a second thought"), null);
        assertThat(world.api(TripsApi.class).getTrip(tripId).getCancellationReason())
                .isEqualTo(first);
    }

    @Then("the cancellation-desk load is still AWARDED and a replacement trip can be raised against it but a second one cannot")
    public void loadReleased() {
        assertThat(world.api(PlanningApi.class).getLoad(loadId).getStatus()).isEqualTo("AWARDED");

        TripsApi trips = world.api(TripsApi.class);
        UUID replacement = trips.raiseTrip(new RaiseTripRequest()
                .tripNo("TR-TCR-" + world.salt).loadId(loadId), null).getId();
        assertThat(trips.getTrip(replacement).getStatus()).hasToString("PLANNED");
        assertRefused(() -> trips.raiseTrip(new RaiseTripRequest()
                .tripNo("TR-TCR2-" + world.salt).loadId(loadId), null),
                409, "load-already-has-trip");
        assertThat(trips.getTrip(tripId).getStatus()).hasToString("CANCELLED");
    }

    @Then("cancelling the cancellation-desk trip is refused as not cancellable and it stays DISPATCHED")
    public void notCancellable() {
        TripsApi trips = world.api(TripsApi.class);
        assertRefused(() -> trips.cancelTrip(tripId, new CancelTripRequest().reason("too late"), null),
                409, "trip-not-cancellable");
        assertThat(trips.getTrip(tripId).getStatus()).hasToString("DISPATCHED");
        assertThat(trips.getTrip(tripId).getCancelledAt()).isNull();
    }

    @Then("a cancellation without a reason is refused as invalid")
    public void noReason() {
        TripsApi trips = world.api(TripsApi.class);
        assertRefused(() -> trips.cancelTrip(tripId, new CancelTripRequest().reason(""), null),
                400, "validation-failed");
        assertRefused(() -> trips.cancelTrip(tripId, new CancelTripRequest().reason("   "), null),
                400, "validation-failed");
        assertThat(trips.getTrip(tripId).getStatus()).hasToString("ASSIGNED");
    }

    @Then("a caller without TRIP_EXECUTE is refused as forbidden and the trip is untouched")
    public void lacksPermission() {
        AuthResult reader = support.callerWith("tcreader", "TRIP_READ");
        assertRefused(() -> support.as(reader, TripsApi.class)
                        .cancelTrip(tripId, new CancelTripRequest().reason("no"), null),
                403, "access-denied");
        assertThat(world.api(TripsApi.class).getTrip(tripId).getStatus()).hasToString("ASSIGNED");
    }

    @Then("an unknown trip, another tenant's trip and an anonymous caller are refused as not found, not found and unauthorised")
    public void absentTrips() {
        TripsApi trips = world.api(TripsApi.class);
        assertRefused(() -> trips.cancelTrip(UUID.randomUUID(),
                new CancelTripRequest().reason("x"), null), 404, "not-found");

        AuthResult stranger = support.otherTenantAdministrator();
        assertRefused(() -> support.as(stranger, TripsApi.class)
                .cancelTrip(tripId, new CancelTripRequest().reason("x"), null), 404, "not-found");

        TripsApi anonymous = world.client(null, TripsApi.class);
        assertStatus(() -> anonymous.cancelTrip(tripId, new CancelTripRequest().reason("x"), null),
                401);
        assertThat(world.api(TripsApi.class).getTrip(tripId).getStatus()).hasToString("ASSIGNED");
    }

    // ----------------------------------------------------------- terminals

    @When("the first site is renamed {string} with {int} docks using the version read")
    public void rename(String name, int docks) {
        TerminalsApi terminals = world.api(TerminalsApi.class);
        versionRead = terminals.getTerminal(origin).getVersion();
        terminals.updateTerminal(origin, new UpdateTerminalRequest().name(name).version(versionRead)
                .dockCount(docks).avgDwellMinutes(30));
    }

    @Then("the first site reads back renamed with {int} docks, the same code and a newer version")
    public void readBack(int docks) {
        Terminal terminal = world.api(TerminalsApi.class).getTerminal(origin);
        assertThat(terminal.getName()).isEqualTo("Renamed yard");
        assertThat(terminal.getDockCount()).isEqualTo(docks);
        assertThat(terminal.getCode()).isEqualTo(firstCode);
        assertThat(terminal.getVersion()).isGreaterThan(versionRead);
    }

    @Then("renaming it again with the version read before is refused as a concurrent modification")
    public void stale() {
        TerminalsApi terminals = world.api(TerminalsApi.class);
        assertRefused(() -> terminals.updateTerminal(origin,
                new UpdateTerminalRequest().name("Lost update").version(versionRead)),
                409, "concurrent-modification");
        assertThat(terminals.getTerminal(origin).getName()).isEqualTo("Renamed yard");
    }

    @Then("moving it onto the second site is refused as an overlap and bad bodies are refused as invalid")
    public void refusedBodies() {
        TerminalsApi terminals = world.api(TerminalsApi.class);
        long version = terminals.getTerminal(origin).getVersion();
        Point onto = new Point().lat(BigDecimal.valueOf(DEST_LAT))
                .lon(world.shiftedLon(DEST_LON));

        assertRefused(() -> terminals.updateTerminal(origin, new UpdateTerminalRequest()
                        .name("Renamed yard").version(version).centre(onto)
                        .radiusMetres(new BigDecimal("500"))),
                409, "terminal-overlap");
        // Both shapes, half a circle, no name, no version: all 400.
        assertRefused(() -> terminals.updateTerminal(origin, new UpdateTerminalRequest()
                        .name("x").version(version).centre(onto)
                        .ring(List.of(onto, onto, onto))),
                400, "invalid-argument");
        assertRefused(() -> terminals.updateTerminal(origin, new UpdateTerminalRequest()
                        .name("x").version(version).centre(onto)),
                400, "invalid-argument");
        assertRefused(() -> terminals.updateTerminal(origin, new UpdateTerminalRequest()
                        .name("").version(version)),
                400, "validation-failed");
        assertRefused(() -> terminals.updateTerminal(UUID.randomUUID(), new UpdateTerminalRequest()
                        .name("x").version(version)),
                404, "not-found");
        // Nothing above changed it.
        assertThat(terminals.getTerminal(origin).getVersion()).isEqualTo(version);
    }

    @Then("a caller without TERMINAL_UPDATE is refused as forbidden")
    public void cannotUpdate() {
        AuthResult reader = support.callerWith("tcupd", "TERMINAL_READ", "TERMINAL_CREATE");
        assertRefused(() -> support.as(reader, TerminalsApi.class).updateTerminal(origin,
                        new UpdateTerminalRequest().name("x").version(0L)),
                403, "access-denied");
    }

    @Then("deleting the spare site succeeds, and it is then gone and cannot be deleted again")
    public void deleteSpare() {
        TerminalsApi terminals = world.api(TerminalsApi.class);
        terminals.deleteTerminal(spare);
        assertRefused(() -> terminals.getTerminal(spare), 404, "not-found");
        assertRefused(() -> terminals.deleteTerminal(spare), 404, "not-found");
    }

    @Then("deleting the first site is refused as in use while the trip starts there")
    public void deleteInUse() {
        TerminalsApi terminals = world.api(TerminalsApi.class);
        assertRefused(() -> terminals.deleteTerminal(origin), 409, "terminal-in-use");
        assertThat(terminals.getTerminal(origin).getId()).isEqualTo(origin);
    }

    @Then("the first site is still refused after that trip is cancelled")
    public void stillInUse() {
        world.api(TripsApi.class).cancelTrip(tripId,
                new CancelTripRequest().reason("stood down"), null);
        // A cancelled trip still names its origin, and so do the order and the
        // load: the record of what happened is not the terminal's to erase.
        assertRefused(() -> world.api(TerminalsApi.class).deleteTerminal(origin),
                409, "terminal-in-use");
    }

    @Then("a caller without TERMINAL_DELETE is refused as forbidden and another tenant's administrator is told it is not found")
    public void deleteGuards() {
        AuthResult noDelete = support.callerWith("tcdel", "TERMINAL_READ", "TERMINAL_UPDATE");
        assertRefused(() -> support.as(noDelete, TerminalsApi.class).deleteTerminal(destination),
                403, "access-denied");

        AuthResult stranger = support.otherTenantAdministrator();
        assertRefused(() -> support.as(stranger, TerminalsApi.class).deleteTerminal(destination),
                404, "not-found");
        assertRefused(() -> support.as(stranger, TerminalsApi.class).updateTerminal(destination,
                new UpdateTerminalRequest().name("x").version(0L)), 404, "not-found");
        assertThat(world.api(TerminalsApi.class).getTerminal(destination).getId())
                .isEqualTo(destination);
    }

    // --------------------------------------------------------------- helpers

    private void network() {
        origin = site("TCS-ORIGIN", ORIGIN_LAT, ORIGIN_LON);
        destination = site("TCS-DEST", DEST_LAT, DEST_LON);
        firstCode = world.api(TerminalsApi.class).getTerminal(origin).getCode();
        UUID vendor = world.activeVendor("TCV");
        UUID customer = world.partner("TCC", RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER);
        UUID consignee = world.partner("TCN", RegisterPartnerRequest.PartnerTypeEnum.CONSIGNEE);
        UUID lorry = world.lorry("TC", 20000);
        UUID driver = world.driver("TC");
        tripId = world.assignedTrip("TC", customer, consignee, origin, destination, vendor,
                lorry, driver, 5000);
        loadId = world.api(TripsApi.class).getTrip(tripId).getLoadId();
    }

    private UUID site(String code, double lat, double lon) {
        return world.terminal(RegisterPointRadiusTerminalRequest.CategoryEnum.CUSTOMER_SITE,
                code, lat, lon);
    }
}
