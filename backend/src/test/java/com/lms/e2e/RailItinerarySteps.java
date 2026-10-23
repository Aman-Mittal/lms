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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.lms.client.api.MultimodalApi;
import com.lms.client.api.SettlementApi;
import com.lms.client.model.CarrierCost;
import com.lms.client.model.FreightBill;
import com.lms.client.model.MatchRequest;
import com.lms.client.model.MatchResponse;
import com.lms.client.model.Itinerary;
import com.lms.client.model.ItineraryLeg;
import com.lms.client.model.PlanItineraryLegRequest;
import com.lms.client.model.PlanItineraryRequest;
import com.lms.client.model.PublishRailTariffRequest;
import com.lms.client.model.RailMilestoneRequest;
import com.lms.client.model.RegisterPartnerRequest;
import com.lms.client.model.RegisterPointRadiusTerminalRequest;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Value;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Multimodal itineraries over HTTP: a road, rail and road shipment through two
 * RAILHEAD terminals, a rail leg priced from the carrier's rate card, rail
 * moved by reported milestones, road legs by their trips, and the cost split
 * by carrier.
 *
 * <p>The itinerary's timeline is expressed as hours before the scenario
 * starts, so it never pins a date. Road legs follow their trips through
 * events, which settle after the request returns, so the steps that depend on
 * that wait for it.
 */
public class RailItinerarySteps {

    @Value("${local.server.port}")
    private int port;

    private HttpWorld world;
    private UUID origin;
    private UUID railheadA;
    private UUID railheadB;
    private UUID destination;
    private UUID roadCarrierOne;
    private UUID roadCarrierTwo;
    private UUID railCarrier;
    private UUID customer;
    private UUID consignee;
    private UUID firstMileTrip;
    private UUID lastMileTrip;
    private UUID itineraryId;
    private OffsetDateTime now;
    private FreightBill railBill;
    private MatchResponse.StatusEnum matched;

    @Given("the demo administrator is signed in at the rail desk")
    public void signIn() {
        world = new HttpWorld(port);
        world.signInAsDemoAdministrator();
        now = OffsetDateTime.now(ZoneOffset.UTC);
    }

    @Given("a corridor of two railheads with a first-mile and a last-mile site is registered")
    public void corridor() {
        var site = RegisterPointRadiusTerminalRequest.CategoryEnum.CUSTOMER_SITE;
        var railhead = RegisterPointRadiusTerminalRequest.CategoryEnum.RAILHEAD;
        origin = world.terminal(site, "RL-ORIGIN", 17.10, 72.90);
        railheadA = world.terminal(railhead, "RL-HEAD-A", 17.50, 73.50);
        railheadB = world.terminal(railhead, "RL-HEAD-B", 18.50, 74.50);
        destination = world.terminal(site, "RL-DEST", 18.60, 74.60);
        customer = world.partner("RLC", RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER);
        consignee = world.partner("RLN", RegisterPartnerRequest.PartnerTypeEnum.CONSIGNEE);
    }

    @Given("two road carriers and a rail carrier are active")
    public void carriers() {
        roadCarrierOne = world.activeVendor("RDONE");
        roadCarrierTwo = world.activeVendor("RDTWO");
        railCarrier = world.activeVendor("RAILCO");
    }

    @Given("the rail carrier charges {int} per wagon between the two railheads")
    public void railRateCard(int perWagon) {
        world.api(MultimodalApi.class).publishRailTariff(new PublishRailTariffRequest()
                .code(world.code("RAIL-RC"))
                .carrierPartnerId(railCarrier)
                .originTerminalId(railheadA)
                .destinationTerminalId(railheadB)
                .currency("INR")
                .perWagonRate(BigDecimal.valueOf(perWagon))
                .perContainerRate(BigDecimal.valueOf(perWagon))
                .minimumCharge(BigDecimal.ZERO)
                .effectiveFrom(LocalDate.now(ZoneOffset.UTC).minusDays(10)), null);
    }

    @Given("trips are assigned for the first mile to the railhead and the last mile from it")
    public void trips() {
        firstMileTrip = world.assignedTrip("FM", customer, consignee, origin, railheadA,
                roadCarrierOne, world.lorry("FM", 20000), world.driver("FM"), 5000);
        lastMileTrip = world.assignedTrip("LM", customer, consignee, railheadB, destination,
                roadCarrierTwo, world.lorry("LM", 20000), world.driver("LM"), 5000);
    }

    @Given("the shipment is planned as road, rail and road with the rail leg priced from the rate card")
    public void plan() {
        itineraryId = world.api(MultimodalApi.class).planItinerary(new PlanItineraryRequest()
                .shipmentRef(world.code("SHP"))
                .currency("INR")
                .legs(List.of(
                        new PlanItineraryLegRequest()
                                .mode(PlanItineraryLegRequest.ModeEnum.ROAD)
                                .fromTerminalId(origin).toTerminalId(railheadA)
                                .plannedDepartureAt(now.minusHours(48))
                                .plannedArrivalAt(now.minusHours(40))
                                .tripId(firstMileTrip)
                                .carrierPartnerId(roadCarrierOne)
                                .costAmount(new BigDecimal("12000")),
                        new PlanItineraryLegRequest()
                                .mode(PlanItineraryLegRequest.ModeEnum.RAIL)
                                .fromTerminalId(railheadA).toTerminalId(railheadB)
                                .plannedDepartureAt(now.minusHours(34))
                                .plannedArrivalAt(now.minusHours(14))
                                .transferDwellMinutes(240)
                                .unitType(PlanItineraryLegRequest.UnitTypeEnum.WAGON)
                                .unitCount(40)
                                .bookingRef(world.code("RR"))
                                .carrierPartnerId(railCarrier),
                        new PlanItineraryLegRequest()
                                .mode(PlanItineraryLegRequest.ModeEnum.ROAD)
                                .fromTerminalId(railheadB).toTerminalId(destination)
                                .plannedDepartureAt(now.minusHours(10))
                                .plannedArrivalAt(now.minusHours(4))
                                .transferDwellMinutes(120)
                                .tripId(lastMileTrip)
                                .carrierPartnerId(roadCarrierTwo)
                                .costAmount(new BigDecimal("9000")))), null).getId();
        assertThat(itinerary().getStatus()).isEqualTo(Itinerary.StatusEnum.PLANNED);
    }

    @When("the first-mile lorry leaves the origin 48 hours ago and delivers to the railhead 40 hours ago")
    public void firstMile() {
        world.dispatch(firstMileTrip, origin, 5000, now.minusHours(48));
        world.deliver(firstMileTrip, now.minusHours(40));
        waitForLeg(1, ItineraryLeg.StatusEnum.ARRIVED);
    }

    @When("the train is reported placed, loaded and departed, then arrived and released")
    public void rail() {
        MultimodalApi itineraries = world.api(MultimodalApi.class);
        report(itineraries, RailMilestoneRequest.MilestoneEnum.PLACED, 38);
        report(itineraries, RailMilestoneRequest.MilestoneEnum.LOADED, 36);
        report(itineraries, RailMilestoneRequest.MilestoneEnum.DEPARTED, 34);
        report(itineraries, RailMilestoneRequest.MilestoneEnum.ARRIVED, 14);
        report(itineraries, RailMilestoneRequest.MilestoneEnum.RELEASED, 12);
        assertThat(leg(2).getStatus()).isEqualTo(ItineraryLeg.StatusEnum.RELEASED);
    }

    @When("the last-mile lorry leaves the second railhead 10 hours ago and delivers 4 hours ago")
    public void lastMile() {
        world.dispatch(lastMileTrip, railheadB, 5000, now.minusHours(10));
        world.deliver(lastMileTrip, now.minusHours(4));
        waitForLeg(3, ItineraryLeg.StatusEnum.ARRIVED);
    }

    @Then("the itinerary is completed over {int} legs with no missed connection")
    public void completed(int legs) {
        Itinerary itinerary = HttpWorld.eventually("the itinerary completing", () -> {
            Itinerary current = itinerary();
            assertThat(current.getStatus()).isEqualTo(Itinerary.StatusEnum.COMPLETED);
            return current;
        });
        assertThat(itinerary.getLegCount()).isEqualTo(legs);
        assertThat(itinerary.getConnectionMissed()).isFalse();
        assertThat(leg(3).getStartedEarly()).isFalse();
    }

    @Then("the rail leg was priced at {int} from the rate card")
    public void railPriced(int cost) {
        ItineraryLeg rail = leg(2);
        assertThat(rail.getRailTariffCode()).isEqualTo(world.code("RAIL-RC"));
        assertThat(rail.getCostAmount()).isEqualByComparingTo(BigDecimal.valueOf(cost));
    }

    @Then("the cost by carrier is {int} for the first road carrier, {int} for rail and {int} for the second road carrier")
    public void costByCarrier(int first, int rail, int second) {
        Itinerary itinerary = itinerary();
        assertThat(itinerary.getCostByCarrier()).hasSize(3);
        assertThat(costOf(itinerary, roadCarrierOne)).isEqualByComparingTo(BigDecimal.valueOf(first));
        assertThat(costOf(itinerary, railCarrier)).isEqualByComparingTo(BigDecimal.valueOf(rail));
        assertThat(costOf(itinerary, roadCarrierTwo)).isEqualByComparingTo(BigDecimal.valueOf(second));
        assertThat(itinerary.getTotalCost())
                .isEqualByComparingTo(BigDecimal.valueOf(first + rail + second));
    }

    @Then("a rail freight bill of {int} is published for the rail carrier and the rail leg")
    public void railBillPublished(int amount) {
        railBill = HttpWorld.eventually("the rail leg's freight bill", () -> {
            SettlementApi settlement = world.api(SettlementApi.class);
            UUID railLegId = leg(2).getId();
            String cursor = null;
            while (true) {
                var page = settlement.listFreightBills(null, cursor, 200);
                var found = page.getItems().stream()
                        .filter(b -> railLegId.equals(b.getItineraryLegId()))
                        .findFirst();
                if (found.isPresent()) {
                    return settlement.getFreightBill(found.get().getId());
                }
                if (!Boolean.TRUE.equals(page.getHasMore())) {
                    throw new AssertionError("no bill for the rail leg");
                }
                cursor = page.getNextCursor();
            }
        });
        assertThat(railBill.getSource()).isEqualTo(FreightBill.SourceEnum.RAIL);
        assertThat(railBill.getStatus()).isEqualTo(FreightBill.StatusEnum.DRAFT);
        assertThat(railBill.getComputedAmount()).isEqualByComparingTo(BigDecimal.valueOf(amount));
        assertThat(railBill.getItineraryNo()).isEqualTo(itinerary().getItineraryNo());
        assertThat(railBill.getLegSeq()).isEqualTo(2);
        assertThat(railBill.getTripId()).isNull();
        assertThat(railBill.getLoadId()).isNull();
        assertThat(railBill.getTariffCode()).isNull();
    }

    @When("the rail carrier invoices {int} against it")
    public void railCarrierInvoices(int amount) {
        matched = world.api(SettlementApi.class).matchFreightBill(railBill.getId(),
                new MatchRequest().claimedAmount(BigDecimal.valueOf(amount)), null).getStatus();
    }

    @Then("the rail freight bill is approved for payment")
    public void railBillApproved() {
        assertThat(matched).isEqualTo(MatchResponse.StatusEnum.APPROVED_FOR_PAYMENT);
        assertThat(world.api(SettlementApi.class).getFreightBill(railBill.getId()).getStatus())
                .isEqualTo(FreightBill.StatusEnum.APPROVED_FOR_PAYMENT);
    }

    // ----------------------------------------------------------- helpers

    private void report(MultimodalApi itineraries, RailMilestoneRequest.MilestoneEnum milestone,
                        int hoursAgo) {
        itineraries.recordRailMilestone(itineraryId, 2, new RailMilestoneRequest()
                .milestone(milestone).at(now.minusHours(hoursAgo)), null);
    }

    private BigDecimal costOf(Itinerary itinerary, UUID carrier) {
        return itinerary.getCostByCarrier().stream()
                .filter(c -> carrier.equals(c.getCarrierPartnerId()))
                .map(CarrierCost::getTotalCost)
                .findFirst().orElseThrow(() -> new AssertionError("carrier not in the split"));
    }

    private Itinerary itinerary() {
        return world.api(MultimodalApi.class).getItinerary(itineraryId);
    }

    private ItineraryLeg leg(int seq) {
        return itinerary().getLegs().stream().filter(l -> l.getSeq() == seq).findFirst().orElseThrow();
    }

    private void waitForLeg(int seq, ItineraryLeg.StatusEnum status) {
        HttpWorld.eventually("leg " + seq + " to be " + status, () -> {
            assertThat(leg(seq).getStatus()).isEqualTo(status);
            return status;
        });
    }
}
