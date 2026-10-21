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
import java.util.List;
import java.util.UUID;

import com.lms.client.api.ControlTowerApi;
import com.lms.client.api.PlanningApi;
import com.lms.client.api.WarehouseApi;
import com.lms.client.model.AsnExpectedLine;
import com.lms.client.model.Consignment;
import com.lms.client.model.CreateAsnRequest;
import com.lms.client.model.CreateBinRequest;
import com.lms.client.model.CreateOutboundOrderRequest;
import com.lms.client.model.CreateSkuRequest;
import com.lms.client.model.DispatchRequest;
import com.lms.client.model.Exception;
import com.lms.client.model.ExceptionCategory;
import com.lms.client.model.OutboundOrder;
import com.lms.client.model.OutboundOrderLine;
import com.lms.client.model.OutboundOrderLineRequest;
import com.lms.client.model.PutawayRequest;
import com.lms.client.model.ReceiptCount;
import com.lms.client.model.ReceiveAsnRequest;
import com.lms.client.model.RegisterPartnerRequest;
import com.lms.client.model.RegisterPointRadiusTerminalRequest;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Value;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Warehousing over HTTP: an ASN counted in with a variance, putaway, a pick
 * that falls short, packing, dispatch of the handling unit, and the
 * consignment planning raises from the hand-off.
 *
 * <p>The control tower and planning hear the warehouse through the outbox, so
 * the steps that read what they made wait for it to settle.
 */
public class WarehouseFlowSteps {

    private static final String LOT = "LOT-1";

    @Value("${local.server.port}")
    private int port;

    private HttpWorld world;
    private UUID warehouse;
    private UUID bin;
    private UUID sku;
    private UUID consignee;
    private UUID site;
    private UUID asnId;
    private UUID orderId;
    private UUID handlingUnitId;

    @Given("the demo administrator is signed in at the warehouse desk")
    public void signIn() {
        world = new HttpWorld(port);
        world.signInAsDemoAdministrator();
    }

    @Given("a warehouse with a bin of {int} units, a stock item, a consignee and a delivery site are registered")
    public void setUp(int capacity) {
        warehouse = world.terminal(RegisterPointRadiusTerminalRequest.CategoryEnum.WAREHOUSE,
                "WH", 13.10, 72.90);
        site = world.terminal(RegisterPointRadiusTerminalRequest.CategoryEnum.CUSTOMER_SITE,
                "WH-SITE", 13.30, 73.10);
        UUID owner = world.partner("WHO", RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER);
        consignee = world.partner("WHN", RegisterPartnerRequest.PartnerTypeEnum.CONSIGNEE);
        WarehouseApi warehouses = world.api(WarehouseApi.class);
        bin = warehouses.createBin(warehouse,
                new CreateBinRequest().code(world.code("BIN-A")).capacityUnits(capacity), null).getId();
        sku = warehouses.createSku(new CreateSkuRequest().code(world.code("SKU"))
                .description("Widgets").ownerPartnerId(owner).uom("EA"), null).getId();
    }

    @Given("an advance shipping notice expects {int} units of the stock item")
    public void asn(int expected) {
        asnId = world.api(WarehouseApi.class).createAsn(new CreateAsnRequest()
                .warehouseId(warehouse)
                .asnNo(world.code("ASN"))
                .supplierPartnerId(consignee)
                .lines(List.of(new AsnExpectedLine().skuId(sku).lotNo(LOT).quantity(expected))),
                null).getId();
    }

    @When("the delivery is counted in with {int} received of which {int} are damaged")
    public void receive(int received, int damaged) {
        world.api(WarehouseApi.class).receiveAsn(asnId, new ReceiveAsnRequest()
                .lines(List.of(new ReceiptCount().lineNo(1).receivedQty(received)
                        .damagedQty(damaged)))
                .crossDock(false), null);
    }

    @Then("the notice is received showing {int} short and {int} damaged")
    public void received(int shortQty, int damaged) {
        var asn = world.api(WarehouseApi.class).getAsn(asnId);
        assertThat(asn.getStatus().getValue()).isEqualTo("RECEIVED");
        assertThat(asn.getLines()).singleElement().satisfies(line -> {
            assertThat(line.getShortQty()).isEqualTo(shortQty);
            assertThat(line.getDamagedQty()).isEqualTo(damaged);
        });
    }

    @Then("the control tower holds a receipt variance ticket about the notice")
    public void receiptTicket() {
        Exception ticket = ticketAbout(ExceptionCategory.RECEIPT_VARIANCE, asnId);
        assertThat(ticket.getTripId()).as("not about a trip").isNull();
        assertThat(ticket.getSuspendsSettlement()).isFalse();
    }

    @When("the {int} undamaged units are put away into the bin")
    public void putAway(int quantity) {
        world.api(WarehouseApi.class).putaway(new PutawayRequest()
                .binId(bin).skuId(sku).lotNo(LOT).quantity(quantity), null);
    }

    @When("an outbound order for {int} units is picked, packed and dispatched to the delivery site weighing {int} kg")
    public void outbound(int units, int grossKg) {
        WarehouseApi warehouses = world.api(WarehouseApi.class);
        orderId = warehouses.createOutboundOrder(new CreateOutboundOrderRequest()
                .warehouseId(warehouse)
                .orderNo(world.code("OUT"))
                .shipTo("Delivery site " + world.salt)
                .lines(List.of(new OutboundOrderLineRequest().skuId(sku).quantity(units))),
                null).getId();
        warehouses.pickOutboundOrder(orderId, null);
        handlingUnitId = warehouses.packOutboundOrder(orderId, null).getId();
        warehouses.dispatchHandlingUnit(handlingUnitId, null, new DispatchRequest()
                .reference(world.code("LOAD"))
                .destinationTerminalId(site)
                .consigneePartnerId(consignee)
                .grossWeightKg(BigDecimal.valueOf(grossKg))
                .volumeM3(new BigDecimal("2.5")));
    }

    @Then("the order shows {int} picked with {int} short and is back-ordered")
    public void picked(int picked, int shortQty) {
        OutboundOrder order = world.api(WarehouseApi.class).getOutboundOrder(orderId);
        assertThat(order.getStatus()).isEqualTo(OutboundOrder.StatusEnum.BACK_ORDERED);
        OutboundOrderLine line = order.getLines().get(0);
        assertThat(line.getPickedQty()).isEqualTo(picked);
        assertThat(line.getShortQty()).isEqualTo(shortQty);
    }

    @Then("the control tower holds a pick shortfall ticket about the order")
    public void shortfallTicket() {
        ticketAbout(ExceptionCategory.PICK_SHORTFALL, orderId);
    }

    @Then("the dispatched handling unit became a consignment for the consignee carrying {int} kg, waiting to be loaded")
    public void consignment(int kilos) {
        Consignment consignment = HttpWorld.eventually("the hand-off consignment",
                () -> world.api(PlanningApi.class).listUnassignedConsignments(null, 200).getItems()
                        .stream()
                        .filter(c -> handlingUnitId.equals(c.getHandlingUnitId()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no consignment for the unit")));
        assertThat(consignment.getTotalDeadWeightKg()).isEqualByComparingTo(BigDecimal.valueOf(kilos));
        assertThat(consignment.getDestinationTerminalCode()).isEqualTo(world.code("WH-SITE"));
        assertThat(consignment.getFreightMode()).isEqualTo("FTL");
    }

    private Exception ticketAbout(ExceptionCategory category, UUID subject) {
        return HttpWorld.eventually("a " + category + " ticket", () ->
                world.api(ControlTowerApi.class).listExceptions(null, null, null, 200).getItems()
                        .stream()
                        .filter(t -> t.getCategory() == category && subject.equals(t.getSubjectId()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no " + category + " ticket")));
    }
}
