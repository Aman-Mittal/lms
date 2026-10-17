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
package com.lms.web;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import com.lms.acceptance.TestFixtures;
import com.lms.client.ApiClient;
import com.lms.client.api.WarehouseApi;
import com.lms.client.model.AdjustStockRequest;
import com.lms.client.model.Asn;
import com.lms.client.model.AsnExpectedLine;
import com.lms.client.model.AsnLine;
import com.lms.client.model.BinSlice;
import com.lms.client.model.CreateAsnRequest;
import com.lms.client.model.CreateBinRequest;
import com.lms.client.model.CreateOutboundOrderRequest;
import com.lms.client.model.CreateSkuRequest;
import com.lms.client.model.DispatchRequest;
import com.lms.client.model.HandlingUnit;
import com.lms.client.model.OutboundOrder;
import com.lms.client.model.OutboundOrderLineRequest;
import com.lms.client.model.PutawayRequest;
import com.lms.client.model.ReceiptCount;
import com.lms.client.model.ReceiveAsnRequest;
import com.lms.client.model.StockBalance;
import com.lms.client.model.StockBalanceSlice;
import com.lms.client.model.StockMovement;
import com.lms.client.model.StockMovementSlice;
import com.lms.identity.command.domain.AppUser;
import com.lms.identity.security.TokenService;
import com.lms.masterdata.command.RegisterTerminalCommand;
import com.lms.masterdata.command.TerminalCommandService;
import com.lms.masterdata.command.domain.Terminal;
import com.lms.shared.geo.LatLon;
import com.lms.shared.tenant.TenantContext;
import feign.FeignException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Warehousing over HTTP, through the client generated from the contract.
 *
 * <p>Only the warehouse terminal is created in-process -- terminals have their
 * own endpoints and their own tests. Everything the warehouse itself does, from
 * the first SKU to the hand-off, crosses the wire, so a schema that disagrees
 * with what the server sends fails here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WarehouseApiTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.7-alpine")
            .withDatabaseName("lms").withUsername("lms").withPassword("lms");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final List<String> WAREHOUSE = List.of(
            "WAREHOUSE_READ", "WAREHOUSE_SETUP", "WAREHOUSE_OPERATE", "INVENTORY_ADJUST");

    @LocalServerPort
    private int port;

    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private TerminalCommandService terminals;
    @Autowired
    private TokenService tokens;

    private String salt;
    private String orgPath;
    private AppUser operator;
    private UUID warehouseId;

    @BeforeEach
    void aWarehouse() {
        salt = UUID.randomUUID().toString().substring(0, 8);
        orgPath = "/acme-" + salt + "/";
        UUID tenantId = fixtures.createTenant("acme-" + salt, "Acme Logistics");
        UUID orgUnitId = fixtures.createOrgUnit(tenantId, orgPath, "Acme HQ", "HQ", null);
        operator = user(tenantId, orgUnitId, "operator@acme.test");

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("setup", "n/a",
                        Set.of(new SimpleGrantedAuthority("TERMINAL_CREATE"))));
        try {
            warehouseId = TenantContext.callWith(tenantId, orgPath, (Supplier<UUID>) () ->
                    terminals.register(new RegisterTerminalCommand.PointRadius(orgUnitId,
                            "WH-" + salt, "Bhiwandi DC", Terminal.FunctionalCategory.WAREHOUSE,
                            new LatLon(19.3, 73.05), 400, 4, null, null, 45, List.of())));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("receipt with variance, putaway, short pick, pack and hand-off through the generated client")
    void receiptToHandOff() {
        WarehouseApi api = client(WAREHOUSE);
        UUID sku = api.createSku(new CreateSkuRequest().code("SKU-" + salt)
                .description("Widgets"), null).getId();
        UUID bin = api.createBin(warehouseId, new CreateBinRequest().code("A-01")
                .capacityUnits(50), null).getId();

        UUID asnId = api.createAsn(new CreateAsnRequest().warehouseId(warehouseId)
                .asnNo("ASN-" + salt)
                .lines(List.of(new AsnExpectedLine().skuId(sku).quantity(100))), null).getId();
        api.receiveAsn(asnId, new ReceiveAsnRequest().lines(List.of(
                new ReceiptCount().lineNo(1).receivedQty(90).damagedQty(5))), null);

        Asn asn = api.getAsn(asnId);
        assertThat(asn.getStatus()).isEqualTo(Asn.StatusEnum.RECEIVED);
        AsnLine line = asn.getLines().get(0);
        assertThat(line.getShortQty()).isEqualTo(10);
        assertThat(line.getExcessQty()).isZero();
        assertThat(line.getDamagedQty()).isEqualTo(5);

        // 85 good units on the dock; the bin takes 50.
        assertStatus(() -> api.putaway(new PutawayRequest().binId(bin).skuId(sku).quantity(60),
                null), 409, "bin-over-capacity");
        api.putaway(new PutawayRequest().binId(bin).skuId(sku).quantity(50), null);

        StockBalanceSlice stock = api.listStock(warehouseId, sku, null, null, null);
        assertThat(stock.getItems())
                .extracting(StockBalance::getLocationType, StockBalance::getLocationCode,
                        StockBalance::getOnHand)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                StockBalance.LocationTypeEnum.DOCK, null, 35),
                        org.assertj.core.groups.Tuple.tuple(
                                StockBalance.LocationTypeEnum.BIN, "A-01", 50));
        BinSlice bins = api.listBins(warehouseId, null, null);
        assertThat(bins.getItems().get(0).getOccupiedUnits()).isEqualTo(50);

        UUID orderId = api.createOutboundOrder(new CreateOutboundOrderRequest()
                .warehouseId(warehouseId).orderNo("OUT-" + salt).shipTo("Pune DC")
                .lines(List.of(new OutboundOrderLineRequest().skuId(sku).quantity(60))), null)
                .getId();
        api.pickOutboundOrder(orderId, null);
        OutboundOrder picked = api.getOutboundOrder(orderId);
        assertThat(picked.getStatus()).isEqualTo(OutboundOrder.StatusEnum.SHORT_PICKED);
        assertThat(picked.getLines().get(0).getPickedQty()).isEqualTo(50);
        assertThat(picked.getLines().get(0).getShortQty()).isEqualTo(10);

        UUID unitId = api.packOutboundOrder(orderId, null).getId();
        HandlingUnit packed = api.getHandlingUnit(unitId);
        assertThat(packed.getStatus()).isEqualTo(HandlingUnit.StatusEnum.PACKED);
        assertThat(packed.getSource()).isEqualTo(HandlingUnit.SourceEnum.PACK);
        assertThat(packed.getContents()).singleElement()
                .satisfies(content -> assertThat(content.getQuantity()).isEqualTo(50));

        api.dispatchHandlingUnit(unitId, null, new DispatchRequest().reference("LOAD-1"));
        HandlingUnit dispatched = api.getHandlingUnit(unitId);
        assertThat(dispatched.getStatus()).isEqualTo(HandlingUnit.StatusEnum.DISPATCHED);
        assertThat(dispatched.getDispatchRef()).isEqualTo("LOAD-1");
        assertThat(dispatched.getContents()).isEmpty();
        assertThat(api.getOutboundOrder(orderId).getStatus())
                .isEqualTo(OutboundOrder.StatusEnum.DISPATCHED);

        // The ledger pages newest first and accounts for every unit: what is
        // left is exactly the 35 still on the dock.
        List<StockMovement> ledger = new java.util.ArrayList<>();
        String cursor = null;
        do {
            StockMovementSlice page = api.listStockMovements(sku, cursor, 3);
            ledger.addAll(page.getItems());
            cursor = page.getNextCursor();
        } while (cursor != null);
        assertThat(ledger.stream().mapToInt(StockMovement::getQuantity).sum()).isEqualTo(35);
        assertThat(ledger).extracting(StockMovement::getMovementType)
                .contains(StockMovement.MovementTypeEnum.RECEIPT,
                        StockMovement.MovementTypeEnum.PUTAWAY,
                        StockMovement.MovementTypeEnum.PICK,
                        StockMovement.MovementTypeEnum.PACK,
                        StockMovement.MovementTypeEnum.DISPATCH);
    }

    @Test
    @DisplayName("a cross-dock never touches a bin, and adjustments cannot make stock negative")
    void crossDockAndAdjustment() {
        WarehouseApi api = client(WAREHOUSE);
        UUID sku = api.createSku(new CreateSkuRequest().code("SKU-" + salt)
                .description("Gadgets"), null).getId();
        UUID bin = api.createBin(warehouseId, new CreateBinRequest().code("B-01")
                .capacityUnits(100), null).getId();

        UUID asnId = api.createAsn(new CreateAsnRequest().warehouseId(warehouseId)
                .asnNo("XD-" + salt)
                .lines(List.of(new AsnExpectedLine().skuId(sku).quantity(20))), null).getId();
        api.receiveAsn(asnId, new ReceiveAsnRequest().crossDock(true).lines(List.of(
                new ReceiptCount().lineNo(1).receivedQty(20).damagedQty(0))), null);

        Asn asn = api.getAsn(asnId);
        assertThat(asn.getCrossDock()).isTrue();
        HandlingUnit unit = api.getHandlingUnit(asn.getHandlingUnitId());
        assertThat(unit.getSource()).isEqualTo(HandlingUnit.SourceEnum.CROSS_DOCK);
        assertThat(unit.getContents()).singleElement()
                .satisfies(content -> assertThat(content.getQuantity()).isEqualTo(20));
        assertThat(api.listStock(warehouseId, null, bin, null, null).getItems()).isEmpty();

        assertStatus(() -> api.adjustStock(new AdjustStockRequest().binId(bin).skuId(sku)
                .quantity(-1).reason("Counted"), null), 409, "stock-cannot-go-negative");
        UUID movement = api.adjustStock(new AdjustStockRequest().binId(bin).skuId(sku)
                .quantity(3).reason("Found behind the racking"), null).getId();
        assertThat(movement).isNotNull();
        assertThat(api.listStock(warehouseId, sku, bin, null, null).getItems())
                .extracting(StockBalance::getOnHand).containsExactly(3);
    }

    @Test
    @DisplayName("refusals arrive as the documented statuses")
    void refusals() {
        WarehouseApi api = client(WAREHOUSE);
        // Validation before the domain.
        assertStatus(() -> api.createBin(warehouseId, new CreateBinRequest().code("Z")
                .capacityUnits(0), null), 400, null);
        // Unknown things are 404s, not empty bodies.
        assertStatus(() -> api.getAsn(UUID.randomUUID()), 404, null);
        assertStatus(() -> api.createBin(UUID.randomUUID(), new CreateBinRequest().code("Z")
                .capacityUnits(5), null), 404, null);
        // Reading is not adjusting.
        WarehouseApi readOnly = client(List.of("WAREHOUSE_READ", "WAREHOUSE_OPERATE"));
        assertStatus(() -> readOnly.adjustStock(new AdjustStockRequest().binId(UUID.randomUUID())
                .skuId(UUID.randomUUID()).quantity(1).reason("x"), null), 403, null);
        assertStatus(() -> readOnly.createSku(new CreateSkuRequest().code("X")
                .description("x"), null), 403, null);
    }

    @Test
    @DisplayName("a retried receipt with the same idempotency key counts the goods once")
    void idempotentReceipt() {
        WarehouseApi api = client(WAREHOUSE);
        UUID sku = api.createSku(new CreateSkuRequest().code("SKU-" + salt)
                .description("Widgets"), null).getId();
        UUID asnId = api.createAsn(new CreateAsnRequest().warehouseId(warehouseId)
                .asnNo("ASN-" + salt)
                .lines(List.of(new AsnExpectedLine().skuId(sku).quantity(10))), null).getId();
        ReceiveAsnRequest count = new ReceiveAsnRequest().lines(List.of(
                new ReceiptCount().lineNo(1).receivedQty(10).damagedQty(0)));
        String key = "receive-" + UUID.randomUUID();

        api.receiveAsn(asnId, count, key);
        api.receiveAsn(asnId, count, key);

        assertThat(api.listStock(warehouseId, sku, null, null, null).getItems())
                .extracting(StockBalance::getOnHand).containsExactly(10);
        // Without the key, a second count is the business error it is.
        assertStatus(() -> api.receiveAsn(asnId, count, null), 409, "asn-already-received");
    }

    // --------------------------------------------------------------- helpers

    private static void assertStatus(Runnable call, int status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(FeignException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            if (code != null) {
                assertThat(e.contentUTF8()).contains(code);
            }
        });
    }

    private WarehouseApi client(List<String> permissions) {
        String token = tokens.issueAccessToken(operator, orgPath, permissions).value();
        ApiClient apiClient = new ApiClient().setBasePath("http://localhost:" + port);
        apiClient.getFeignBuilder().requestInterceptor(template ->
                template.header("Authorization", "Bearer " + token));
        return apiClient.buildClient(WarehouseApi.class);
    }

    private AppUser user(UUID tenantId, UUID orgUnitId, String email) {
        UUID id = fixtures.createUser(tenantId, orgUnitId, orgPath, email, "not-used-here");
        return new AppUser(id, tenantId, orgUnitId, email, "not-used", email,
                AppUser.UserStatus.ACTIVE, null, 0, null, null, null, Instant.now(), Instant.now());
    }
}
