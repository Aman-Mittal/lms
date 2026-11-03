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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.lms.client.api.OrdersApi;
import com.lms.client.api.PlanningApi;
import com.lms.client.api.SourcingApi;
import com.lms.client.api.TripsApi;
import com.lms.client.model.AddOrderLineRequest;
import com.lms.client.model.AddVendorRequest;
import com.lms.client.model.AllocateRequest;
import com.lms.client.model.AssignConsignmentRequest;
import com.lms.client.model.AssignTripRequest;
import com.lms.client.model.BulkAssignConsignmentsRequest;
import com.lms.client.model.Consignment;
import com.lms.client.model.DefineGuideRequest;
import com.lms.client.model.IdResponse;
import com.lms.client.model.Load;
import com.lms.client.model.OpenLoadRequest;
import com.lms.client.model.RaiseOrderRequest;
import com.lms.client.model.RaiseTripRequest;
import com.lms.client.model.RegisterPartnerRequest;
import com.lms.client.model.RegisterPointRadiusTerminalRequest;
import com.lms.client.model.VendorResponseRequest;
import feign.FeignException;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One load carrying a whole order's consignments, built and run over HTTP.
 *
 * <p>Latitude band 33 belongs to this feature alone, so its terminals cannot
 * overlap another mode's. Every consignee is registered over the wire, as a
 * customer's master data would be.
 */
public class BulkLoadSteps {

    private static final int LORRY_PAYLOAD_KG = 20000;

    @Value("${local.server.port}")
    private int port;

    private HttpWorld world;
    private UUID origin;
    private UUID destination;
    private UUID lorry;
    private UUID orderId;
    private BigDecimal lineWeightKg;
    private List<UUID> consignments = List.of();
    private List<UUID> boarded = List.of();
    private UUID loadId;
    private FeignException refusal;

    private PlanningApi planning() {
        return world.api(PlanningApi.class);
    }

    @Given("the demo administrator is signed in at the bulk-load desk")
    public void signIn() {
        world = new HttpWorld(port);
        world.signInAsDemoAdministrator();
        var site = RegisterPointRadiusTerminalRequest.CategoryEnum.CUSTOMER_SITE;
        origin = world.terminal(site, "BL-ORIGIN", 33.10, 80.10);
        destination = world.terminal(site, "BL-DEST", 33.40, 80.40);
        lorry = world.lorry("BL", LORRY_PAYLOAD_KG);
    }

    @Given("a bulk-load order with {int} lines of {int} kg, each to its own consignee, is raised and validated")
    public void anOrder(int lines, int weightKg) {
        lineWeightKg = BigDecimal.valueOf(weightKg);
        UUID customer = world.partner("BLC", RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER);
        OrdersApi orders = world.api(OrdersApi.class);
        orderId = orders.raiseOrder(new RaiseOrderRequest()
                .orgUnitId(world.orgUnitId())
                .customerPartnerId(customer)
                .orderNo(world.code("SO-BL"))
                .originTerminalId(origin)
                .requestedPickupAt(OffsetDateTime.now(ZoneOffset.UTC).plusHours(4))
                .source(RaiseOrderRequest.SourceEnum.API), null).getId();
        for (int i = 1; i <= lines; i++) {
            UUID consignee = world.partner("BLK" + i, RegisterPartnerRequest.PartnerTypeEnum.CONSIGNEE);
            orders.addOrderLine(orderId, new AddOrderLineRequest()
                    .lineNo(i)
                    .materialCode("CASE-" + i)
                    .materialDescription("Cased goods")
                    .materialClass("GENERAL")
                    .quantity(BigDecimal.ONE)
                    .uom("CASE")
                    .deadWeightKg(lineWeightKg)
                    .consigneePartnerId(consignee)
                    .destinationTerminalId(destination), null);
        }
        orders.validateOrder(orderId, null);
    }

    @When("the bulk-load order is planned into consignments")
    public void planned() {
        consignments = planning().generateConsignments(orderId, null).stream()
                .map(IdResponse::getId).toList();
    }

    @Then("the bulk-load queue for the order lists {int} consignments")
    public void queueLists(int count) {
        List<Consignment> queue = planning().listUnassignedConsignments(null, 200, orderId, null).getItems();
        assertThat(queue).hasSize(count);
        assertThat(queue).extracting(Consignment::getId).containsExactlyInAnyOrderElementsOf(consignments);
    }

    @When("the first bulk-load consignment is put on another load")
    public void firstElsewhere() {
        UUID other = openLoad("LD-BLX");
        planning().assignConsignment(other,
                new AssignConsignmentRequest().consignmentId(consignments.get(0)), null);
    }

    @When("every bulk-load consignment is put on one load in a single request")
    public void boardAll() {
        loadId = openLoad("LD-BL");
        // Last generated first, so that keeping the order given is visible.
        List<UUID> batch = new ArrayList<>(consignments);
        java.util.Collections.reverse(batch);
        try {
            planning().assignConsignmentsInBulk(loadId,
                    new BulkAssignConsignmentsRequest().consignmentIds(batch), null);
            boarded = batch;
        } catch (FeignException e) {
            refusal = e;
        }
    }

    @Then("the bulk load carries {int} consignments weighing {int} kg in the order they were given")
    public void loadCarries(int count, int weightKg) {
        assertThat(refusal).as("the batch was refused: %s",
                refusal == null ? "" : refusal.contentUTF8()).isNull();
        Load load = planning().getLoad(loadId);
        assertThat(load.getConsignmentCount()).isEqualTo(count);
        assertThat(load.getPlannedWeightKg()).isEqualByComparingTo(BigDecimal.valueOf(weightKg));
        assertThat(planning().listLoadConsignments(loadId)).extracting(Consignment::getId)
                .containsExactlyElementsOf(boarded);
        assertThat(planning().listUnassignedConsignments(null, 200, orderId, null).getItems()).isEmpty();
    }

    @When("the bulk load is planned, awarded, dispatched and its trip completed")
    public void runTheLoad() {
        UUID vendor = world.activeVendor("BLV");
        UUID driver = world.driver("BL");
        SourcingApi sourcing = world.api(SourcingApi.class);
        UUID guideId = sourcing.defineRoutingGuide(new DefineGuideRequest()
                .orgUnitId(world.orgUnitId())
                .originTerminalId(origin)
                .destinationTerminalId(destination)
                .vehicleType(HttpWorld.VEHICLE_TYPE), null).getId();
        sourcing.addRoutingGuideVendor(guideId, new AddVendorRequest()
                .vendorPartnerId(vendor).rankNo(1).responseSlaMinutes(60)
                .agreedRate(new BigDecimal("45000")), null);

        planning().planLoad(loadId, null);
        UUID allocationId = sourcing.allocateLoad(new AllocateRequest().loadId(loadId), null).getId();
        sourcing.acceptOffer(allocationId, new VendorResponseRequest().vendorPartnerId(vendor), null);

        TripsApi trips = world.api(TripsApi.class);
        UUID tripId = trips.raiseTrip(new RaiseTripRequest().tripNo(world.code("TR-BL"))
                .loadId(loadId).plannedStartAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(60)),
                null).getId();
        trips.assignTrip(tripId, new AssignTripRequest().vehicleId(lorry).driverId(driver), null);

        int carriedKg = lineWeightKg.multiply(BigDecimal.valueOf(boarded.size())).intValueExact();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        world.dispatch(tripId, origin, carriedKg, now.minusHours(6));
        world.deliver(tripId, now.minusHours(1));
    }

    @Then("every bulk-load consignment is DELIVERED")
    public void allDelivered() {
        List<Consignment> aboard = HttpWorld.eventually("every consignment delivered", () -> {
            List<Consignment> now = planning().listLoadConsignments(loadId);
            assertThat(now).hasSize(boarded.size())
                    .allMatch(c -> "DELIVERED".equals(c.getStatus()));
            return now;
        });
        assertThat(aboard).extracting(Consignment::getId).containsExactlyElementsOf(boarded);
    }

    @Then("the bulk-load request is refused as {string} naming only the first consignment")
    public void refused(String code) {
        assertThat(refusal).as("the batch should have been refused").isNotNull();
        assertThat(refusal.status()).isEqualTo(409);
        JsonNode problem = JsonMapper.builder().build().readTree(refusal.contentUTF8());
        assertThat(problem.get("code").asString()).isEqualTo(code);
        String firstRef = planning().getConsignment(consignments.get(0)).getTrackingRef();
        List<String> named = new ArrayList<>();
        problem.get("errors").properties().forEach(e -> named.add(e.getKey()));
        assertThat(named).containsExactly(firstRef);
        assertThat(problem.get("errors").get(firstRef).asString())
                .startsWith("consignment-not-assignable: ");
    }

    @Then("the bulk load carries nothing")
    public void carriesNothing() {
        assertThat(planning().listLoadConsignments(loadId)).isEmpty();
        assertThat(planning().getLoad(loadId).getConsignmentCount()).isZero();
        assertThat(planning().getConsignment(consignments.get(1)).getStatus())
                .isEqualTo("PLANNED");
    }

    private UUID openLoad(String loadNo) {
        return planning().openLoad(new OpenLoadRequest()
                .orgUnitId(world.orgUnitId())
                .loadNo(world.code(loadNo))
                .originTerminalId(origin)
                .vehicleId(lorry), null).getId();
    }
}
