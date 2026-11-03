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
package com.lms.acceptance;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import com.lms.identity.access.SubjectAttributes;
import com.lms.masterdata.command.RegisterTerminalCommand;
import com.lms.masterdata.command.TerminalCommandService;
import com.lms.masterdata.command.domain.Terminal;
import com.lms.shared.geo.LatLon;
import com.lms.shared.query.Slice;
import com.lms.shared.tenant.TenantContext;
import com.lms.warehouse.command.InboundCommandService;
import com.lms.warehouse.command.InventoryCommandService;
import com.lms.warehouse.command.OutboundCommandService;
import com.lms.warehouse.command.WarehouseSetupCommandService;
import com.lms.warehouse.command.WaveCommandService;
import com.lms.warehouse.events.BackOrderFilled;
import com.lms.warehouse.events.HandlingUnitDispatched;
import com.lms.warehouse.events.PickShortfallRecorded;
import com.lms.warehouse.events.QuarantineResolved;
import com.lms.warehouse.events.ReceiptVarianceRecorded;
import com.lms.warehouse.query.AsnSummaryView;
import com.lms.warehouse.query.AsnView;
import com.lms.warehouse.query.HandlingUnitSummaryView;
import com.lms.warehouse.query.HandlingUnitView;
import com.lms.warehouse.query.OutboundOrderSummaryView;
import com.lms.warehouse.query.OutboundOrderView;
import com.lms.warehouse.query.PickWaveSummaryView;
import com.lms.warehouse.query.PickWaveView;
import com.lms.warehouse.query.StockBalanceView;
import com.lms.warehouse.query.WarehouseQueryService;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step definitions for {@code warehouse.feature}.
 *
 * <p>Every expression here names the warehouse, a bin, a stock item or the
 * ledger explicitly: step text is global across all step classes, and a
 * generic "it is refused" would collide with somebody else's.
 */
public class WarehouseSteps {

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private TerminalCommandService terminals;
    @Autowired
    private WarehouseSetupCommandService setup;
    @Autowired
    private InboundCommandService inbound;
    @Autowired
    private OutboundCommandService outbound;
    @Autowired
    private InventoryCommandService inventory;
    @Autowired
    private WaveCommandService waves;
    @Autowired
    private WarehouseQueryService queries;
    @Autowired
    private WarehouseEventProbe probe;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private RuntimeException failure;
    private RuntimeException databaseFailure;
    private UUID lastQuarantineMovement;

    // ------------------------------------------------------------ master data

    @Given("a warehouse {string} at {double},{double}")
    public void aWarehouse(String code, double lat, double lon) {
        world.putRef("warehouse:" + code, terminals.register(new RegisterTerminalCommand.PointRadius(
                world.orgUnitId(), world.uniqueCode(code), code,
                Terminal.FunctionalCategory.WAREHOUSE, new LatLon(lat, lon), 300, 4, null, null,
                45, List.of())));
    }

    @Given("warehouse {string} has a bin {string} holding at most {int} units")
    public void aBin(String warehouse, String bin, int capacity) {
        world.putRef("bin:" + bin, setup.createBin(warehouseId(warehouse), bin, capacity));
    }

    @When("a bin {string} is added to terminal {string}")
    public void binAtTerminal(String bin, String terminal) {
        attempt(() -> setup.createBin(world.ref(terminal), bin, 10));
    }

    @Given("a stock item {string} described as {string}")
    public void aStockItem(String sku, String description) {
        world.putRef("sku:" + sku, setup.createSku(world.uniqueCode(sku), description, null, "EA"));
    }

    @Given("a stock item {string} owned by {string}")
    public void aStockItemOwnedBy(String sku, String partner) {
        world.putRef("sku:" + sku, setup.createSku(world.uniqueCode(sku), sku + " goods",
                world.ref(partner), "EA"));
    }

    /** A customer's own staff on the portal: may read the warehouse, nothing else. */
    @When("the signed-in user is a warehouse portal user for {string}")
    public void warehousePortalUser(String partner) {
        AccessSteps.signInWith("warehouse-portal", List.of("WAREHOUSE_READ"),
                new SubjectAttributes(null, null, world.ref(partner), null));
    }

    // ---------------------------------------------------------------- inbound

    @Given("an ASN {string} into {string} expecting:")
    public void anAsn(String asnNo, String warehouse, List<Map<String, String>> lines) {
        world.putRef("asn:" + asnNo, inbound.createAsn(warehouseId(warehouse),
                world.uniqueCode(asnNo), null, lines.stream()
                        .map(row -> new InboundCommandService.ExpectedLine(
                                skuId(row.get("sku")), row.get("lot"),
                                Integer.parseInt(row.get("quantity"))))
                        .toList()));
    }

    @When("ASN {string} is counted in:")
    public void countedIn(String asnNo, List<Map<String, String>> counts) {
        attempt(() -> inbound.receive(asnId(asnNo), counted(counts), false));
    }

    @When("ASN {string} is counted in for cross-dock:")
    public void countedInForCrossDock(String asnNo, List<Map<String, String>> counts) {
        attempt(() -> inbound.receive(asnId(asnNo), counted(counts), true));
    }

    /**
     * Shorthand for stock that is already in a bin: an ASN for exactly that
     * quantity, received whole and put away whole. The interesting part of
     * the scenarios that use it is what happens next.
     */
    @Given("{int} of {string} have been received into {string} and put away in bin {string}")
    public void stockInBin(int quantity, String sku, String warehouse, String bin) {
        String asnNo = "STOCK-" + UUID.randomUUID().toString().substring(0, 6);
        UUID asnId = inbound.createAsn(warehouseId(warehouse), world.uniqueCode(asnNo), null,
                List.of(new InboundCommandService.ExpectedLine(skuId(sku), null, quantity)));
        inbound.receive(asnId, List.of(new InboundCommandService.CountedLine(1, quantity, 0)),
                false);
        inbound.putaway(binId(bin), skuId(sku), null, quantity);
    }

    @When("{int} of {string} are put away from the dock into bin {string}")
    public void putAway(int quantity, String sku, String bin) {
        attempt(() -> inbound.putaway(binId(bin), skuId(sku), null, quantity));
    }

    // --------------------------------------------------------------- outbound

    @Given("an outbound order {string} from {string} for {int} of {string}")
    public void anOutboundOrder(String orderNo, String warehouse, int quantity, String sku) {
        world.putRef("outbound:" + orderNo, outbound.createOrder(warehouseId(warehouse),
                world.uniqueCode(orderNo), "Customer DC",
                List.of(new OutboundCommandService.RequestedLine(skuId(sku), quantity))));
    }

    @Given("an outbound order {string} from {string} for {int} of {string} and {int} of {string}")
    public void aTwoLineOutboundOrder(String orderNo, String warehouse, int first,
                                      String firstSku, int second, String secondSku) {
        world.putRef("outbound:" + orderNo, outbound.createOrder(warehouseId(warehouse),
                world.uniqueCode(orderNo), "Customer DC",
                List.of(new OutboundCommandService.RequestedLine(skuId(firstSku), first),
                        new OutboundCommandService.RequestedLine(skuId(secondSku), second))));
    }

    @When("outbound order {string} is picked")
    public void picked(String orderNo) {
        attempt(() -> outbound.pick(orderId(orderNo)));
    }

    @When("outbound order {string} is packed into handling unit {string}")
    public void packed(String orderNo, String unit) {
        attempt(() -> world.putRef("hu:" + unit, outbound.pack(orderId(orderNo))));
    }

    @When("handling unit {string} is dispatched as {string}")
    public void dispatched(String unit, String reference) {
        attempt(() -> outbound.dispatch(world.ref("hu:" + unit), reference));
    }

    @When("the cross-dock unit of ASN {string} is dispatched as {string}")
    public void crossDockDispatched(String asnNo, String reference) {
        attempt(() -> outbound.dispatch(crossDockUnit(asnNo), reference));
    }

    @When("the back-order of outbound order {string} is cancelled")
    public void backOrderCancelled(String orderNo) {
        attempt(() -> outbound.cancelBackOrder(orderId(orderNo)));
    }

    // ------------------------------------------------------------- pick waves

    @When("outbound orders {string} are released in {string} as wave {string}")
    public void waveReleased(String orderNos, String warehouse, String wave) {
        attempt(() -> world.putRef("wave:" + wave, waves.release(warehouseId(warehouse),
                names(orderNos).stream().map(this::orderId).toList())));
    }

    @When("wave {string} is picked")
    public void wavePicked(String wave) {
        attempt(() -> waves.pick(world.ref("wave:" + wave)));
    }

    // -------------------------------------------------------------- quarantine

    @When("{int} quarantined {string} in {string} are released to bin {string} because {string}")
    public void releasedFromQuarantine(int quantity, String sku, String warehouse, String bin,
                                       String reason) {
        attempt(() -> lastQuarantineMovement = inventory.releaseFromQuarantine(binId(bin),
                skuId(sku), null, quantity, reason));
    }

    @When("{int} quarantined {string} in {string} are written off because {string}")
    public void writtenOffFromQuarantine(int quantity, String sku, String warehouse,
                                         String reason) {
        attempt(() -> lastQuarantineMovement = inventory.writeOffQuarantine(
                warehouseId(warehouse), skuId(sku), null, quantity, reason));
    }

    @When("a movement taking {int} of {string} out of the quarantine of {string} is written straight to the ledger")
    public void rawQuarantineWithdrawal(int quantity, String sku, String warehouse) {
        UUID warehouseId = warehouseId(warehouse);
        rawSql(() -> jdbc.sql("""
                        INSERT INTO stock_movement (id, tenant_id, warehouse_id, movement_type,
                                                    location_type, location_id, sku_id, lot_no,
                                                    quantity, actor, occurred_at, version)
                        VALUES (gen_random_uuid(), :tenantId, :warehouseId, 'ADJUST', 'QUARANTINE',
                                :warehouseId, :skuId, '', :quantity, 'a script', :at, 0)
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("warehouseId", warehouseId)
                .param("skuId", skuId(sku))
                .param("quantity", -quantity)
                .param("at", Instant.now().atOffset(ZoneOffset.UTC))
                .update());
    }

    // ------------------------------------------------------------- inventory

    @When("{int} of {string} are written off from bin {string} because {string}")
    public void writtenOff(int quantity, String sku, String bin, String reason) {
        attempt(() -> inventory.adjust(binId(bin), skuId(sku), null, -quantity, reason));
    }

    /**
     * Goes round the service entirely, as a careless script or a bug in some
     * future module would. Whatever the service checks, the database must
     * refuse this on its own.
     */
    @When("a movement taking {int} of {string} out of bin {string} is written straight to the ledger")
    public void rawNegativeMovement(int quantity, String sku, String bin) {
        rawSql(() -> jdbc.sql("""
                        INSERT INTO stock_movement (id, tenant_id, warehouse_id, movement_type,
                                                    location_type, location_id, sku_id, lot_no,
                                                    quantity, actor, occurred_at, version)
                        SELECT gen_random_uuid(), tenant_id, warehouse_id, 'ADJUST', 'BIN', id,
                               :skuId, '', :quantity, 'a script', :at, 0
                          FROM warehouse_bin WHERE id = :binId
                        """)
                .param("skuId", skuId(sku))
                .param("quantity", -quantity)
                .param("at", Instant.now().atOffset(ZoneOffset.UTC))
                .param("binId", binId(bin))
                .update());
    }

    @When("the latest movement of {string} is edited in the ledger")
    public void editLedger(String sku) {
        rawSql(() -> jdbc.sql("""
                        UPDATE stock_movement SET quantity = quantity + 1
                         WHERE id = (SELECT id FROM stock_movement WHERE sku_id = :skuId
                                      ORDER BY occurred_at DESC, id DESC LIMIT 1)
                        """)
                .param("skuId", skuId(sku))
                .update());
    }

    @When("the movements of {string} are deleted from the ledger")
    public void deleteLedger(String sku) {
        rawSql(() -> jdbc.sql("DELETE FROM stock_movement WHERE sku_id = :skuId")
                .param("skuId", skuId(sku))
                .update());
    }

    @When("the balance of {string} in bin {string} is overwritten directly")
    public void overwriteBalance(String sku, String bin) {
        rawSql(() -> jdbc.sql("""
                        UPDATE stock_balance SET on_hand = 1000
                         WHERE location_type = 'BIN' AND location_id = :binId AND sku_id = :skuId
                        """)
                .param("binId", binId(bin))
                .param("skuId", skuId(sku))
                .update());
    }

    // ------------------------------------------------------------- assertions

    @Then("the warehouse refuses mentioning {string}")
    public void refusedMentioning(String fragment) {
        assertThat(failure).as("the warehouse should have refused").isNotNull();
        assertThat(failure.getMessage()).contains(fragment);
    }

    @Then("the warehouse refuses it as outside the caller's subtree")
    public void refusedByPolicy() {
        assertThat(failure).as("the access policy should have refused").isInstanceOf(
                AccessDeniedException.class);
    }

    @Then("the warehouse accepts it")
    public void accepted() {
        assertThat(failure).as("the warehouse refused: %s",
                failure == null ? "" : failure.getMessage()).isNull();
    }

    @Then("the ledger database refuses it mentioning {string}")
    public void databaseRefused(String fragment) {
        assertThat(databaseFailure).as("the database should have refused").isNotNull();
        assertThat(NestedExceptionUtils.getMostSpecificCause(databaseFailure).getMessage())
                .contains(fragment);
    }

    @Then("ASN {string} line {int} shows {int} short, {int} excess and {int} damaged")
    public void lineVariance(String asnNo, int lineNo, int shortQty, int excessQty,
                             int damagedQty) {
        AsnView.Line line = asn(asnNo).lines().stream()
                .filter(candidate -> candidate.lineNo() == lineNo)
                .findFirst()
                .orElseThrow(() -> new AssertionError("ASN " + asnNo + " has no line " + lineNo));
        assertThat(line.shortQty()).as("short").isEqualTo(shortQty);
        assertThat(line.excessQty()).as("excess").isEqualTo(excessQty);
        assertThat(line.damagedQty()).as("damaged").isEqualTo(damagedQty);
    }

    @Then("ASN {string} is {string}")
    public void asnStatus(String asnNo, String status) {
        assertThat(asn(asnNo).status()).isEqualTo(status);
    }

    @Then("{int} receipt variances were published for ASN {string}")
    public void variancesPublished(int count, String asnNo) {
        UUID asnId = asnId(asnNo);
        assertThat(probe.heard(ReceiptVarianceRecorded.class).stream()
                .filter(event -> event.asnId().equals(asnId))
                .toList()).hasSize(count);
    }

    @Then("the dock of {string} holds {int} of {string}")
    public void dockHolds(String warehouse, int quantity, String sku) {
        UUID warehouseId = warehouseId(warehouse);
        assertThat(onHand(warehouseId, sku, "DOCK", warehouseId)).isEqualTo(quantity);
    }

    @Then("bin {string} of {string} holds {int} of {string}")
    public void binHolds(String bin, String warehouse, int quantity, String sku) {
        assertThat(onHand(warehouseId(warehouse), sku, "BIN", binId(bin))).isEqualTo(quantity);
    }

    @Then("outbound order {string} is {string}")
    public void orderStatus(String orderNo, String status) {
        assertThat(order(orderNo).status()).isEqualTo(status);
    }

    @Then("outbound order {string} line {int} was picked {int} with {int} short")
    public void linePicked(String orderNo, int lineNo, int picked, int shortQty) {
        OutboundOrderView.Line line = order(orderNo).lines().get(lineNo - 1);
        assertThat(line.pickedQty()).as("picked").isEqualTo(picked);
        assertThat(line.shortQty()).as("short").isEqualTo(shortQty);
    }

    @Then("a pick shortfall of {int} was published for outbound order {string}")
    public void shortfallPublished(int shortQty, String orderNo) {
        UUID orderId = orderId(orderNo);
        assertThat(probe.heard(PickShortfallRecorded.class).stream()
                .filter(event -> event.orderId().equals(orderId))
                .map(PickShortfallRecorded::shortQty)
                .toList()).containsExactly(shortQty);
    }

    @Then("no pick shortfall was published for outbound order {string}")
    public void noShortfall(String orderNo) {
        UUID orderId = orderId(orderNo);
        assertThat(probe.heard(PickShortfallRecorded.class).stream()
                .filter(event -> event.orderId().equals(orderId))
                .toList()).isEmpty();
    }

    @Then("handling unit {string} is {string} holding {int} of {string}")
    public void unitHolds(String unit, String status, int quantity, String sku) {
        HandlingUnitView view = handlingUnit(world.ref("hu:" + unit));
        assertThat(view.status()).isEqualTo(status);
        assertThat(quantityIn(view, sku)).isEqualTo(quantity);
    }

    @Then("ASN {string} went into a handling unit holding {int} of {string}")
    public void crossDockUnitHolds(String asnNo, int quantity, String sku) {
        HandlingUnitView view = handlingUnit(crossDockUnit(asnNo));
        assertThat(view.source()).isEqualTo("CROSS_DOCK");
        assertThat(view.status()).isEqualTo("PACKED");
        assertThat(quantityIn(view, sku)).isEqualTo(quantity);
    }

    @Then("the hand-off of handling unit {string} carried {int} of {string} as {string}")
    public void handOff(String unit, int quantity, String sku, String reference) {
        assertHandOff(world.ref("hu:" + unit), quantity, sku, reference);
    }

    @Then("the hand-off of the cross-dock unit of ASN {string} carried {int} of {string} as {string}")
    public void crossDockHandOff(String asnNo, int quantity, String sku, String reference) {
        assertHandOff(crossDockUnit(asnNo), quantity, sku, reference);
    }

    @Then("no stock of {string} remains in {string}")
    public void nothingLeft(String sku, String warehouse) {
        assertThat(inTransaction(() -> queries.stock(warehouseId(warehouse), skuId(sku), null,
                null, 200).items())).isEmpty();
    }

    /**
     * The ledger, not only the balance, says where everything went: summing
     * every movement of the SKU gives what is still in the warehouse.
     */
    @Then("the ledger for {string} sums to {int}")
    public void ledgerSums(String sku, int total) {
        int sum = inTransaction(() -> jdbc.sql(
                        "SELECT coalesce(sum(quantity), 0)::int FROM stock_movement WHERE sku_id = :skuId")
                .param("skuId", skuId(sku))
                .query(Integer.class)
                .single());
        assertThat(sum).isEqualTo(total);
    }

    @Then("the quarantine of {string} holds {int} of {string}")
    public void quarantineHolds(String warehouse, int quantity, String sku) {
        UUID warehouseId = warehouseId(warehouse);
        assertThat(onHand(warehouseId, sku, "QUARANTINE", warehouseId)).isEqualTo(quantity);
    }

    @Then("only the quarantine of {string} still holds {string}")
    public void onlyQuarantineHolds(String warehouse, String sku) {
        List<StockBalanceView> balances = inTransaction(() -> queries.stock(
                warehouseId(warehouse), skuId(sku), null, null, 200).items());
        assertThat(balances).isNotEmpty()
                .extracting(StockBalanceView::locationType)
                .containsOnly("QUARANTINE");
    }

    @Then("a filled back-order of {int} was published for outbound order {string} line {int}")
    public void backOrderFilled(int filled, String orderNo, int lineNo) {
        assertBackOrderFilled(filled, null, orderNo, lineNo);
    }

    @Then("a filled back-order of {int} in wave {string} was published for outbound order {string} line {int}")
    public void backOrderFilledInWave(int filled, String wave, String orderNo, int lineNo) {
        assertBackOrderFilled(filled, world.ref("wave:" + wave), orderNo, lineNo);
    }

    @Then("no filled back-order was published for outbound order {string}")
    public void noBackOrderFilled(String orderNo) {
        UUID orderId = orderId(orderNo);
        assertThat(probe.heard(BackOrderFilled.class).stream()
                .filter(event -> event.orderId().equals(orderId))
                .toList()).isEmpty();
    }

    @Then("a pick shortfall of {int} in wave {string} was published for outbound order {string}")
    public void shortfallInWave(int shortQty, String wave, String orderNo) {
        UUID orderId = orderId(orderNo);
        UUID waveId = world.ref("wave:" + wave);
        assertThat(probe.heard(PickShortfallRecorded.class).stream()
                .filter(event -> event.orderId().equals(orderId))
                .toList())
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.shortQty()).isEqualTo(shortQty);
                    assertThat(event.waveId()).isEqualTo(waveId);
                });
    }

    @Then("wave {string} is {string} with orders {string}")
    public void waveStatus(String wave, String status, String orderNos) {
        PickWaveView view = inTransaction(() -> queries.pickWave(world.ref("wave:" + wave))
                .orElseThrow(() -> new AssertionError("No wave " + wave)));
        assertThat(view.status()).isEqualTo(status);
        assertThat(view.orders()).extracting(PickWaveView.Order::orderId)
                .containsExactlyElementsOf(names(orderNos).stream().map(this::orderId).toList());
    }

    /** Read from the ledger: the PICK movements out of the bin that name the order. */
    @Then("outbound order {string} took {int} of {string} from bin {string} and {int} from bin {string}")
    public void tookFromBins(String orderNo, int first, String sku, String firstBin, int second,
                             String secondBin) {
        assertThat(pickedFrom(orderNo, sku, firstBin)).as(firstBin).isEqualTo(first);
        assertThat(pickedFrom(orderNo, sku, secondBin)).as(secondBin).isEqualTo(second);
    }

    @Then("the quarantine change was audited as {string} because {string}")
    public void quarantineAudited(String action, String reason) {
        assertThat(lastQuarantineMovement).as("no quarantine change was made").isNotNull();
        Map<String, Object> entry = inTransaction(() -> jdbc.sql("""
                        SELECT action, after_state ->> 'reason' AS reason
                          FROM audit_log
                         WHERE resource_type = 'STOCK_MOVEMENT' AND resource_id = :id
                        """)
                .param("id", lastQuarantineMovement)
                .query()
                .singleRow());
        assertThat(entry).containsEntry("action", action).containsEntry("reason", reason);
    }

    @Then("a quarantine {string} of {int} of {string} was published")
    public void quarantineResolvedPublished(String outcome, int quantity, String sku) {
        assertThat(probe.heard(QuarantineResolved.class).stream()
                .filter(event -> event.movementId().equals(lastQuarantineMovement))
                .toList())
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.outcome().name()).isEqualTo(outcome);
                    assertThat(event.quantity()).isEqualTo(quantity);
                    assertThat(event.skuId()).isEqualTo(skuId(sku));
                });
    }

    // --------------------------------------------------------------- listings

    @Then("listing {word} ASNs of {string} {int} at a time gives {string}")
    public void asnListing(String status, String warehouse, int pageSize, String asnNos) {
        List<UUID> listed = allPages(cursor -> queries.asns(warehouseId(warehouse),
                statusOrNull(status), cursor, pageSize), AsnSummaryView::id);
        assertThat(listed).containsExactlyElementsOf(
                names(asnNos).stream().map(this::asnId).toList());
    }

    @Then("listing {word} outbound orders of {string} {int} at a time gives {string}")
    public void orderListing(String status, String warehouse, int pageSize, String orderNos) {
        List<UUID> listed = allPages(cursor -> queries.outboundOrders(warehouseId(warehouse),
                statusOrNull(status), cursor, pageSize), OutboundOrderSummaryView::id);
        assertThat(listed).containsExactlyElementsOf(
                names(orderNos).stream().map(this::orderId).toList());
    }

    @Then("listing {word} handling units of {string} {int} at a time gives {string}")
    public void unitListing(String status, String warehouse, int pageSize, String units) {
        List<UUID> listed = allPages(cursor -> queries.handlingUnits(warehouseId(warehouse),
                statusOrNull(status), cursor, pageSize), HandlingUnitSummaryView::id);
        assertThat(listed).containsExactlyElementsOf(
                names(units).stream().map(unit -> world.ref("hu:" + unit)).toList());
    }

    @Then("listing {word} pick waves of {string} gives {string}")
    public void waveListing(String status, String warehouse, String waves) {
        List<UUID> listed = allPages(cursor -> queries.pickWaves(warehouseId(warehouse),
                statusOrNull(status), cursor, 1), PickWaveSummaryView::id);
        assertThat(listed).containsExactlyElementsOf(
                names(waves).stream().map(wave -> world.ref("wave:" + wave)).toList());
    }

    @Then("wave {string} is listed holding {int} orders")
    public void waveListedWithCount(String wave, int orders) {
        UUID id = world.ref("wave:" + wave);
        PickWaveView view = inTransaction(() -> queries.pickWave(id).orElseThrow());
        PickWaveSummaryView row = inTransaction(() -> queries.pickWaves(view.warehouseId(), null,
                        null, 200).items().stream()
                .filter(candidate -> candidate.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("Wave " + wave + " is not listed")));
        assertThat(row.orderCount()).isEqualTo(orders);
    }

    @Then("outbound order {string} is listed with {int} ordered, {int} picked and {int} back-ordered")
    public void orderListedWithTotals(String orderNo, int ordered, int picked, int backOrdered) {
        UUID orderId = orderId(orderNo);
        OutboundOrderView order = order(orderNo);
        OutboundOrderSummaryView row = inTransaction(() -> queries.outboundOrders(
                        order.warehouseId(), null, null, 200).items().stream()
                .filter(candidate -> candidate.id().equals(orderId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Order " + orderNo + " is not listed")));
        assertThat(row.orderedUnits()).as("ordered").isEqualTo(ordered);
        assertThat(row.pickedUnits()).as("picked").isEqualTo(picked);
        assertThat(row.backOrderedUnits()).as("back-ordered").isEqualTo(backOrdered);
    }

    // --------------------------------------------------------------- helpers

    private void assertBackOrderFilled(int filled, UUID waveId, String orderNo, int lineNo) {
        UUID orderId = orderId(orderNo);
        assertThat(probe.heard(BackOrderFilled.class).stream()
                .filter(event -> event.orderId().equals(orderId) && event.lineNo() == lineNo)
                .toList())
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.filledQty()).isEqualTo(filled);
                    assertThat(event.waveId()).isEqualTo(waveId);
                });
    }

    private int pickedFrom(String orderNo, String sku, String bin) {
        return inTransaction(() -> jdbc.sql("""
                        SELECT coalesce(-sum(quantity), 0)::int FROM stock_movement
                         WHERE movement_type = 'PICK' AND location_type = 'BIN'
                           AND location_id = :binId AND sku_id = :skuId
                           AND reference_id = :orderId
                        """)
                .param("binId", binId(bin))
                .param("skuId", skuId(sku))
                .param("orderId", orderId(orderNo))
                .query(Integer.class)
                .single());
    }

    /** Walks every page with the cursor each one hands back. */
    private <T> List<UUID> allPages(Function<String, Slice<T>> page, Function<T, UUID> id) {
        List<UUID> ids = new ArrayList<>();
        String cursor = null;
        do {
            String current = cursor;
            Slice<T> slice = inTransaction(() -> page.apply(current));
            slice.items().forEach(item -> ids.add(id.apply(item)));
            cursor = slice.nextCursor();
        } while (cursor != null);
        return ids;
    }

    private static String statusOrNull(String status) {
        return "all".equals(status) ? null : status;
    }

    private static List<String> names(String commaSeparated) {
        return Arrays.stream(commaSeparated.split(",")).map(String::strip).toList();
    }

    private static List<InboundCommandService.CountedLine> counted(List<Map<String, String>> rows) {
        return rows.stream()
                .map(row -> new InboundCommandService.CountedLine(
                        Integer.parseInt(row.get("line")),
                        Integer.parseInt(row.get("received")),
                        Integer.parseInt(row.get("damaged"))))
                .toList();
    }

    private void assertHandOff(UUID unitId, int quantity, String sku, String reference) {
        List<HandlingUnitDispatched> handOffs = probe.heard(HandlingUnitDispatched.class).stream()
                .filter(event -> event.handlingUnitId().equals(unitId))
                .toList();
        assertThat(handOffs).hasSize(1);
        assertThat(handOffs.get(0).dispatchRef()).isEqualTo(reference);
        assertThat(handOffs.get(0).contents())
                .filteredOn(content -> content.skuId().equals(skuId(sku)))
                .extracting(HandlingUnitDispatched.Content::quantity)
                .containsExactly(quantity);
        assertThat(handlingUnit(unitId).status()).isEqualTo("DISPATCHED");
    }

    private int quantityIn(HandlingUnitView view, String sku) {
        UUID skuId = skuId(sku);
        return view.contents().stream()
                .filter(content -> content.skuId().equals(skuId))
                .mapToInt(HandlingUnitView.Content::quantity)
                .sum();
    }

    private int onHand(UUID warehouseId, String sku, String locationType, UUID locationId) {
        return inTransaction(() -> queries.stock(warehouseId, skuId(sku), null, null, 200)
                .items().stream()
                .filter(balance -> balance.locationType().equals(locationType)
                        && balance.locationId().equals(locationId))
                .mapToInt(StockBalanceView::onHand)
                .sum());
    }

    private void attempt(Runnable action) {
        failure = null;
        try {
            action.run();
        } catch (RuntimeException e) {
            failure = e;
        }
    }

    private void rawSql(Supplier<Integer> statement) {
        databaseFailure = null;
        try {
            inTransaction(statement);
        } catch (RuntimeException e) {
            databaseFailure = e;
        }
    }

    private UUID crossDockUnit(String asnNo) {
        UUID unitId = asn(asnNo).handlingUnitId();
        assertThat(unitId).as("ASN %s was not cross-docked", asnNo).isNotNull();
        return unitId;
    }

    private AsnView asn(String asnNo) {
        return inTransaction(() -> queries.asn(asnId(asnNo))
                .orElseThrow(() -> new AssertionError("No ASN " + asnNo)));
    }

    private OutboundOrderView order(String orderNo) {
        return inTransaction(() -> queries.outboundOrder(orderId(orderNo))
                .orElseThrow(() -> new AssertionError("No outbound order " + orderNo)));
    }

    private HandlingUnitView handlingUnit(UUID id) {
        return inTransaction(() -> queries.handlingUnit(id)
                .orElseThrow(() -> new AssertionError("No handling unit " + id)));
    }

    private UUID warehouseId(String code) {
        return world.ref("warehouse:" + code);
    }

    private UUID binId(String code) {
        return world.ref("bin:" + code);
    }

    private UUID skuId(String code) {
        return world.ref("sku:" + code);
    }

    private UUID asnId(String asnNo) {
        return world.ref("asn:" + asnNo);
    }

    private UUID orderId(String orderNo) {
        return world.ref("outbound:" + orderNo);
    }

    private <T> T inTransaction(Supplier<T> action) {
        // The scenario's scope is already bound by "the tenant scope is ...",
        // so the transaction opens inside it, as AGENTS.md requires.
        TenantContext.requireTenantId();
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }
}
