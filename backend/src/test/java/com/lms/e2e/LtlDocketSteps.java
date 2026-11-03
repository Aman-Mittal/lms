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

import com.lms.client.api.LtlRatingApi;
import com.lms.client.api.OrdersApi;
import com.lms.client.api.PlanningApi;
import com.lms.client.model.AddOrderLineRequest;
import com.lms.client.model.AssignConsignmentRequest;
import com.lms.client.model.AssignLtlZoneRequest;
import com.lms.client.model.DocketReceivable;
import com.lms.client.model.FreightMode;
import com.lms.client.model.IdResponse;
import com.lms.client.model.OpenLoadRequest;
import com.lms.client.model.PublishLtlRateCardRequest;
import com.lms.client.model.RaiseOrderRequest;
import com.lms.client.model.RegisterPartnerRequest;
import com.lms.client.model.RegisterPointRadiusTerminalRequest;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Value;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Part-load (LTL) freight over HTTP: hubs and zones, a docket, a line-haul
 * load, and the docket's receivable priced by zone.
 *
 * <p>Every step text here is prefixed or phrased for the part-load desk, since
 * Cucumber step expressions must be unique across all step classes.
 */
public class LtlDocketSteps {

    @Value("${local.server.port}")
    private int port;

    private HttpWorld world;
    private UUID originHub;
    private UUID destinationHub;
    private UUID customer;
    private UUID consignee;
    private UUID site;
    private String originZone;
    private String destinationZone;
    private UUID orderId;
    private UUID docketId;
    private UUID loadId;

    @Given("the demo administrator is signed in at the part-load desk")
    public void signIn() {
        world = new HttpWorld(port);
        world.signInAsDemoAdministrator();
    }

    @Given("part-load hubs in zone {string} and zone {string} are registered")
    public void hubs(String from, String to) {
        originZone = from + "-" + world.salt;
        destinationZone = to + "-" + world.salt;
        originHub = world.terminal(RegisterPointRadiusTerminalRequest.CategoryEnum.HUB,
                "HUB-O", 24.00, 74.00);
        destinationHub = world.terminal(RegisterPointRadiusTerminalRequest.CategoryEnum.HUB,
                "HUB-D", 26.50, 76.00);
        LtlRatingApi ltl = world.api(LtlRatingApi.class);
        ltl.assignLtlZone(originHub, new AssignLtlZoneRequest().zoneCode(originZone)
                .outOfDeliveryArea(false));
        ltl.assignLtlZone(destinationHub, new AssignLtlZoneRequest().zoneCode(destinationZone)
                .outOfDeliveryArea(false));
    }

    @Given("a part-load customer, a consignee and a delivery site are registered")
    public void parties() {
        customer = world.partner("LTLC", RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER);
        consignee = world.partner("LTLN", RegisterPartnerRequest.PartnerTypeEnum.CONSIGNEE);
        site = world.terminal(RegisterPointRadiusTerminalRequest.CategoryEnum.CUSTOMER_SITE,
                "LTL-SITE", 26.60, 76.10);
    }

    @Given("a zone rate card charges {double} per kg with a {int} docket charge between those zones")
    public void rateCard(double perKg, int docketCharge) {
        world.api(LtlRatingApi.class).publishLtlRateCard(new PublishLtlRateCardRequest()
                .code(world.code("LTL-RC"))
                .originZone(originZone)
                .destinationZone(destinationZone)
                .effectiveFrom(LocalDate.now(ZoneOffset.UTC).minusDays(30))
                .rateBasis(PublishLtlRateCardRequest.RateBasisEnum.PER_KG)
                .rateAmount(BigDecimal.valueOf(perKg))
                .minimumChargeableWeightKg(new BigDecimal("50"))
                .kgPerCft(new BigDecimal("6"))
                .docketCharge(BigDecimal.valueOf(docketCharge))
                .odaSurcharge(BigDecimal.ZERO), null);
    }

    @When("a part load of {int} kg is booked from the origin hub to the destination hub")
    public void book(int weightKg) {
        OrdersApi orders = world.api(OrdersApi.class);
        orderId = orders.raiseOrder(new RaiseOrderRequest()
                .orgUnitId(world.orgUnitId())
                .customerPartnerId(customer)
                .orderNo(world.code("LTL-SO"))
                .originTerminalId(originHub)
                .requestedPickupAt(OffsetDateTime.now(ZoneOffset.UTC).plusHours(4))
                .source(RaiseOrderRequest.SourceEnum.API)
                .freightMode(FreightMode.LTL)
                .originHubTerminalId(originHub)
                .destinationHubTerminalId(destinationHub), null).getId();
        orders.addOrderLine(orderId, new AddOrderLineRequest()
                .lineNo(1)
                .materialCode("PARCELS")
                .materialDescription("Boxed parcels")
                .materialClass("GENERAL")
                .quantity(new BigDecimal("4"))
                .uom("BOX")
                .deadWeightKg(BigDecimal.valueOf(weightKg))
                .consigneePartnerId(consignee)
                .destinationTerminalId(site), null);
        orders.validateOrder(orderId, null);
        List<IdResponse> dockets = world.api(PlanningApi.class).generateConsignments(orderId, null);
        assertThat(dockets).as("one consignee, one docket").hasSize(1);
        docketId = dockets.get(0).getId();
    }

    @Then("the shipment is a docket numbered in the {string} series between the two hubs")
    public void docketNumbered(String series) {
        var docket = world.api(PlanningApi.class).getConsignment(docketId);
        assertThat(docket.getTrackingRef()).startsWith(series);
        assertThat(docket.getFreightMode()).isEqualTo("LTL");
        assertThat(docket.getOriginHubCode()).isEqualTo(world.code("HUB-O"));
        assertThat(docket.getDestinationHubCode()).isEqualTo(world.code("HUB-D"));
    }

    @When("the docket is put on a line-haul lorry from the origin hub and the load is planned")
    public void loadDocket() {
        PlanningApi planning = world.api(PlanningApi.class);
        UUID lorry = world.lorry("LTL", 20000);
        loadId = planning.openLoad(new OpenLoadRequest()
                .orgUnitId(world.orgUnitId())
                .loadNo(world.code("LTL-LD"))
                .originTerminalId(originHub)
                .vehicleId(lorry)
                .freightMode(FreightMode.LTL)
                .ltlLeg(com.lms.client.model.LtlLeg.LINE_HAUL), null).getId();
        planning.assignConsignment(loadId,
                new AssignConsignmentRequest().consignmentId(docketId), null);
        planning.planLoad(loadId, null);
    }

    @Then("the line-haul load is a part load holding {int} docket")
    public void loadHolds(int count) {
        var load = world.api(PlanningApi.class).getLoad(loadId);
        assertThat(load.getFreightMode()).isEqualTo("LTL");
        assertThat(load.getConsignmentCount()).isEqualTo(count);
        assertThat(load.getStatus()).isEqualTo("PLANNED");
    }

    @Then("the docket receivable is charged on {int} kg and totals {int} at the zone price")
    public void receivable(int chargeableKg, int total) {
        DocketReceivable receivable = HttpWorld.eventually("the docket receivable",
                () -> world.api(LtlRatingApi.class)
                        .listDocketReceivables(customer, null, null, 50).getItems().stream()
                        .filter(r -> docketId.equals(r.getDocketId()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no receivable for the docket")));
        assertThat(receivable.getOriginZone()).isEqualTo(originZone);
        assertThat(receivable.getDestinationZone()).isEqualTo(destinationZone);
        assertThat(receivable.getChargeableWeightKg())
                .isEqualByComparingTo(BigDecimal.valueOf(chargeableKg));
        assertThat(receivable.getDocketCharge()).isEqualByComparingTo("100");
        assertThat(receivable.getTotalAmount()).isEqualByComparingTo(BigDecimal.valueOf(total));
    }
}
