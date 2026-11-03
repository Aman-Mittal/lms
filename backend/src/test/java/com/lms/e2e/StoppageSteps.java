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
import java.util.List;
import java.util.UUID;

import com.lms.client.api.ControlTowerApi;
import com.lms.client.api.TelematicsApi;
import com.lms.client.api.TripsApi;
import com.lms.client.model.Exception;
import com.lms.client.model.PingBatchRequest;
import com.lms.client.model.Ping;
import com.lms.client.model.RegisterPartnerRequest;
import com.lms.client.model.RegisterPointRadiusTerminalRequest;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Value;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Stoppage detection over HTTP: positions posted to the telematics endpoint
 * carry a dispatched trip into transit and then hold still, and the control
 * tower board shows the ticket.
 *
 * <p>Latitude band 21: no other mode uses it, so the spot the lorry waits at
 * can never fall inside another scenario's terminal. The lorry is parked
 * between the origin and the destination, outside both geofences.
 */
public class StoppageSteps {

    private static final double ORIGIN_LAT = 21.10;
    private static final double ORIGIN_LON = 72.90;
    private static final double SPOT_LAT = 21.20;
    private static final double SPOT_LON = 73.00;

    @Value("${local.server.port}")
    private int port;

    private HttpWorld world;
    private UUID origin;
    private UUID lorry;
    private UUID tripId;

    @Given("the demo administrator is signed in at the stoppage desk")
    public void signIn() {
        world = new HttpWorld(port);
        world.signInAsDemoAdministrator();
    }

    @Given("two sites and a dispatched lorry are registered for the stoppage watch")
    public void network() {
        var site = RegisterPointRadiusTerminalRequest.CategoryEnum.CUSTOMER_SITE;
        origin = world.terminal(site, "SP-ORIGIN", ORIGIN_LAT, ORIGIN_LON);
        UUID destination = world.terminal(site, "SP-DEST", 21.30, 73.10);
        UUID vendor = world.activeVendor("SPV");
        UUID customer = world.partner("SPC", RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER);
        UUID consignee = world.partner("SPN", RegisterPartnerRequest.PartnerTypeEnum.CONSIGNEE);
        lorry = world.lorry("SP", 20000);
        UUID driver = world.driver("SP");
        tripId = world.assignedTrip("SP", customer, consignee, origin, destination, vendor,
                lorry, driver, 5000);
        world.dispatch(tripId, origin, 5000, OffsetDateTime.now(ZoneOffset.UTC).minusHours(5));
    }

    @When("the lorry reports leaving the origin and then sitting at one spot for {int} hours")
    public void sitting(int hours) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        post(ping(ORIGIN_LAT, ORIGIN_LON, now.minusHours(hours + 2)),
                ping(SPOT_LAT, SPOT_LON, now.minusHours(hours + 1)),
                ping(SPOT_LAT, SPOT_LON, now.minusHours(1)));
    }

    @When("the lorry reports the same spot again")
    public void again() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        post(ping(SPOT_LAT, SPOT_LON, now.minusMinutes(30)),
                ping(SPOT_LAT, SPOT_LON, now.minusMinutes(5)));
    }

    @Then("the trip is in transit")
    public void inTransit() {
        assertThat(world.api(TripsApi.class).getTrip(tripId).getStatus())
                .hasToString("IN_TRANSIT");
    }

    @Then("one stoppage ticket is on the board for the trip, located at that spot")
    public void oneTicket() {
        List<Exception> tickets = HttpWorld.eventually("the stoppage ticket", () -> {
            List<Exception> found = world.api(ControlTowerApi.class)
                    .listExceptions(null, null, null, 200).getItems().stream()
                    .filter(ticket -> tripId.equals(ticket.getTripId()))
                    .toList();
            assertThat(found).hasSize(1);
            return found;
        });
        Exception ticket = tickets.get(0);
        assertThat(ticket.getCategory().getValue()).isEqualTo("EXTENDED_STOPPAGE");
        assertThat(ticket.getSource()).isEqualTo(Exception.SourceEnum.STOPPAGE_DETECTED);
        assertThat(ticket.getSeverity()).isEqualTo(Exception.SeverityEnum.P2);
        assertThat(ticket.getPositionSource()).isEqualTo(Exception.PositionSourceEnum.DETECTOR);
        assertThat(ticket.getLat().doubleValue()).isCloseTo(SPOT_LAT, within(0.0001));
        assertThat(ticket.getLon().doubleValue())
                .isCloseTo(world.shiftedLon(SPOT_LON).doubleValue(), within(0.0001));
    }

    // --------------------------------------------------------------- helpers

    private Ping ping(double lat, double lon, OffsetDateTime at) {
        return new Ping().vehicleId(lorry).recordedAt(at)
                .lat(BigDecimal.valueOf(lat)).lon(world.shiftedLon(lon));
    }

    private void post(Ping... pings) {
        var result = world.api(TelematicsApi.class)
                .ingestPings(new PingBatchRequest().pings(List.of(pings)), null);
        assertThat(result.getRejections()).as("rejected pings").isEmpty();
        assertThat(result.getAccepted()).isEqualTo(pings.length);
    }
}
