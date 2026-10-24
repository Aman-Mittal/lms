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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.client.api.ControlTowerApi;
import com.lms.client.api.LtlRatingApi;
import com.lms.client.api.OrdersApi;
import com.lms.client.api.PlanningApi;
import com.lms.client.api.SourcingApi;
import com.lms.client.api.TripsApi;
import com.lms.client.model.AddOrderLineRequest;
import com.lms.client.model.AddVendorRequest;
import com.lms.client.model.AllocateRequest;
import com.lms.client.model.Allocation;
import com.lms.client.model.AssignConsignmentRequest;
import com.lms.client.model.AssignExceptionRequest;
import com.lms.client.model.AssignLtlZoneRequest;
import com.lms.client.model.AssignTripRequest;
import com.lms.client.model.AttachTripDocumentRequest;
import com.lms.client.model.Consignment;
import com.lms.client.model.DefineGuideRequest;
import com.lms.client.model.Exception;
import com.lms.client.model.ExceptionActivity;
import com.lms.client.model.ExceptionCategory;
import com.lms.client.model.ExceptionNoteRequest;
import com.lms.client.model.ExceptionSummary;
import com.lms.client.model.FreightMode;
import com.lms.client.model.GateEvent;
import com.lms.client.model.GateInRequest;
import com.lms.client.model.IdResponse;
import com.lms.client.model.LtlLeg;
import com.lms.client.model.MilestoneRequest;
import com.lms.client.model.Offer;
import com.lms.client.model.OpenLoadRequest;
import com.lms.client.model.OrderLine;
import com.lms.client.model.PublishLtlRateCardRequest;
import com.lms.client.model.RaiseExceptionRequest;
import com.lms.client.model.RaiseOrderRequest;
import com.lms.client.model.RaiseTripRequest;
import com.lms.client.model.RegisterPartnerRequest;
import com.lms.client.model.RegisterPointRadiusTerminalRequest;
import com.lms.client.model.ReopenExceptionRequest;
import com.lms.client.model.ResolveExceptionRequest;
import com.lms.client.model.VendorResponseRequest;
import com.lms.client.model.WeighingRequest;
import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;

import static com.lms.e2e.PlanningFlowSupport.assertProblem;
import static com.lms.e2e.PlanningFlowSupport.assertStatus;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Planning reads, sourcing outcomes, the gate log and the control tower's
 * summary and reopen, all over HTTP.
 *
 * <p>Every step text carries the words "planning-flow" because Cucumber glue
 * is global across the package and a step phrase may exist only once. The
 * geography stays in the 50 to 54 degrees north band.
 */
public class PlanningFlowSteps {

    private static final BigDecimal LON = new BigDecimal("10.90");

    @Value("${local.server.port}")
    private int port;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private HttpWorld world;
    private PlanningFlowSupport support;

    private UUID customer;
    private UUID consignee;
    private final Map<String, UUID> vendors = new HashMap<>();
    private UUID origin;
    private UUID destination;
    private UUID lorry;
    private UUID driver;

    private UUID orderId;
    private int[] lineWeights;
    private UUID consignmentId;
    private String trackingRef;
    private UUID loadId;
    private UUID guideId;
    private UUID allocationId;
    private UUID tripId;
    private int tripWeightKg;
    private OffsetDateTime gateInAt;
    private OffsetDateTime dispatchedAt;
    private OffsetDateTime deliveredAt;

    private ExceptionSummary baseline;
    private UUID theftId;
    private UUID delayId;

    // part-load
    private UUID originHub;
    private UUID destinationHub;
    private UUID site;
    private UUID docketId;
    private String docketRef;

    @After("@e2e_planning_flow")
    public void tidy() {
        if (support != null) {
            support.cleanUp();
        }
    }

    // ------------------------------------------------------------ set-up

    @Given("the demo administrator is signed in at the planning-flow desk")
    public void signIn() {
        world = new HttpWorld(port);
        world.signInAsDemoAdministrator();
        support = new PlanningFlowSupport(world, port, jdbc, passwordEncoder, transactionManager);
    }

    @Given("planning-flow parties, vendors {string} and {string}, two depots, a lorry and a driver are registered")
    public void parties(String first, String second) {
        customer = world.partner("PFC", RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER);
        consignee = world.partner("PFN", RegisterPartnerRequest.PartnerTypeEnum.CONSIGNEE);
        vendors.put(first, world.activeVendor("PF" + first));
        vendors.put(second, world.activeVendor("PF" + second));
        origin = world.terminal(RegisterPointRadiusTerminalRequest.CategoryEnum.CUSTOMER_SITE,
                "PF-ORIGIN", 50.10, LON.doubleValue());
        destination = world.terminal(RegisterPointRadiusTerminalRequest.CategoryEnum.CUSTOMER_SITE,
                "PF-DEST", 50.30, LON.doubleValue() + 0.20);
        lorry = world.lorry("PF", 20000);
        driver = world.driver("PF");
    }

    // ------------------------------------------------------- order lines

    @When("a planning-flow order with lines of {int} kg and {int} kg is raised and validated")
    public void order(int first, int second) {
        lineWeights = new int[] {first, second};
        OrdersApi orders = world.api(OrdersApi.class);
        orderId = orders.raiseOrder(new RaiseOrderRequest()
                .orgUnitId(world.orgUnitId())
                .customerPartnerId(customer)
                .orderNo(world.code("SO-PF"))
                .originTerminalId(origin)
                .requestedPickupAt(OffsetDateTime.now(ZoneOffset.UTC).plusHours(4))
                .source(RaiseOrderRequest.SourceEnum.API), null).getId();
        String[][] materials = {{"STEEL-COIL", "Cold-rolled steel coil"}, {"PIPES", "Seamless pipes"}};
        for (int i = 0; i < 2; i++) {
            orders.addOrderLine(orderId, new AddOrderLineRequest()
                    .lineNo(i + 1)
                    .materialCode(materials[i][0])
                    .materialDescription(materials[i][1])
                    .materialClass("GENERAL")
                    .quantity(new BigDecimal("10"))
                    .uom("UNIT")
                    .deadWeightKg(BigDecimal.valueOf(lineWeights[i]))
                    .consigneePartnerId(consignee)
                    .destinationTerminalId(destination), null);
        }
        orders.validateOrder(orderId, null);
    }

    @Then("the planning-flow order lists both lines in line order with their weights and destination")
    public void linesListed() {
        List<OrderLine> lines = world.api(OrdersApi.class).listOrderLines(orderId);
        assertThat(lines).hasSize(2);
        assertThat(lines).extracting(OrderLine::getLineNo).containsExactly(1, 2);
        assertThat(lines).extracting(OrderLine::getMaterialCode)
                .containsExactly("STEEL-COIL", "PIPES");
        assertThat(lines.get(0).getMaterialDescription()).isEqualTo("Cold-rolled steel coil");
        assertThat(lines.get(0).getDeadWeightKg()).isEqualByComparingTo(BigDecimal.valueOf(lineWeights[0]));
        assertThat(lines.get(1).getDeadWeightKg()).isEqualByComparingTo(BigDecimal.valueOf(lineWeights[1]));
        assertThat(lines).allSatisfy(line -> {
            assertThat(line.getId()).isNotNull();
            assertThat(line.getQuantity()).isEqualByComparingTo("10");
            assertThat(line.getUom()).isEqualTo("UNIT");
            assertThat(line.getMaterialClass()).isEqualTo("GENERAL");
            assertThat(line.getConsigneePartnerId()).isEqualTo(consignee);
            assertThat(line.getDestinationTerminalCode()).isEqualTo(world.code("PF-DEST"));
        });
    }

    @Then("the planning-flow order lines are empty for an unknown order and for another tenant, and refused to anonymous callers")
    public void linesNotVisible() {
        OrdersApi mine = world.api(OrdersApi.class);
        assertThat(mine.listOrderLines(UUID.randomUUID())).isEmpty();

        String stranger = support.otherTenantAdministratorToken();
        assertThat(world.client(stranger, OrdersApi.class).listOrderLines(orderId))
                .as("another tenant's token must see none of this order's lines").isEmpty();
        assertThat(mine.listOrderLines(orderId)).hasSize(2);

        assertStatus(() -> world.client(null, OrdersApi.class).listOrderLines(orderId), 401);
    }

    // --------------------------------------- consignments, tracking, loads

    @When("the planning-flow order is planned into a consignment")
    public void consignment() {
        List<IdResponse> generated = world.api(PlanningApi.class).generateConsignments(orderId, null);
        assertThat(generated).as("one consignee, one consignment").hasSize(1);
        consignmentId = generated.get(0).getId();
        trackingRef = world.api(PlanningApi.class).getConsignment(consignmentId).getTrackingRef();
        assertThat(trackingRef).isNotBlank();
    }

    @Then("tracking the planning-flow reference shows status {word} for the one consignee and no load")
    public void trackedUnloaded(String status) {
        Consignment tracked = world.api(PlanningApi.class).trackConsignment(trackingRef);
        assertThat(tracked.getId()).isEqualTo(consignmentId);
        assertThat(tracked.getTrackingRef()).isEqualTo(trackingRef);
        assertThat(tracked.getStatus()).isEqualTo(status);
        assertThat(tracked.getLoadNo()).isNull();
        assertThat(tracked.getConsigneeName()).startsWith("PFN");
        assertThat(tracked.getDestinationTerminalCode()).isEqualTo(world.code("PF-DEST"));
        assertThat(tracked.getTotalDeadWeightKg())
                .isEqualByComparingTo(BigDecimal.valueOf(lineWeights[0] + lineWeights[1]));
        assertThat(tracked.getContainsHazmat()).isFalse();
        assertThat(tracked.getFreightMode()).isEqualTo("FTL");
    }

    @When("the planning-flow consignment is put on a planned load")
    public void loaded() {
        PlanningApi planning = world.api(PlanningApi.class);
        loadId = planning.openLoad(new OpenLoadRequest()
                .orgUnitId(world.orgUnitId())
                .loadNo(world.code("LD-PF"))
                .originTerminalId(origin)
                .vehicleId(lorry), null).getId();
        planning.assignConsignment(loadId, new AssignConsignmentRequest().consignmentId(consignmentId), null);
        planning.planLoad(loadId, null);
    }

    @Then("tracking the planning-flow reference shows status {word} on the planning-flow load")
    public void trackedOnLoad(String status) {
        Consignment tracked = world.api(PlanningApi.class).trackConsignment(trackingRef);
        assertThat(tracked.getStatus()).isEqualTo(status);
        assertThat(tracked.getLoadNo()).isEqualTo(world.code("LD-PF"));
    }

    @Then("the planning-flow load lists exactly its consignment")
    public void loadContents() {
        List<Consignment> items = world.api(PlanningApi.class).listLoadConsignments(loadId);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getId()).isEqualTo(consignmentId);
        assertThat(items.get(0).getTrackingRef()).isEqualTo(trackingRef);
        assertThat(items.get(0).getLoadNo()).isEqualTo(world.code("LD-PF"));
        assertThat(items.get(0).getStatus()).isEqualTo("ASSIGNED_TO_LOAD");
    }

    @Then("an unknown planning-flow load, and another tenant's view of this one, list no consignments")
    public void loadContentsHidden() {
        String mine = support.demoAdministratorToken();
        PlanningFlowSupport.Raw unknown = support.get("/api/v1/loads/" + UUID.randomUUID()
                + "/consignments", mine);
        assertThat(unknown.status()).isEqualTo(200);
        assertThat(unknown.json().size()).isZero();

        PlanningFlowSupport.Raw foreign = support.get("/api/v1/loads/" + loadId + "/consignments",
                support.otherTenantAdministratorToken());
        assertThat(foreign.status()).isEqualTo(200);
        assertThat(foreign.json().size()).isZero();

        assertThat(support.get("/api/v1/loads/" + loadId + "/consignments", null).status())
                .isEqualTo(401);
    }

    @Then("tracking an unknown planning-flow reference is a 404 problem and so is another tenant's lookup")
    public void trackingRefused() {
        assertProblem(() -> world.api(PlanningApi.class).trackConsignment("NO-SUCH-" + world.salt),
                404, "not-found");

        String stranger = support.otherTenantAdministratorToken();
        assertProblem(() -> world.client(stranger, PlanningApi.class).trackConsignment(trackingRef),
                404, "not-found");
        assertStatus(() -> world.client(null, PlanningApi.class).trackConsignment(trackingRef), 401);
        assertThat(world.api(PlanningApi.class).trackConsignment(trackingRef).getId())
                .as("the owner still finds it").isEqualTo(consignmentId);
    }

    // ---------------------------------------------------------- trips

    @Given("a planning-flow trip is assigned for {int} kg, the load awarded to {string}")
    public void assignedTrip(int weightKg, String vendor) {
        tripWeightKg = weightKg;
        lineWeights = new int[] {weightKg - 1000, 1000};
        order(lineWeights[0], lineWeights[1]);
        consignment();
        loaded();
        rankGuide(vendor, null);
        offerLoad();
        vendorAccepts(vendor);
        TripsApi trips = world.api(TripsApi.class);
        tripId = trips.raiseTrip(new RaiseTripRequest().tripNo(world.code("TR-PF"))
                .loadId(loadId).plannedStartAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(60)),
                null).getId();
        trips.assignTrip(tripId, new AssignTripRequest().vehicleId(lorry).driverId(driver), null);
    }

    @When("the planning-flow lorry is gated in with the remark {string} and dispatched")
    public void dispatched(String remark) {
        TripsApi trips = world.api(TripsApi.class);
        dispatchedAt = OffsetDateTime.now(ZoneOffset.UTC).minusHours(3);
        gateInAt = dispatchedAt.minusMinutes(60);
        trips.gateIn(tripId, new GateInRequest().terminalId(origin).at(gateInAt).remarks(remark), null);
        trips.recordWeighing(tripId, new WeighingRequest().terminalId(origin)
                .type(WeighingRequest.TypeEnum.TARE).weightKg(HttpWorld.TARE_KG).at(gateInAt), null);
        trips.recordWeighing(tripId, new WeighingRequest().terminalId(origin)
                .type(WeighingRequest.TypeEnum.GROSS)
                .weightKg(HttpWorld.TARE_KG.add(BigDecimal.valueOf(tripWeightKg))).at(gateInAt), null);
        trips.markLoaded(tripId, new MilestoneRequest().at(dispatchedAt.minusMinutes(1)), null);
        trips.attachTripDocument(tripId, new AttachTripDocumentRequest()
                .documentType("CONSIGNMENT_NOTE").documentRef("LR-" + tripId), null);
        trips.attachTripDocument(tripId, new AttachTripDocumentRequest()
                .documentType("EWAY_BILL").documentRef("EW-" + tripId), null);
        trips.dispatchTrip(tripId, new MilestoneRequest().at(dispatchedAt), null);
    }

    @When("the planning-flow trip arrives and is completed")
    public void delivered() {
        deliveredAt = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5);
        world.deliver(tripId, deliveredAt);
    }

    @Then("the planning-flow gate log is empty")
    public void gateLogEmpty() {
        assertThat(world.api(TripsApi.class).getGateLog(tripId)).isEmpty();
    }

    @Then("the planning-flow gate log lists gate-in then gate-out at the origin in time order, with the remark {string}")
    public void gateLogAfterDispatch(String remark) {
        List<GateEvent> log = world.api(TripsApi.class).getGateLog(tripId);
        assertThat(log).hasSize(2);
        assertThat(log).extracting(GateEvent::getEventType)
                .containsExactly(GateEvent.EventTypeEnum.GATE_IN, GateEvent.EventTypeEnum.GATE_OUT);
        assertThat(log).extracting(GateEvent::getTerminalCode)
                .containsExactly(world.code("PF-ORIGIN"), world.code("PF-ORIGIN"));
        assertThat(log.get(0).getRemarks()).isEqualTo(remark);
        assertThat(log.get(1).getRemarks()).isNull();
        assertSameMoment(log.get(0).getOccurredAt(), gateInAt);
        assertSameMoment(log.get(1).getOccurredAt(), dispatchedAt);
        assertThat(log).allSatisfy(event -> assertThat(event.getId()).isNotNull());
    }

    @Then("the planning-flow gate log now ends with a gate-in at the destination")
    public void gateLogAfterArrival() {
        List<GateEvent> log = world.api(TripsApi.class).getGateLog(tripId);
        assertThat(log).extracting(GateEvent::getEventType).containsExactly(
                GateEvent.EventTypeEnum.GATE_IN, GateEvent.EventTypeEnum.GATE_OUT,
                GateEvent.EventTypeEnum.GATE_IN);
        assertThat(log.get(2).getTerminalCode()).isEqualTo(world.code("PF-DEST"));
        assertSameMoment(log.get(2).getOccurredAt(), deliveredAt.minusMinutes(10));
        assertThat(log).extracting(GateEvent::getOccurredAt)
                .as("events come back oldest first").isSorted();
    }

    @Then("the planning-flow gate log is empty for an unknown trip and for another tenant, and refused to anonymous callers")
    public void gateLogHidden() {
        assertThat(world.api(TripsApi.class).getGateLog(UUID.randomUUID())).isEmpty();
        String stranger = support.otherTenantAdministratorToken();
        assertThat(world.client(stranger, TripsApi.class).getGateLog(tripId))
                .as("another tenant's token must not see this trip's gate events").isEmpty();
        assertThat(world.api(TripsApi.class).getGateLog(tripId)).isNotEmpty();
        assertStatus(() -> world.client(null, TripsApi.class).getGateLog(tripId), 401);
    }

    @Then("the planning-flow load is {word}")
    public void loadStatus(String status) {
        assertThat(world.api(PlanningApi.class).getLoad(loadId).getStatus()).isEqualTo(status);
    }

    // ------------------------------------------------------ routing guide

    @Given("the planning-flow load is planned and {string} is ranked first and {string} second")
    public void plannedAndRanked(String first, String second) {
        order(4000, 1000);
        consignment();
        loaded();
        rankGuide(first, second);
    }

    @Given("the planning-flow load is planned and {string} is ranked first on its lane")
    public void plannedAndRankedOne(String first) {
        order(4000, 1000);
        consignment();
        loaded();
        rankGuide(first, null);
    }

    private void rankGuide(String first, String second) {
        SourcingApi sourcing = world.api(SourcingApi.class);
        guideId = sourcing.defineRoutingGuide(new DefineGuideRequest()
                .orgUnitId(world.orgUnitId())
                .originTerminalId(origin)
                .destinationTerminalId(destination)
                .vehicleType(HttpWorld.VEHICLE_TYPE), null).getId();
        sourcing.addRoutingGuideVendor(guideId, new AddVendorRequest()
                .vendorPartnerId(vendors.get(first)).rankNo(1).responseSlaMinutes(60)
                .agreedRate(new BigDecimal("20000")), null);
        if (second != null) {
            sourcing.addRoutingGuideVendor(guideId, new AddVendorRequest()
                    .vendorPartnerId(vendors.get(second)).rankNo(2).responseSlaMinutes(90)
                    .agreedRate(new BigDecimal("22000")), null);
        }
    }

    @When("the planning-flow load is offered to its vendors")
    public void offerLoad() {
        allocationId = world.api(SourcingApi.class).allocateLoad(new AllocateRequest().loadId(loadId), null)
                .getId();
    }

    @When("vendor {string} rejects the planning-flow offer")
    public void vendorRejects(String vendor) {
        world.api(SourcingApi.class).rejectOffer(allocationId,
                new VendorResponseRequest().vendorPartnerId(vendors.get(vendor)), null);
    }

    @When("vendor {string} accepts the planning-flow offer")
    public void vendorAccepts(String vendor) {
        world.api(SourcingApi.class).acceptOffer(allocationId,
                new VendorResponseRequest().vendorPartnerId(vendors.get(vendor)), null);
    }

    @When("the planning-flow allocation is cancelled")
    public void cancelAllocation() {
        world.api(SourcingApi.class).cancelAllocation(allocationId, null);
    }

    @Then("the planning-flow allocation is {word} at rank {int} after {int} offers")
    public void allocationState(String status, int rank, int offers) {
        Allocation allocation = world.api(SourcingApi.class).getAllocation(allocationId);
        assertThat(allocation.getStatus()).isEqualTo(status);
        assertThat(allocation.getCurrentRank()).isEqualTo(rank);
        assertThat(allocation.getOffersMade()).isEqualTo(offers);
        assertThat(allocation.getLoadId()).isEqualTo(loadId);
        assertThat(allocation.getLoadNo()).isEqualTo(world.code("LD-PF"));
        assertThat(allocation.getStrategy()).isEqualTo(Allocation.StrategyEnum.CONTRACTUAL);
    }

    @Then("the planning-flow offer trail reads {string}")
    public void offerTrail(String expected) {
        List<Offer> offers = world.api(SourcingApi.class).listOffers(allocationId);
        String[] wanted = expected.split(",");
        assertThat(offers).hasSize(wanted.length);
        for (int i = 0; i < wanted.length; i++) {
            String[] parts = wanted[i].split("=");
            Offer offer = offers.get(i);
            assertThat(offer.getRankNo()).isEqualTo(i + 1);
            assertThat(offer.getVendorPartnerId()).isEqualTo(vendors.get(parts[0]));
            assertThat(offer.getVendorName()).startsWith("PF" + parts[0]);
            assertThat(offer.getOfferedAt()).isNotNull();
            assertThat(offer.getOutcome().getValue()).isEqualTo(parts[1]);
            long slaMinutes = i == 0 ? 60 : 90;
            assertThat(Duration.between(offer.getOfferedAt(), offer.getRespondsBy()))
                    .isEqualTo(Duration.ofMinutes(slaMinutes));
            if ("PENDING".equals(parts[1])) {
                assertThat(offer.getRespondedAt()).isNull();
            } else {
                assertThat(offer.getRespondedAt()).isNotNull();
            }
        }
    }

    @Then("vendor {string} cannot answer the planning-flow offer, it is another vendor's now")
    public void notYours(String vendor) {
        UUID id = vendors.get(vendor);
        assertProblem(() -> world.api(SourcingApi.class).rejectOffer(allocationId,
                new VendorResponseRequest().vendorPartnerId(id), null), 409, "offer-not-yours");
        assertProblem(() -> world.api(SourcingApi.class).acceptOffer(allocationId,
                new VendorResponseRequest().vendorPartnerId(id), null), 409, "offer-not-yours");
    }

    @Then("a further planning-flow rejection by {string} is refused with {string}")
    public void furtherRejection(String vendor, String code) {
        assertProblem(() -> world.api(SourcingApi.class).rejectOffer(allocationId,
                new VendorResponseRequest().vendorPartnerId(vendors.get(vendor)), null), 409, code);
    }

    @Then("a further planning-flow acceptance by {string} is refused with {string}")
    public void furtherAcceptance(String vendor, String code) {
        assertProblem(() -> world.api(SourcingApi.class).acceptOffer(allocationId,
                new VendorResponseRequest().vendorPartnerId(vendors.get(vendor)), null), 409, code);
    }

    @Then("cancelling the planning-flow allocation is refused with {string}")
    public void cancelRefused(String code) {
        assertProblem(() -> world.api(SourcingApi.class).cancelAllocation(allocationId, null), 409, code);
    }

    @Then("cancelling the planning-flow allocation again changes nothing")
    public void cancelAgain() {
        world.api(SourcingApi.class).cancelAllocation(allocationId, null);
        assertThat(world.api(SourcingApi.class).getAllocation(allocationId).getStatus())
                .isEqualTo("CANCELLED");
    }

    @Then("the planning-flow allocation names {string} as its awarded vendor")
    public void awardedVendor(String vendor) {
        assertThat(world.api(SourcingApi.class).getAllocation(allocationId).getAwardedVendorName())
                .startsWith("PF" + vendor);
    }

    @Then("the cancelled planning-flow allocation is listed among cancelled allocations")
    public void listedCancelled() {
        var page = world.api(SourcingApi.class).listAllocations("CANCELLED", null, 200);
        assertThat(page.getItems()).extracting(Allocation::getId).contains(allocationId);
        assertThat(world.api(SourcingApi.class).listAllocations("OFFERED", null, 200).getItems())
                .extracting(Allocation::getId).doesNotContain(allocationId);
    }

    // ----------------------------------------------------- retire a guide

    @Then("the planning-flow routing guide is active with {string} at rank 1")
    public void guideActive(String vendor) {
        var guide = world.api(SourcingApi.class).getRoutingGuide(guideId);
        assertThat(guide.getActive()).isTrue();
        assertThat(guide.getEntries()).singleElement().satisfies(entry -> {
            assertThat(entry.getRankNo()).isEqualTo(1);
            assertThat(entry.getVendorPartnerId()).isEqualTo(vendors.get(vendor));
        });
    }

    @When("the planning-flow routing guide is retired")
    public void retire() {
        world.api(SourcingApi.class).retireRoutingGuide(guideId, null);
    }

    @Then("the planning-flow routing guide is retired but still shows its vendors")
    public void guideRetired() {
        var guide = world.api(SourcingApi.class).getRoutingGuide(guideId);
        assertThat(guide.getActive()).isFalse();
        assertThat(guide.getEntries()).hasSize(1);
        assertThat(guide.getOriginTerminalCode()).isEqualTo(world.code("PF-ORIGIN"));
        assertThat(guide.getDestinationTerminalCode()).isEqualTo(world.code("PF-DEST"));
    }

    @Then("retiring the planning-flow routing guide again changes nothing")
    public void retireAgain() {
        world.api(SourcingApi.class).retireRoutingGuide(guideId, null);
        assertThat(world.api(SourcingApi.class).getRoutingGuide(guideId).getActive()).isFalse();
    }

    @Then("a new planning-flow load on that lane can no longer be offered, refused with {string}")
    public void retiredNotUsed(String code) {
        UUID second = secondPlannedLoadOnTheLane();
        assertProblem(() -> world.api(SourcingApi.class).allocateLoad(new AllocateRequest().loadId(second),
                null), 409, code);
    }

    @Then("the retired planning-flow guide takes no new vendor, refused with {string}, and cannot be redefined, refused with {string}")
    public void retiredGuideLocked(String retiredCode, String existsCode) {
        UUID extra = vendors.values().iterator().next();
        UUID another = world.activeVendor("PFX");
        assertProblem(() -> world.api(SourcingApi.class).addRoutingGuideVendor(guideId,
                new AddVendorRequest().vendorPartnerId(another).rankNo(2).responseSlaMinutes(30), null),
                409, retiredCode);
        assertThat(extra).isNotNull();
        assertProblem(() -> world.api(SourcingApi.class).defineRoutingGuide(new DefineGuideRequest()
                .orgUnitId(world.orgUnitId()).originTerminalId(origin).destinationTerminalId(destination)
                .vehicleType(HttpWorld.VEHICLE_TYPE), null), 409, existsCode);
    }

    @Then("the earlier planning-flow allocation, made under the guide before it was retired, is still readable")
    public void earlierAllocation() {
        Allocation allocation = world.api(SourcingApi.class).getAllocation(allocationId);
        assertThat(allocation.getStatus()).isEqualTo("OFFERED");
        assertThat(allocation.getCurrentRank()).isEqualTo(1);
    }

    private UUID secondPlannedLoadOnTheLane() {
        OrdersApi orders = world.api(OrdersApi.class);
        UUID second = orders.raiseOrder(new RaiseOrderRequest()
                .orgUnitId(world.orgUnitId()).customerPartnerId(customer)
                .orderNo(world.code("SO-PF2")).originTerminalId(origin)
                .requestedPickupAt(OffsetDateTime.now(ZoneOffset.UTC).plusHours(4))
                .source(RaiseOrderRequest.SourceEnum.API), null).getId();
        orders.addOrderLine(second, new AddOrderLineRequest().lineNo(1).materialCode("GOODS")
                .materialDescription("General goods").materialClass("GENERAL")
                .quantity(new BigDecimal("1")).uom("CASE").deadWeightKg(new BigDecimal("500"))
                .consigneePartnerId(consignee).destinationTerminalId(destination), null);
        orders.validateOrder(second, null);
        PlanningApi planning = world.api(PlanningApi.class);
        UUID consignmentTwo = planning.generateConsignments(second, null).get(0).getId();
        UUID lorryTwo = world.lorry("PF2", 20000);
        UUID loadTwo = planning.openLoad(new OpenLoadRequest().orgUnitId(world.orgUnitId())
                .loadNo(world.code("LD-PF2")).originTerminalId(origin).vehicleId(lorryTwo), null).getId();
        planning.assignConsignment(loadTwo, new AssignConsignmentRequest().consignmentId(consignmentTwo),
                null);
        planning.planLoad(loadTwo, null);
        assertThat(world.api(PlanningApi.class).getLoad(loadTwo).getStatus()).isEqualTo("PLANNED");
        return loadTwo;
    }

    // --------------------------------------------- sourcing refusals

    @Then("without the permission, rejecting, cancelling and retiring in sourcing are all forbidden")
    public void sourcingForbidden() {
        String token = support.limitedUserToken("nopermission", List.of());
        SourcingApi nobody = world.client(token, SourcingApi.class);
        UUID vendorId = vendors.values().iterator().next();
        assertProblem(() -> nobody.rejectOffer(allocationId,
                new VendorResponseRequest().vendorPartnerId(vendorId), null), 403, "access-denied");
        assertProblem(() -> nobody.cancelAllocation(allocationId, null), 403, "access-denied");
        assertProblem(() -> nobody.retireRoutingGuide(guideId, null), 403, "access-denied");

        // Holding the one permission opens that door and no other.
        String allocator = support.limitedUserToken("allocator", List.of("LOAD_ALLOCATE"));
        assertProblem(() -> world.client(allocator, SourcingApi.class).retireRoutingGuide(guideId, null),
                403, "access-denied");
        assertThat(world.api(SourcingApi.class).getRoutingGuide(guideId).getActive())
                .as("the refused retirement changed nothing").isTrue();
        assertThat(world.api(SourcingApi.class).getAllocation(allocationId).getStatus())
                .isEqualTo("OFFERED");
    }

    @Then("another tenant gets 404 problems for the planning-flow allocation and guide, and no offers")
    public void sourcingForeign() {
        String stranger = support.otherTenantAdministratorToken();
        SourcingApi foreign = world.client(stranger, SourcingApi.class);
        UUID vendorId = vendors.values().iterator().next();
        assertProblem(() -> foreign.rejectOffer(allocationId,
                new VendorResponseRequest().vendorPartnerId(vendorId), null), 404, "not-found");
        assertProblem(() -> foreign.cancelAllocation(allocationId, null), 404, "not-found");
        assertProblem(() -> foreign.retireRoutingGuide(guideId, null), 404, "not-found");
        assertProblem(() -> foreign.getAllocation(allocationId), 404, "not-found");
        assertThat(foreign.listOffers(allocationId)).isEmpty();
        assertThat(world.api(SourcingApi.class).listOffers(allocationId)).isNotEmpty();
        assertThat(world.api(SourcingApi.class).getAllocation(allocationId).getStatus())
                .isEqualTo("OFFERED");
    }

    @Then("unknown planning-flow allocation and guide ids are 404 problems, and an unknown allocation has no offers")
    public void sourcingUnknown() {
        SourcingApi sourcing = world.api(SourcingApi.class);
        UUID unknown = UUID.randomUUID();
        UUID vendorId = vendors.values().iterator().next();
        assertProblem(() -> sourcing.rejectOffer(unknown,
                new VendorResponseRequest().vendorPartnerId(vendorId), null), 404, "not-found");
        assertProblem(() -> sourcing.cancelAllocation(unknown, null), 404, "not-found");
        assertProblem(() -> sourcing.retireRoutingGuide(unknown, null), 404, "not-found");
        assertThat(sourcing.listOffers(unknown)).isEmpty();
    }

    @Then("a planning-flow rejection naming no vendor is a 400 problem with code validation-failed")
    public void rejectionNamesNoVendor() {
        assertProblem(() -> world.api(SourcingApi.class).rejectOffer(allocationId,
                new VendorResponseRequest(), null), 400, "validation-failed");
        assertThat(world.api(SourcingApi.class).getAllocation(allocationId).getStatus())
                .as("the malformed rejection changed nothing").isEqualTo("OFFERED");
    }

    // ------------------------------------------------- part-load hubs

    @Given("planning-flow hubs, a delivery site, a customer, a consignee and vendor {string} are registered")
    public void hubParties(String vendor) {
        customer = world.partner("PFLC", RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER);
        consignee = world.partner("PFLN", RegisterPartnerRequest.PartnerTypeEnum.CONSIGNEE);
        vendors.put(vendor, world.activeVendor("PF" + vendor));
        originHub = world.terminal(RegisterPointRadiusTerminalRequest.CategoryEnum.HUB,
                "PF-HUB-O", 51.00, 12.00);
        destinationHub = world.terminal(RegisterPointRadiusTerminalRequest.CategoryEnum.HUB,
                "PF-HUB-D", 52.50, 13.00);
        site = world.terminal(RegisterPointRadiusTerminalRequest.CategoryEnum.CUSTOMER_SITE,
                "PF-SITE", 52.60, 13.10);
        lorry = world.lorry("PFL", 20000);
        driver = world.driver("PFL");
    }

    @When("a planning-flow docket of {int} kg is booked and its line-haul load awarded to {string}")
    public void docketOnAwardedLineHaul(int weightKg, String vendor) {
        tripWeightKg = weightKg;
        String originZone = "PFW-" + world.salt;
        String destinationZone = "PFN-" + world.salt;
        LtlRatingApi ltl = world.api(LtlRatingApi.class);
        ltl.assignLtlZone(originHub, new AssignLtlZoneRequest().zoneCode(originZone)
                .outOfDeliveryArea(false));
        ltl.assignLtlZone(destinationHub, new AssignLtlZoneRequest().zoneCode(destinationZone)
                .outOfDeliveryArea(false));
        ltl.publishLtlRateCard(new PublishLtlRateCardRequest()
                .code(world.code("PFL-RC")).originZone(originZone).destinationZone(destinationZone)
                .effectiveFrom(LocalDate.now(ZoneOffset.UTC).minusDays(30))
                .rateBasis(PublishLtlRateCardRequest.RateBasisEnum.PER_KG)
                .rateAmount(new BigDecimal("12.00"))
                .minimumChargeableWeightKg(new BigDecimal("50")).kgPerCft(new BigDecimal("6"))
                .docketCharge(new BigDecimal("100")).odaSurcharge(BigDecimal.ZERO), null);

        OrdersApi orders = world.api(OrdersApi.class);
        orderId = orders.raiseOrder(new RaiseOrderRequest()
                .orgUnitId(world.orgUnitId()).customerPartnerId(customer)
                .orderNo(world.code("PFL-SO")).originTerminalId(originHub)
                .requestedPickupAt(OffsetDateTime.now(ZoneOffset.UTC).plusHours(4))
                .source(RaiseOrderRequest.SourceEnum.API).freightMode(FreightMode.LTL)
                .originHubTerminalId(originHub).destinationHubTerminalId(destinationHub), null).getId();
        orders.addOrderLine(orderId, new AddOrderLineRequest().lineNo(1).materialCode("PARCELS")
                .materialDescription("Boxed parcels").materialClass("GENERAL")
                .quantity(new BigDecimal("4")).uom("BOX").deadWeightKg(BigDecimal.valueOf(weightKg))
                .consigneePartnerId(consignee).destinationTerminalId(site), null);
        orders.validateOrder(orderId, null);
        PlanningApi planning = world.api(PlanningApi.class);
        docketId = planning.generateConsignments(orderId, null).get(0).getId();
        docketRef = planning.getConsignment(docketId).getTrackingRef();

        loadId = planning.openLoad(new OpenLoadRequest().orgUnitId(world.orgUnitId())
                .loadNo(world.code("PFL-LD")).originTerminalId(originHub).vehicleId(lorry)
                .freightMode(FreightMode.LTL).ltlLeg(LtlLeg.LINE_HAUL), null).getId();
        planning.assignConsignment(loadId, new AssignConsignmentRequest().consignmentId(docketId), null);
        planning.planLoad(loadId, null);

        SourcingApi sourcing = world.api(SourcingApi.class);
        guideId = sourcing.defineRoutingGuide(new DefineGuideRequest().orgUnitId(world.orgUnitId())
                .originTerminalId(originHub).destinationTerminalId(destinationHub)
                .vehicleType(HttpWorld.VEHICLE_TYPE), null).getId();
        sourcing.addRoutingGuideVendor(guideId, new AddVendorRequest()
                .vendorPartnerId(vendors.get(vendor)).rankNo(1).responseSlaMinutes(60)
                .agreedRate(new BigDecimal("20000")), null);
        allocationId = sourcing.allocateLoad(new AllocateRequest().loadId(loadId), null).getId();
        sourcing.acceptOffer(allocationId,
                new VendorResponseRequest().vendorPartnerId(vendors.get(vendor)), null);

        TripsApi trips = world.api(TripsApi.class);
        tripId = trips.raiseTrip(new RaiseTripRequest().tripNo(world.code("PFL-TR")).loadId(loadId)
                .plannedStartAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(60)), null).getId();
        trips.assignTrip(tripId, new AssignTripRequest().vehicleId(lorry).driverId(driver), null);
    }

    @When("the planning-flow line haul leaves the origin hub and reaches the destination hub")
    public void lineHaulArrives() {
        world.dispatch(tripId, originHub, tripWeightKg, OffsetDateTime.now(ZoneOffset.UTC).minusHours(3));
        assertThat(world.api(PlanningApi.class).getConsignment(docketId).getStatus())
                .isEqualTo("IN_TRANSIT");
        // Dispatched but not yet arrived: nothing waits at the hub.
        assertThat(world.api(PlanningApi.class).listDocketsAwaitingDelivery(destinationHub, null, 50)
                .getItems()).extracting(Consignment::getId).doesNotContain(docketId);
        world.deliver(tripId, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5));
    }

    @Then("the planning-flow destination hub lists the docket as arrived and waiting for delivery")
    public void hubListsDocket() {
        var page = world.api(PlanningApi.class).listDocketsAwaitingDelivery(destinationHub, null, 50);
        assertThat(page.getHasMore()).isFalse();
        assertThat(page.getItems()).filteredOn(c -> docketId.equals(c.getId())).singleElement()
                .satisfies(docket -> {
                    assertThat(docket.getStatus()).isEqualTo("ARRIVED_AT_HUB");
                    assertThat(docket.getTrackingRef()).isEqualTo(docketRef);
                    assertThat(docket.getFreightMode()).isEqualTo("LTL");
                    assertThat(docket.getOriginHubCode()).isEqualTo(world.code("PF-HUB-O"));
                    assertThat(docket.getDestinationHubCode()).isEqualTo(world.code("PF-HUB-D"));
                    assertThat(docket.getConsigneeName()).startsWith("PFLN");
                    assertThat(docket.getTotalDeadWeightKg())
                            .isEqualByComparingTo(BigDecimal.valueOf(tripWeightKg));
                });
        // The origin hub is not where it is waiting.
        assertThat(world.api(PlanningApi.class).listDocketsAwaitingDelivery(originHub, null, 50)
                .getItems()).extracting(Consignment::getId).doesNotContain(docketId);
        // A limit of one is honoured and the page says whether there is more.
        var one = world.api(PlanningApi.class).listDocketsAwaitingDelivery(destinationHub, null, 1);
        assertThat(one.getItems()).hasSizeLessThanOrEqualTo(1);
    }

    @Then("the planning-flow hub queue is forbidden without load-read permission and empty for another tenant")
    public void hubQueueGuarded() {
        String nobody = support.limitedUserToken("hubnobody", List.of());
        assertProblem(() -> world.client(nobody, PlanningApi.class)
                .listDocketsAwaitingDelivery(destinationHub, null, 50), 403, "access-denied");
        String reader = support.limitedUserToken("hubreader", List.of("LOAD_READ"));
        assertThat(world.client(reader, PlanningApi.class)
                .listDocketsAwaitingDelivery(destinationHub, null, 50).getItems())
                .extracting(Consignment::getId).contains(docketId);

        String stranger = support.otherTenantAdministratorToken();
        assertThat(world.client(stranger, PlanningApi.class)
                .listDocketsAwaitingDelivery(destinationHub, null, 50).getItems()).isEmpty();
        assertThat(world.api(PlanningApi.class).listDocketsAwaitingDelivery(UUID.randomUUID(), null, 50)
                .getItems()).isEmpty();
        assertStatus(() -> world.client(null, PlanningApi.class)
                .listDocketsAwaitingDelivery(destinationHub, null, 50), 401);
    }

    @When("the planning-flow docket is put on a delivery load opened at the destination hub")
    public void deliveryLoad() {
        PlanningApi planning = world.api(PlanningApi.class);
        UUID deliveryLorry = world.lorry("PFD", 20000);
        UUID delivery = planning.openLoad(new OpenLoadRequest().orgUnitId(world.orgUnitId())
                .loadNo(world.code("PFD-LD")).originTerminalId(destinationHub).vehicleId(deliveryLorry)
                .freightMode(FreightMode.LTL).ltlLeg(LtlLeg.DELIVERY), null).getId();
        planning.assignConsignment(delivery, new AssignConsignmentRequest().consignmentId(docketId), null);
    }

    @Then("the planning-flow destination hub no longer lists the docket and it is assigned to delivery")
    public void hubEmptied() {
        assertThat(world.api(PlanningApi.class).listDocketsAwaitingDelivery(destinationHub, null, 50)
                .getItems()).extracting(Consignment::getId).doesNotContain(docketId);
        assertThat(world.api(PlanningApi.class).getConsignment(docketId).getStatus())
                .isEqualTo("ASSIGNED_TO_DELIVERY");
    }

    // ------------------------------------------------- control tower

    @When("the planning-flow exception summary is read as the baseline")
    public void baseline() {
        baseline = world.api(ControlTowerApi.class).summariseExceptions();
    }

    @When("a planning-flow THEFT and an ETA_DELAY are raised on the trip")
    public void raiseTwo() {
        ControlTowerApi tower = world.api(ControlTowerApi.class);
        theftId = tower.raiseException(new RaiseExceptionRequest().tripId(tripId)
                .category(ExceptionCategory.THEFT).description("Seal broken at the toll plaza"), null)
                .getId();
        support.remember(theftId);
        delayId = tower.raiseException(new RaiseExceptionRequest().tripId(tripId)
                .category(ExceptionCategory.ETA_DELAY).description("Running two hours late"), null)
                .getId();
        support.remember(delayId);
    }

    @Then("the planning-flow summary shows {int} more open P1, {int} more open P3, {int} more unassigned and {int} more settlement hold")
    public void summaryDelta(int p1, int p3, int unassigned, int holds) {
        ExceptionSummary now = world.api(ControlTowerApi.class).summariseExceptions();
        assertThat(now.getOpenP1() - baseline.getOpenP1()).as("open P1").isEqualTo(p1);
        assertThat(now.getOpenP2() - baseline.getOpenP2()).as("open P2").isZero();
        assertThat(now.getOpenP3() - baseline.getOpenP3()).as("open P3").isEqualTo(p3);
        assertThat(now.getUnassigned() - baseline.getUnassigned()).as("unassigned").isEqualTo(unassigned);
        assertThat(now.getSettlementHolds() - baseline.getSettlementHolds()).as("holds").isEqualTo(holds);
        assertThat(now.getOpenWithoutTrip() - baseline.getOpenWithoutTrip()).as("without a trip").isZero();
    }

    @When("the planning-flow THEFT is assigned, investigated and resolved by the administrator")
    public void workTheft() {
        ControlTowerApi tower = world.api(ControlTowerApi.class);
        tower.assignException(theftId, new AssignExceptionRequest().assigneeUserId(world.userId()), null);
        tower.investigateException(theftId, null, new ExceptionNoteRequest());
        tower.resolveException(theftId, new ResolveExceptionRequest()
                .resolution("Seal replaced, load recounted at the plaza"), null);
        assertThat(tower.getException(theftId).getStatus()).isEqualTo(Exception.StatusEnum.RESOLVED);
    }

    @When("the planning-flow resolution is sent back with the reason {string}")
    public void sendBack(String reason) {
        world.api(ControlTowerApi.class).reopenException(theftId,
                new ReopenExceptionRequest().reason(reason), null);
    }

    @Then("the planning-flow THEFT is under investigation again, with a REOPENED entry carrying {string}")
    public void reopenedTheft(String reason) {
        ControlTowerApi tower = world.api(ControlTowerApi.class);
        Exception ticket = tower.getException(theftId);
        assertThat(ticket.getStatus()).isEqualTo(Exception.StatusEnum.UNDER_INVESTIGATION);
        assertThat(ticket.getSuspendsSettlement()).as("still holding the money").isTrue();
        List<ExceptionActivity> history = tower.getExceptionHistory(theftId);
        assertThat(history).extracting(a -> a.getAction().getValue())
                .containsSubsequence("RAISED", "ASSIGNED", "INVESTIGATION_STARTED", "RESOLVED", "REOPENED");
        ExceptionActivity reopened = history.stream()
                .filter(a -> "REOPENED".equals(a.getAction().getValue())).findFirst().orElseThrow();
        assertThat(reopened.getNote()).isEqualTo(reason);
        assertThat(reopened.getFromStatus()).isEqualTo("RESOLVED");
        assertThat(reopened.getToStatus()).isEqualTo("UNDER_INVESTIGATION");
    }

    @When("the planning-flow THEFT is resolved again and closed by a second manager")
    public void closeTheft() {
        ControlTowerApi tower = world.api(ControlTowerApi.class);
        tower.resolveException(theftId, new ResolveExceptionRequest()
                .resolution("Insurer informed; police report filed and attached"), null);
        String email = "pf-manager-" + world.salt.toLowerCase() + "@demo.local";
        String token = secondManagerToken(email);
        world.client(token, ControlTowerApi.class).closeException(theftId, null,
                new ExceptionNoteRequest().note("Agreed"));
    }

    @When("the planning-flow ETA_DELAY is worked through to closed by the administrator")
    public void closeDelay() {
        ControlTowerApi tower = world.api(ControlTowerApi.class);
        tower.assignException(delayId, new AssignExceptionRequest().assigneeUserId(world.userId()), null);
        tower.investigateException(delayId, null, new ExceptionNoteRequest());
        tower.resolveException(delayId, new ResolveExceptionRequest()
                .resolution("Delivered inside the revised window"), null);
        tower.closeException(delayId, null, new ExceptionNoteRequest());
    }

    @Then("the planning-flow summary is back to the baseline")
    public void summaryBack() {
        ExceptionSummary now = world.api(ControlTowerApi.class).summariseExceptions();
        assertThat(now.getOpenP1()).isEqualTo(baseline.getOpenP1());
        assertThat(now.getOpenP2()).isEqualTo(baseline.getOpenP2());
        assertThat(now.getOpenP3()).isEqualTo(baseline.getOpenP3());
        assertThat(now.getUnassigned()).isEqualTo(baseline.getUnassigned());
        assertThat(now.getSettlementHolds()).isEqualTo(baseline.getSettlementHolds());
    }

    @Then("reopening the closed planning-flow THEFT is refused with {string}")
    public void reopenClosed(String code) {
        assertProblem(() -> world.api(ControlTowerApi.class).reopenException(theftId,
                new ReopenExceptionRequest().reason("Changed my mind"), null), 409, code);
        assertThat(world.api(ControlTowerApi.class).getException(theftId).getStatus())
                .isEqualTo(Exception.StatusEnum.CLOSED);
    }

    @When("a planning-flow ETA_DELAY is raised on the trip")
    public void raiseDelayOnly() {
        delayId = world.api(ControlTowerApi.class).raiseException(new RaiseExceptionRequest()
                .tripId(tripId).category(ExceptionCategory.ETA_DELAY).description("Late"), null).getId();
        support.remember(delayId);
    }

    @Then("reopening the planning-flow ticket before it is resolved is refused with {string}")
    public void reopenUnresolved(String code) {
        assertProblem(() -> world.api(ControlTowerApi.class).reopenException(delayId,
                new ReopenExceptionRequest().reason("Not yet resolved"), null), 409, code);
        assertThat(world.api(ControlTowerApi.class).getException(delayId).getStatus())
                .isEqualTo(Exception.StatusEnum.RAISED);
    }

    @When("the planning-flow ticket is assigned, investigated and resolved")
    public void resolveDelay() {
        ControlTowerApi tower = world.api(ControlTowerApi.class);
        tower.assignException(delayId, new AssignExceptionRequest().assigneeUserId(world.userId()), null);
        tower.investigateException(delayId, null, new ExceptionNoteRequest());
        tower.resolveException(delayId, new ResolveExceptionRequest().resolution("Back on schedule"), null);
    }

    @Then("a planning-flow reopening without a reason is a 400 problem with code validation-failed and an unknown ticket is a 404 problem")
    public void reopenMalformed() {
        ControlTowerApi tower = world.api(ControlTowerApi.class);
        assertProblem(() -> tower.reopenException(delayId, new ReopenExceptionRequest(), null),
                400, "validation-failed");
        assertProblem(() -> tower.reopenException(delayId, new ReopenExceptionRequest().reason("  "), null),
                400, "validation-failed");
        assertThat(tower.getException(delayId).getStatus()).isEqualTo(Exception.StatusEnum.RESOLVED);
        assertProblem(() -> tower.reopenException(UUID.randomUUID(),
                new ReopenExceptionRequest().reason("Whose ticket?"), null), 404, "not-found");
    }

    @Then("the planning-flow summary and reopen are permission checked and tenant isolated")
    public void controlTowerGuarded() {
        String nobody = support.limitedUserToken("ctnobody", List.of());
        assertProblem(() -> world.client(nobody, ControlTowerApi.class).summariseExceptions(),
                403, "access-denied");
        assertProblem(() -> world.client(nobody, ControlTowerApi.class).reopenException(delayId,
                new ReopenExceptionRequest().reason("Not allowed"), null), 403, "access-denied");

        String reader = support.limitedUserToken("ctreader", List.of("EXCEPTION_READ"));
        ControlTowerApi readerApi = world.client(reader, ControlTowerApi.class);
        assertThat(readerApi.summariseExceptions().getOpenP3()).isPositive();
        assertProblem(() -> readerApi.reopenException(delayId,
                new ReopenExceptionRequest().reason("Reading is not closing"), null), 403, "access-denied");
        assertThat(world.api(ControlTowerApi.class).getException(delayId).getStatus())
                .as("the refused reopenings changed nothing").isEqualTo(Exception.StatusEnum.RESOLVED);

        String stranger = support.otherTenantAdministratorToken();
        ControlTowerApi foreign = world.client(stranger, ControlTowerApi.class);
        ExceptionSummary theirs = foreign.summariseExceptions();
        assertThat(theirs.getOpenP1() + theirs.getOpenP2() + theirs.getOpenP3()
                + theirs.getUnassigned() + theirs.getSettlementHolds())
                .as("a new tenant sees none of this tenant's tickets").isZero();
        assertProblem(() -> foreign.reopenException(delayId,
                new ReopenExceptionRequest().reason("Not mine"), null), 404, "not-found");

        assertStatus(() -> world.client(null, ControlTowerApi.class).summariseExceptions(), 401);
    }

    @When("the planning-flow ticket is closed")
    public void closeTicket() {
        world.api(ControlTowerApi.class).closeException(delayId, null, new ExceptionNoteRequest());
    }

    /** An administrator-equivalent second user, who is allowed to close what the first resolved. */
    private String secondManagerToken(String email) {
        List<String> every = jdbc.sql("SELECT code FROM permission").query(String.class).list();
        return support.limitedUserToken(email.substring(0, email.indexOf('@')), every);
    }

    private static void assertSameMoment(OffsetDateTime actual, OffsetDateTime expected) {
        assertThat(Duration.between(actual.toInstant(), expected.toInstant()).abs())
                .as("expected " + expected + " but was " + actual)
                .isLessThan(Duration.ofSeconds(1));
    }
}
