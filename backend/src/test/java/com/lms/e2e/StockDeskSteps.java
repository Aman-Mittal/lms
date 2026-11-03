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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.client.api.WarehouseApi;
import com.lms.client.model.AdjustStockRequest;
import com.lms.client.model.AsnExpectedLine;
import com.lms.client.model.AsnSummary;
import com.lms.client.model.AuthResult;
import com.lms.client.model.Bin;
import com.lms.client.model.CreateAsnRequest;
import com.lms.client.model.CreateBinRequest;
import com.lms.client.model.CreateOutboundOrderRequest;
import com.lms.client.model.CreateSkuRequest;
import com.lms.client.model.DispatchRequest;
import com.lms.client.model.HandlingUnit;
import com.lms.client.model.HandlingUnitSummary;
import com.lms.client.model.OutboundOrderLineRequest;
import com.lms.client.model.PutawayRequest;
import com.lms.client.model.ReceiptCount;
import com.lms.client.model.ReceiveAsnRequest;
import com.lms.client.model.RegisterPartnerRequest;
import com.lms.client.model.RegisterPointRadiusTerminalRequest;
import com.lms.client.model.ReleaseQuarantineRequest;
import com.lms.client.model.Sku;
import com.lms.client.model.StockBalance;
import com.lms.client.model.StockMovement;
import com.lms.client.model.StockMovementSlice;
import com.lms.client.model.WriteOffQuarantineRequest;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

import static com.lms.e2e.MoneyStockSupport.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Warehouse stock reads and corrections over HTTP: balances by location,
 * the movement ledger and its paging, adjustments after a count, quarantined
 * units released or written off, handling units, and who may do any of it.
 *
 * <p>The warehouse, its bins and its SKUs are the scenario's own, so every
 * count asserted here is exact: nothing else puts stock in this warehouse.
 * Latitude band 45 degrees north.
 */
public class StockDeskSteps {

    private static final String LOT = "LOT-1";

    @Value("${local.server.port}")
    private int port;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private HttpWorld world;
    private MoneyStockSupport support;
    private UUID warehouse;
    private UUID site;
    private UUID owner;
    private UUID consignee;
    private final Map<String, UUID> bins = new HashMap<>();
    private final List<UUID> skus = new ArrayList<>();
    private final List<UUID> asns = new ArrayList<>();
    private final List<UUID> units = new ArrayList<>();
    private final Map<UUID, UUID> orderOfUnit = new HashMap<>();
    private final List<UUID> adjustments = new ArrayList<>();
    private UUID releaseMovement;
    private UUID writeOffMovement;
    private String lastReason;
    private String dispatchRef;
    private AuthResult reader;
    private AuthResult other;

    private WarehouseApi warehouses() {
        return world.api(WarehouseApi.class);
    }

    private UUID bin(String name) {
        return bins.get(name);
    }

    private UUID first() {
        return skus.get(0);
    }

    // ------------------------------------------------------------------ set up

    @Given("the stock desk is signed in as the demo administrator")
    public void signIn() {
        world = new HttpWorld(port);
        world.signInAsDemoAdministrator();
        support = new MoneyStockSupport(world, port, jdbc, passwordEncoder, transactionManager);
    }

    @Given("the stock desk has a warehouse with bins {string} of {int} units, {string} of {int} units and {string} of {int} units")
    public void warehouse(String a, int capacityA, String b, int capacityB, String c,
                          int capacityC) {
        warehouse = world.terminal(RegisterPointRadiusTerminalRequest.CategoryEnum.WAREHOUSE,
                "MS-WH", 45.10, 10.10);
        site = world.terminal(RegisterPointRadiusTerminalRequest.CategoryEnum.CUSTOMER_SITE,
                "MS-SITE", 45.30, 10.30);
        owner = world.partner("MSO", RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER);
        consignee = world.partner("MSN", RegisterPartnerRequest.PartnerTypeEnum.CONSIGNEE);
        bins.put(a, warehouses().createBin(warehouse, new CreateBinRequest()
                .code(world.code("BIN-" + a)).capacityUnits(capacityA), null).getId());
        bins.put(b, warehouses().createBin(warehouse, new CreateBinRequest()
                .code(world.code("BIN-" + b)).capacityUnits(capacityB), null).getId());
        bins.put(c, warehouses().createBin(warehouse, new CreateBinRequest()
                .code(world.code("BIN-" + c)).capacityUnits(capacityC), null).getId());
    }

    @Given("the stock desk has {int} stock items owned by one customer")
    public void items(int count) {
        for (int i = 1; i <= count; i++) {
            skus.add(warehouses().createSku(new CreateSkuRequest()
                    .code(skuCode(i)).description("Money stock item " + i)
                    .ownerPartnerId(owner).uom("EA"), null).getId());
        }
    }

    /** Salt first, so the codes of one scenario sort together and apart from every other's. */
    private String skuCode(int number) {
        return world.salt + "-MS-" + number;
    }

    @Given("the stock desk receives {int} units of the first item with {int} damaged on a fresh notice")
    @When("the stock desk receives {int} units of the first item with {int} damaged on another notice")
    public void receive(int units, int damaged) {
        UUID asnId = notice(units);
        count(asnId, units, damaged);
    }

    @Given("the stock desk puts {int} units of the first item away into bin {string}")
    @When("the stock desk puts {int} more units of the first item away into bin {string}")
    public void putAway(int quantity, String bin) {
        warehouses().putaway(new PutawayRequest().binId(bin(bin)).skuId(first()).lotNo(LOT)
                .quantity(quantity), null);
    }

    private UUID notice(int expected) {
        UUID asnId = warehouses().createAsn(new CreateAsnRequest()
                .warehouseId(warehouse)
                .asnNo(world.code("ASN-" + (asns.size() + 1)))
                .supplierPartnerId(consignee)
                .lines(List.of(new AsnExpectedLine().skuId(first()).lotNo(LOT).quantity(expected))),
                null).getId();
        asns.add(asnId);
        return asnId;
    }

    private void count(UUID asnId, int received, int damaged) {
        warehouses().receiveAsn(asnId, new ReceiveAsnRequest()
                .lines(List.of(new ReceiptCount().lineNo(1).receivedQty(received)
                        .damagedQty(damaged)))
                .crossDock(false), null);
    }

    // ------------------------------------------------------------ bins and SKUs

    @Then("the stock desk lists bin {string} holding {int} of {int} and bin {string} holding {int} of {int}")
    public void binsHold(String a, int heldA, int capacityA, String b, int heldB, int capacityB) {
        List<Bin> listed = warehouses().listBins(warehouse, null, 50).getItems();
        assertThat(listed).extracting(Bin::getId).contains(bin(a), bin(b));
        assertThat(listed).filteredOn(x -> x.getId().equals(bin(a))).singleElement()
                .satisfies(x -> {
                    assertThat(x.getOccupiedUnits()).isEqualTo(heldA);
                    assertThat(x.getCapacityUnits()).isEqualTo(capacityA);
                    assertThat(x.getWarehouseId()).isEqualTo(warehouse);
                    assertThat(x.getCode()).isEqualTo(world.code("BIN-" + a));
                });
        assertThat(listed).filteredOn(x -> x.getId().equals(bin(b))).singleElement()
                .satisfies(x -> {
                    assertThat(x.getOccupiedUnits()).isEqualTo(heldB);
                    assertThat(x.getCapacityUnits()).isEqualTo(capacityB);
                });
    }

    @Then("the stock desk pages the bins two at a time by code, ending with bin {string}")
    public void pageBins(String last) {
        var page1 = warehouses().listBins(warehouse, null, 2);
        assertThat(page1.getItems()).extracting(Bin::getCode)
                .containsExactly(world.code("BIN-A"), world.code("BIN-B"));
        assertThat(page1.getHasMore()).isTrue();
        assertThat(page1.getNextCursor()).isNotBlank();
        var page2 = warehouses().listBins(warehouse, page1.getNextCursor(), 2);
        assertThat(page2.getItems()).extracting(Bin::getId).containsExactly(bin(last));
        assertThat(page2.getHasMore()).isFalse();
    }

    @Then("the stock desk lists its stock items by code and pages them two at a time")
    public void pageSkus() {
        String before = world.salt + "-MS-0";
        var page1 = warehouses().listSkus(before, 2);
        assertThat(page1.getItems()).extracting(Sku::getCode)
                .containsExactly(skuCode(1), skuCode(2));
        assertThat(page1.getItems().get(0)).satisfies(sku -> {
            assertThat(sku.getId()).isEqualTo(skus.get(0));
            assertThat(sku.getOwnerPartnerId()).isEqualTo(owner);
            assertThat(sku.getUom()).isEqualTo("EA");
            assertThat(sku.getDescription()).isEqualTo("Money stock item 1");
        });
        assertThat(page1.getHasMore()).isTrue();
        assertThat(page1.getNextCursor()).isEqualTo(skuCode(2));
        var page2 = warehouses().listSkus(page1.getNextCursor(), 2);
        assertThat(page2.getItems().get(0).getCode()).isEqualTo(skuCode(3));
        assertThat(page2.getItems()).extracting(Sku::getCode)
                .doesNotContain(skuCode(1), skuCode(2));
    }

    // ------------------------------------------------------------- notices

    @Given("the stock desk has also filed a notice for {int} more units that has not arrived")
    public void pendingNotice(int expected) {
        notice(expected);
    }

    @Then("the stock desk lists the two notices newest first with the unreceived one leading")
    public void noticesNewestFirst() {
        List<AsnSummary> listed = warehouses().listAsns(warehouse, null, null, 50).getItems();
        assertThat(listed).extracting(AsnSummary::getId)
                .containsExactly(asns.get(1), asns.get(0));
        assertThat(listed.get(0)).satisfies(asn -> {
            assertThat(asn.getStatus().getValue()).isEqualTo("EXPECTED");
            assertThat(asn.getReceivedAt()).isNull();
            assertThat(asn.getAsnNo()).isEqualTo(world.code("ASN-2"));
            assertThat(asn.getWarehouseId()).isEqualTo(warehouse);
            assertThat(asn.getSupplierPartnerId()).isEqualTo(consignee);
            assertThat(asn.getCrossDock()).isFalse();
        });
        assertThat(listed.get(1)).satisfies(asn -> {
            assertThat(asn.getStatus().getValue()).isEqualTo("RECEIVED");
            assertThat(asn.getReceivedAt()).isNotNull();
            assertThat(asn.getAsnNo()).isEqualTo(world.code("ASN-1"));
        });
    }

    @Then("the stock desk lists only the received notice when asked for received ones and only the other when asked for expected ones")
    public void noticesByStatus() {
        assertThat(warehouses().listAsns(warehouse, "RECEIVED", null, 50).getItems())
                .extracting(AsnSummary::getId).containsExactly(asns.get(0));
        assertThat(warehouses().listAsns(warehouse, "EXPECTED", null, 50).getItems())
                .extracting(AsnSummary::getId).containsExactly(asns.get(1));
    }

    @Then("the stock desk pages the notices one at a time and the second page holds the older notice")
    public void noticePages() {
        var page1 = warehouses().listAsns(warehouse, null, null, 1);
        assertThat(page1.getItems()).extracting(AsnSummary::getId).containsExactly(asns.get(1));
        assertThat(page1.getHasMore()).isTrue();
        var page2 = warehouses().listAsns(warehouse, null, page1.getNextCursor(), 1);
        assertThat(page2.getItems()).extracting(AsnSummary::getId).containsExactly(asns.get(0));
        assertThat(page2.getHasMore()).isFalse();
    }

    // ------------------------------------------------------------- balances

    @Then("the stock desk lists the first item as {int} in bin {string}, {int} on the dock and {int} in quarantine")
    public void balances(int inBin, String bin, int onDock, int quarantined) {
        List<StockBalance> listed = warehouses()
                .listStock(warehouse, first(), null, null, 200).getItems();
        assertThat(listed).allSatisfy(b -> {
            assertThat(b.getSkuId()).isEqualTo(first());
            assertThat(b.getLotNo()).isEqualTo(LOT);
            assertThat(b.getWarehouseId()).isEqualTo(warehouse);
        });
        assertThat(onHand(listed, StockBalance.LocationTypeEnum.BIN, bin(bin))).isEqualTo(inBin);
        assertThat(onHand(listed, StockBalance.LocationTypeEnum.DOCK, warehouse)).isEqualTo(onDock);
        assertThat(onHand(listed, StockBalance.LocationTypeEnum.QUARANTINE, warehouse))
                .isEqualTo(quarantined);
        listed.stream().filter(b -> b.getLocationType() == StockBalance.LocationTypeEnum.BIN)
                .forEach(b -> assertThat(b.getLocationCode()).isEqualTo(world.code("BIN-" + bin)));
    }

    @Then("asked for bin {string} alone the stock desk lists one balance of {int} units")
    public void balanceOfBin(String bin, int units) {
        assertThat(warehouses().listStock(warehouse, null, bin(bin), null, 200).getItems())
                .singleElement().satisfies(b -> {
                    assertThat(b.getLocationType()).isEqualTo(StockBalance.LocationTypeEnum.BIN);
                    assertThat(b.getLocationId()).isEqualTo(bin(bin));
                    assertThat(b.getOnHand()).isEqualTo(units);
                });
    }

    @Then("asked for the second item the stock desk lists no balances")
    public void secondItemEmpty() {
        assertThat(warehouses().listStock(warehouse, skus.get(1), null, null, 200).getItems())
                .isEmpty();
    }

    @Then("the stock desk lists no quarantine balance for the first item")
    public void noQuarantine() {
        assertThat(warehouses().listStock(warehouse, first(), null, null, 200).getItems())
                .noneMatch(b -> b.getLocationType() == StockBalance.LocationTypeEnum.QUARANTINE);
    }

    private static int onHand(List<StockBalance> listed, StockBalance.LocationTypeEnum type,
                              UUID location) {
        return listed.stream()
                .filter(b -> b.getLocationType() == type && location.equals(b.getLocationId()))
                .mapToInt(StockBalance::getOnHand).sum();
    }

    // ---------------------------------------------------------- adjustments

    @When("the stock desk adjusts bin {string} by {int} because {string}")
    public void adjust(String bin, int quantity, String reason) {
        lastReason = reason;
        adjustments.add(warehouses().adjustStock(new AdjustStockRequest().binId(bin(bin))
                .skuId(first()).lotNo(LOT).quantity(quantity).reason(reason), null).getId());
    }

    @Then("the ledger of the first item leads with an adjustment of {int} in bin {string} carrying the reason, made by the signed-in user")
    public void ledgerLeadsWithAdjustment(int quantity, String bin) {
        StockMovement latest = warehouses().listStockMovements(first(), null, 5).getItems().get(0);
        assertThat(latest.getId()).isEqualTo(adjustments.get(adjustments.size() - 1));
        assertThat(latest.getMovementType()).isEqualTo(StockMovement.MovementTypeEnum.ADJUST);
        assertThat(latest.getQuantity()).isEqualTo(quantity);
        assertThat(latest.getLocationType()).isEqualTo(StockMovement.LocationTypeEnum.BIN);
        assertThat(latest.getLocationId()).isEqualTo(bin(bin));
        assertThat(latest.getReason()).isEqualTo(lastReason);
        assertThat(latest.getActor()).isEqualTo(world.userId().toString());
        assertThat(latest.getLotNo()).isEqualTo(LOT);
        assertThat(latest.getWarehouseId()).isEqualTo(warehouse);
        assertThat(latest.getOccurredAt()).isNotNull();
    }

    @Then("the ledger of the first item holds {int} adjustments and the earlier receipt rows are untouched")
    public void ledgerKeepsHistory(int count) {
        List<StockMovement> all = allMovements();
        assertThat(all).filteredOn(m -> m.getMovementType() == StockMovement.MovementTypeEnum.ADJUST)
                .hasSize(count).extracting(StockMovement::getId)
                .containsExactlyInAnyOrderElementsOf(adjustments);
        assertThat(all).filteredOn(m -> m.getMovementType() == StockMovement.MovementTypeEnum.RECEIPT)
                .extracting(StockMovement::getQuantity).containsExactlyInAnyOrder(90, 10);
    }

    @Then("adjusting bin {string} by {int} because {string} is refused with {int} and code {string}")
    public void adjustRefused(String bin, int quantity, String reason, int status, String code) {
        assertProblem(() -> warehouses().adjustStock(new AdjustStockRequest().binId(bin(bin))
                .skuId(first()).lotNo(LOT).quantity(quantity).reason(reason), null), status, code);
    }

    @Then("adjusting an unknown stock item in bin {string} is refused as not found")
    public void adjustUnknownSku(String bin) {
        assertProblem(() -> warehouses().adjustStock(new AdjustStockRequest().binId(bin(bin))
                .skuId(UUID.randomUUID()).lotNo(LOT).quantity(1).reason("Unknown item"), null),
                404, "not-found");
    }

    @Then("adjusting an unknown bin is refused as not found")
    public void adjustUnknownBin() {
        assertProblem(() -> warehouses().adjustStock(new AdjustStockRequest()
                .binId(UUID.randomUUID()).skuId(first()).lotNo(LOT).quantity(1)
                .reason("Unknown bin"), null), 404, "not-found");
    }

    @Then("the stock desk can read bin {string} as still holding {int} units")
    public void binStillHolds(String bin, int units) {
        assertThat(warehouses().listBins(warehouse, null, 50).getItems())
                .filteredOn(b -> b.getId().equals(bin(bin))).singleElement()
                .satisfies(b -> assertThat(b.getOccupiedUnits()).isEqualTo(units));
    }

    // ------------------------------------------------------------ the ledger

    @Then("the ledger of the first item pages two rows at a time, newest first, and holds {int} rows in all")
    public void ledgerPages(int total) {
        StockMovementSlice page1 = warehouses().listStockMovements(first(), null, 2);
        assertThat(page1.getItems()).hasSize(2);
        assertThat(page1.getHasMore()).isTrue();
        assertThat(page1.getNextCursor()).isNotBlank();
        StockMovementSlice page2 = warehouses().listStockMovements(first(),
                page1.getNextCursor(), 2);
        assertThat(page2.getItems()).hasSize(2);
        assertThat(page2.getItems()).extracting(StockMovement::getId)
                .doesNotContainAnyElementsOf(page1.getItems().stream()
                        .map(StockMovement::getId).toList());
        assertThat(page1.getItems().get(1).getOccurredAt())
                .isAfterOrEqualTo(page2.getItems().get(0).getOccurredAt());

        List<StockMovement> all = allMovements();
        assertThat(all).hasSize(total);
        assertThat(all).extracting(StockMovement::getId).doesNotHaveDuplicates();
        for (int i = 1; i < all.size(); i++) {
            assertThat(all.get(i - 1).getOccurredAt())
                    .isAfterOrEqualTo(all.get(i).getOccurredAt());
        }
        assertThat(all).extracting(StockMovement::getId).containsAll(adjustments);
    }

    @Then("the ledger of the second item is empty")
    public void secondLedgerEmpty() {
        assertThat(warehouses().listStockMovements(skus.get(1), null, 50).getItems()).isEmpty();
    }

    private List<StockMovement> allMovements() {
        List<StockMovement> all = new ArrayList<>();
        String cursor = null;
        for (int guard = 0; guard < 50; guard++) {
            StockMovementSlice page = warehouses().listStockMovements(first(), cursor, 2);
            all.addAll(page.getItems());
            if (!Boolean.TRUE.equals(page.getHasMore())) {
                return all;
            }
            cursor = page.getNextCursor();
        }
        throw new AssertionError("the ledger never ended");
    }

    // ------------------------------------------------------------ quarantine

    @When("the stock desk releases {int} quarantined units of the first item into bin {string} because {string}")
    public void release(int quantity, String bin, String reason) {
        lastReason = reason;
        releaseMovement = warehouses().releaseQuarantinedStock(new ReleaseQuarantineRequest()
                .binId(bin(bin)).skuId(first()).lotNo(LOT).quantity(quantity).reason(reason),
                null).getId();
    }

    @When("the stock desk writes {int} quarantined units of the first item off because {string}")
    public void writeOff(int quantity, String reason) {
        lastReason = reason;
        writeOffMovement = warehouses().writeOffQuarantinedStock(new WriteOffQuarantineRequest()
                .warehouseId(warehouse).skuId(first()).lotNo(LOT).quantity(quantity)
                .reason(reason), null).getId();
    }

    @Then("the release is two adjustments sharing a reference, {int} out of quarantine and {int} into bin {string}, both carrying the reason")
    public void releaseMovements(int out, int in, String bin) {
        List<StockMovement> all = allMovements();
        StockMovement taken = all.stream().filter(m -> m.getId().equals(releaseMovement))
                .findFirst().orElseThrow(() -> new AssertionError("the release movement is not listed"));
        assertThat(taken.getMovementType()).isEqualTo(StockMovement.MovementTypeEnum.ADJUST);
        assertThat(taken.getLocationType()).isEqualTo(StockMovement.LocationTypeEnum.QUARANTINE);
        assertThat(taken.getQuantity()).isEqualTo(-out);
        assertThat(taken.getReason()).isEqualTo(lastReason);
        assertThat(taken.getReferenceId()).isNotNull();
        assertThat(all).filteredOn(m -> taken.getReferenceId().equals(m.getReferenceId())
                        && !m.getId().equals(taken.getId()))
                .singleElement().satisfies(m -> {
                    assertThat(m.getMovementType()).isEqualTo(StockMovement.MovementTypeEnum.ADJUST);
                    assertThat(m.getLocationType()).isEqualTo(StockMovement.LocationTypeEnum.BIN);
                    assertThat(m.getLocationId()).isEqualTo(bin(bin));
                    assertThat(m.getQuantity()).isEqualTo(in);
                    assertThat(m.getReason()).isEqualTo(lastReason);
                });
    }

    @Then("the write-off is one adjustment of {int} out of quarantine carrying the reason and no reference")
    public void writeOffMovementIs(int out) {
        StockMovement written = allMovements().stream()
                .filter(m -> m.getId().equals(writeOffMovement)).findFirst()
                .orElseThrow(() -> new AssertionError("the write-off movement is not listed"));
        assertThat(written.getMovementType()).isEqualTo(StockMovement.MovementTypeEnum.ADJUST);
        assertThat(written.getLocationType()).isEqualTo(StockMovement.LocationTypeEnum.QUARANTINE);
        assertThat(written.getQuantity()).isEqualTo(-out);
        assertThat(written.getReason()).isEqualTo(lastReason);
        assertThat(written.getReferenceId()).isNull();
    }

    @Then("releasing {int} quarantined units into bin {string} because {string} is refused with {int} and code {string}")
    public void releaseRefused(int quantity, String bin, String reason, int status, String code) {
        assertProblem(() -> warehouses().releaseQuarantinedStock(new ReleaseQuarantineRequest()
                .binId(bin(bin)).skuId(first()).lotNo(LOT).quantity(quantity).reason(reason),
                null), status, code);
    }

    @Then("writing {int} quarantined units off because {string} is refused with {int} and code {string}")
    public void writeOffRefused(int quantity, String reason, int status, String code) {
        assertProblem(() -> warehouses().writeOffQuarantinedStock(new WriteOffQuarantineRequest()
                .warehouseId(warehouse).skuId(first()).lotNo(LOT).quantity(quantity)
                .reason(reason), null), status, code);
    }

    @Then("releasing or writing off an unknown stock item is refused as not found")
    public void quarantineUnknownSku() {
        assertProblem(() -> warehouses().releaseQuarantinedStock(new ReleaseQuarantineRequest()
                .binId(bin("A")).skuId(UUID.randomUUID()).lotNo(LOT).quantity(1).reason("Unknown"),
                null), 404, "not-found");
        assertProblem(() -> warehouses().writeOffQuarantinedStock(new WriteOffQuarantineRequest()
                .warehouseId(warehouse).skuId(UUID.randomUUID()).lotNo(LOT).quantity(1)
                .reason("Unknown"), null), 404, "not-found");
    }

    @Then("releasing into an unknown bin is refused as not found")
    public void releaseUnknownBin() {
        assertProblem(() -> warehouses().releaseQuarantinedStock(new ReleaseQuarantineRequest()
                .binId(UUID.randomUUID()).skuId(first()).lotNo(LOT).quantity(1).reason("Unknown"),
                null), 404, "not-found");
    }

    // -------------------------------------------------------- handling units

    @When("the stock desk packs an outbound order of {int} units of the first item")
    public void pack(int quantity) {
        UUID orderId = warehouses().createOutboundOrder(new CreateOutboundOrderRequest()
                .warehouseId(warehouse)
                .orderNo(world.code("OUT-" + (units.size() + 1)))
                .shipTo("Delivery site " + world.salt)
                .lines(List.of(new OutboundOrderLineRequest().skuId(first()).quantity(quantity))),
                null).getId();
        warehouses().pickOutboundOrder(orderId, null);
        UUID unitId = warehouses().packOutboundOrder(orderId, null).getId();
        units.add(unitId);
        orderOfUnit.put(unitId, orderId);
    }

    @Then("the stock desk lists the two packed handling units newest first, made by packing")
    public void packedUnits() {
        List<HandlingUnitSummary> listed = warehouses()
                .listHandlingUnits(warehouse, "PACKED", null, 50).getItems();
        assertThat(listed).extracting(HandlingUnitSummary::getId)
                .containsExactly(units.get(1), units.get(0));
        assertThat(listed).allSatisfy(u -> {
            assertThat(u.getStatus()).isEqualTo(HandlingUnitSummary.StatusEnum.PACKED);
            assertThat(u.getSource()).isEqualTo(HandlingUnitSummary.SourceEnum.PACK);
            assertThat(u.getHuNo()).isNotBlank();
            assertThat(u.getWarehouseId()).isEqualTo(warehouse);
            assertThat(u.getOutboundOrderId()).isEqualTo(orderOfUnit.get(u.getId()));
            assertThat(u.getDispatchedAt()).isNull();
        });
    }

    @Then("the stock desk pages the handling units one at a time and the second page holds the older one")
    public void unitPages() {
        var page1 = warehouses().listHandlingUnits(warehouse, null, null, 1);
        assertThat(page1.getItems()).extracting(HandlingUnitSummary::getId)
                .containsExactly(units.get(1));
        assertThat(page1.getHasMore()).isTrue();
        var page2 = warehouses().listHandlingUnits(warehouse, null, page1.getNextCursor(), 1);
        assertThat(page2.getItems()).extracting(HandlingUnitSummary::getId)
                .containsExactly(units.get(0));
        assertThat(page2.getHasMore()).isFalse();
    }

    @Then("the first handling unit holds {int} units of the first item in its lot")
    public void unitContents(int quantity) {
        HandlingUnit unit = warehouses().getHandlingUnit(units.get(0));
        assertThat(unit.getId()).isEqualTo(units.get(0));
        assertThat(unit.getStatus()).isEqualTo(HandlingUnit.StatusEnum.PACKED);
        assertThat(unit.getOutboundOrderId()).isEqualTo(orderOfUnit.get(units.get(0)));
        assertThat(unit.getContents()).singleElement().satisfies(c -> {
            assertThat(c.getSkuId()).isEqualTo(first());
            assertThat(c.getSkuCode()).isEqualTo(skuCode(1));
            assertThat(c.getLotNo()).isEqualTo(LOT);
            assertThat(c.getQuantity()).isEqualTo(quantity);
        });
    }

    @When("the stock desk dispatches the first handling unit to the delivery site weighing {int} kg")
    public void dispatchUnit(int kilos) {
        dispatchRef = world.code("LOAD");
        warehouses().dispatchHandlingUnit(units.get(0), null, new DispatchRequest()
                .reference(dispatchRef).destinationTerminalId(site)
                .consigneePartnerId(consignee).grossWeightKg(BigDecimal.valueOf(kilos))
                .volumeM3(new BigDecimal("1.5")));
    }

    @Then("the stock desk lists the first handling unit as dispatched and the second as still packed")
    public void unitsByStatus() {
        assertThat(warehouses().listHandlingUnits(warehouse, "DISPATCHED", null, 50).getItems())
                .extracting(HandlingUnitSummary::getId).containsExactly(units.get(0));
        assertThat(warehouses().listHandlingUnits(warehouse, "PACKED", null, 50).getItems())
                .extracting(HandlingUnitSummary::getId).containsExactly(units.get(1));
    }

    @Then("the first handling unit shows its dispatch reference, destination, consignee and {int} kg")
    public void dispatchedUnit(int kilos) {
        HandlingUnit unit = warehouses().getHandlingUnit(units.get(0));
        assertThat(unit.getStatus()).isEqualTo(HandlingUnit.StatusEnum.DISPATCHED);
        assertThat(unit.getDispatchRef()).isEqualTo(dispatchRef);
        assertThat(unit.getDestinationTerminalId()).isEqualTo(site);
        assertThat(unit.getConsigneePartnerId()).isEqualTo(consignee);
        assertThat(unit.getGrossWeightKg()).isEqualByComparingTo(BigDecimal.valueOf(kilos));
        assertThat(unit.getDispatchedAt()).isNotNull();
    }

    @Then("asking for a handling unit that does not exist is refused as not found")
    public void unknownUnit() {
        assertProblem(() -> warehouses().getHandlingUnit(UUID.randomUUID()), 404, "not-found");
    }

    // ------------------------------------------------------ who may do what

    @Given("a stock desk user who may read stock but not adjust it is provisioned")
    public void limitedUsers() {
        reader = support.userWith("reader", "WAREHOUSE_READ");
    }

    @Then("the user who cannot adjust stock is refused every stock correction as forbidden")
    public void readerCannotAdjust() {
        WarehouseApi api = support.as(reader, WarehouseApi.class);
        assertProblem(() -> api.adjustStock(new AdjustStockRequest().binId(bin("A"))
                .skuId(first()).lotNo(LOT).quantity(1).reason("Not allowed"), null),
                403, "access-denied");
        assertProblem(() -> api.releaseQuarantinedStock(new ReleaseQuarantineRequest()
                .binId(bin("A")).skuId(first()).lotNo(LOT).quantity(1).reason("Not allowed"), null),
                403, "access-denied");
        assertProblem(() -> api.writeOffQuarantinedStock(new WriteOffQuarantineRequest()
                .warehouseId(warehouse).skuId(first()).lotNo(LOT).quantity(1)
                .reason("Not allowed"), null), 403, "access-denied");
        assertThat(api.listStock(warehouse, first(), null, null, 50).getItems())
                .as("the same user can read").isNotEmpty();
    }

    @Then("a user who may not read the warehouse is refused every stock listing as forbidden")
    public void noReadNoListing() {
        AuthResult clerk = support.userWith("noread", "CLIENT_BILLING_READ");
        WarehouseApi api = support.as(clerk, WarehouseApi.class);
        assertProblem(() -> api.listBins(warehouse, null, 10), 403, "access-denied");
        assertProblem(() -> api.listSkus(null, 10), 403, "access-denied");
        assertProblem(() -> api.listAsns(warehouse, null, null, 10), 403, "access-denied");
        assertProblem(() -> api.listStock(warehouse, null, null, null, 10), 403, "access-denied");
        assertProblem(() -> api.listStockMovements(first(), null, 10), 403, "access-denied");
        assertProblem(() -> api.listHandlingUnits(warehouse, null, null, 10), 403, "access-denied");
        assertProblem(() -> api.getHandlingUnit(UUID.randomUUID()), 403, "access-denied");
    }

    @Then("a user who may adjust stock but belongs to a branch beside the warehouse's unit is refused every correction as forbidden")
    public void branchUserRefused() {
        UUID branch = support.branchUnit("beside");
        AuthResult clerk = support.userIn(branch, "branch", "INVENTORY_ADJUST", "WAREHOUSE_READ");
        WarehouseApi api = support.as(clerk, WarehouseApi.class);
        assertProblem(() -> api.adjustStock(new AdjustStockRequest().binId(bin("A"))
                .skuId(first()).lotNo(LOT).quantity(1).reason("Outside the unit"), null),
                403, "access-denied");
        assertProblem(() -> api.releaseQuarantinedStock(new ReleaseQuarantineRequest()
                .binId(bin("A")).skuId(first()).lotNo(LOT).quantity(1).reason("Outside the unit"),
                null), 403, "access-denied");
        assertProblem(() -> api.writeOffQuarantinedStock(new WriteOffQuarantineRequest()
                .warehouseId(warehouse).skuId(first()).lotNo(LOT).quantity(1)
                .reason("Outside the unit"), null), 403, "access-denied");
    }

    @Then("the administrator of another tenant sees none of the warehouse's stock, notices, bins or handling units and cannot correct it")
    public void otherTenant() {
        other = support.otherTenantAdministrator();
        WarehouseApi api = support.as(other, WarehouseApi.class);
        assertThat(api.listBins(warehouse, null, 50).getItems()).isEmpty();
        assertThat(api.listAsns(warehouse, null, null, 50).getItems()).isEmpty();
        assertThat(api.listStock(warehouse, null, null, null, 50).getItems()).isEmpty();
        assertThat(api.listStockMovements(first(), null, 50).getItems()).isEmpty();
        assertThat(api.listHandlingUnits(warehouse, null, null, 50).getItems()).isEmpty();
        assertThat(api.listSkus(world.salt + "-MS-0", 50).getItems()).isEmpty();
        assertProblem(() -> api.getHandlingUnit(units.isEmpty() ? UUID.randomUUID() : units.get(0)),
                404, "not-found");
        assertProblem(() -> api.adjustStock(new AdjustStockRequest().binId(bin("A"))
                .skuId(first()).lotNo(LOT).quantity(1).reason("Another tenant's bin"), null),
                404, "not-found");
        assertProblem(() -> api.releaseQuarantinedStock(new ReleaseQuarantineRequest()
                .binId(bin("A")).skuId(first()).lotNo(LOT).quantity(1).reason("Another tenant"),
                null), 404, "not-found");
    }
}
