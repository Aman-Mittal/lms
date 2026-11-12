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
package com.lms.query;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.lms.settings.query.EffectiveSettingsQuery;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that the hot queries are served by indexes.
 *
 * <p>Query plans are invisible in ordinary tests: against a handful of rows a
 * sequential scan is not merely acceptable, it is what the planner will
 * correctly choose, and the query looks fine right up to the point where a
 * tenant has a million rows and a 0.1-CPU instance has to walk all of them.
 *
 * <p>So rather than measuring speed, this inspects the plan directly, with the
 * planner told to prefer indexes where it has the choice. What it is really
 * checking is that a supporting index <em>exists</em> — if none does, no amount
 * of coaxing produces an index scan.
 *
 * <p>When this fails, the fix is a migration adding the index, not a change
 * here.
 */
@SpringBootTest
class QueryPlanTest {

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

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeAll
    static void note() {
        // Documented rather than enforced here: with an empty table the planner
        // rightly prefers a sequential scan, so enable_seqscan is disabled below
        // to reveal whether an index is even available to be chosen.
    }

    @Test
    @DisplayName("user lookup by tenant and email is index-served")
    void userByTenantAndEmail() {
        assertIndexed("""
                SELECT * FROM app_user
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND lower(email) = 'someone@example.test'
                """, "app_user");
    }

    @Test
    @DisplayName("the access policies for one action are index-served")
    void accessPoliciesForAnAction() {
        // Asked by the decision point on every guarded call that has a policy,
        // so it must never become a walk of the tenant's whole rule set.
        assertIndexed("""
                SELECT code, effect, condition::text AS condition
                  FROM access_policy
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND action = 'INVOICE_APPROVE'
                   AND enabled
                 ORDER BY code
                """, "access_policy");
    }

    @Test
    @DisplayName("a vendor-portal user's bill listing is index-served")
    void partnerScopedBillListing() {
        assertIndexed("""
                SELECT * FROM freight_bill
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND vendor_partner_id = '00000000-0000-0000-0000-000000000002'
                   AND bill_no > 'FB-0000'
                 ORDER BY bill_no
                 LIMIT 51
                """, "freight_bill");
    }

    @Test
    @DisplayName("one order's consignments waiting for a load are index-served")
    void unassignedConsignmentsOfAnOrder() {
        // How a large order is put on one vehicle: its consignments are found
        // together, whatever else waits in the tenant's queue.
        assertIndexed("""
                SELECT * FROM consignment
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND order_id = '00000000-0000-0000-0000-000000000002'
                   AND status = 'PLANNED'
                 ORDER BY tracking_ref
                 LIMIT 201
                """, "consignment");
    }

    @Test
    @DisplayName("a vendor-portal user's trip listing is index-served")
    void partnerScopedTripListing() {
        assertIndexed("""
                SELECT * FROM trip
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND vendor_partner_id = '00000000-0000-0000-0000-000000000002'
                   AND trip_no > 'TR-0000'
                 ORDER BY trip_no
                 LIMIT 51
                """, "trip");
    }

    @Test
    @DisplayName("a vendor-portal user's load listing is index-served")
    void partnerScopedLoadListing() {
        assertIndexed("""
                SELECT * FROM load_unit
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND awarded_vendor_partner_id = '00000000-0000-0000-0000-000000000002'
                   AND load_no > 'LD-0000'
                 ORDER BY load_no
                 LIMIT 51
                """, "load_unit");
    }

    @Test
    @DisplayName("the offers addressed to a vendor-portal user are index-served")
    void partnerScopedAllocationListing() {
        // The EXISTS probe the allocation listing adds for a portal vendor.
        String sql = """
                SELECT a.id FROM allocation a
                  JOIN load_unit l ON l.id = a.load_id
                 WHERE a.tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND l.load_no > 'LD-0000'
                   AND EXISTS (SELECT 1 FROM allocation_offer po
                                WHERE po.tenant_id = a.tenant_id
                                  AND po.allocation_id = a.id
                                  AND po.vendor_partner_id = '00000000-0000-0000-0000-000000000002')
                 ORDER BY l.load_no
                 LIMIT 51
                """;
        assertIndexed(sql, "allocation_offer");
    }

    @Test
    @DisplayName("an order is found by its exact number through the unique index")
    void orderByNumber() {
        assertIndexed("""
                SELECT * FROM sales_order
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND order_no = 'ORD-2026-000001'
                """, "sales_order");
    }

    @Test
    @DisplayName("a record's history is index-served and pages without sorting")
    void recordHistoryIsKeysetSeekable() {
        String sql = """
                SELECT a.id, a.action, a.occurred_at
                  FROM audit_log a
                 WHERE a.tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND a.resource_type = 'SalesOrder'
                   AND a.resource_id = '00000000-0000-0000-0000-000000000002'
                   AND (a.occurred_at, a.id) < ('2026-11-04T00:00:00Z'::timestamptz,
                                                '00000000-0000-0000-0000-000000000003'::uuid)
                 ORDER BY a.occurred_at DESC, a.id DESC
                 LIMIT 51
                """;
        assertIndexed(sql, "audit_log");
        assertNoSort(sql);
    }

    @Test
    @DisplayName("a customer-portal user's order listing is index-served")
    void partnerScopedOrderListing() {
        assertIndexed("""
                SELECT * FROM sales_order
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND customer_partner_id = '00000000-0000-0000-0000-000000000002'
                   AND order_no > 'SO-0000'
                 ORDER BY order_no
                 LIMIT 51
                """, "sales_order");
    }

    @Test
    @DisplayName("a customer-portal user's SKU listing is index-served")
    void partnerScopedSkuListing() {
        assertIndexed("""
                SELECT * FROM sku
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND owner_partner_id = '00000000-0000-0000-0000-000000000002'
                   AND code > 'SKU-0000'
                 ORDER BY code
                 LIMIT 51
                """, "sku");
    }

    @Test
    @DisplayName("organisational subtree prefix match is index-served")
    void orgSubtreePrefix() {
        // The reason org_unit_path_prefix_idx uses text_pattern_ops: without it
        // a LIKE 'prefix%' cannot use the index under a non-C collation.
        assertIndexed("""
                SELECT * FROM org_unit
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND path LIKE '/acme/north/' || '%'
                """, "org_unit");
    }

    @Test
    @DisplayName("the circuit a trip is carrying is found by index")
    void circuitRunByTrip() {
        // Runs on every geofence crossing of every trip, most of which carry
        // no circuit at all -- the miss has to be as cheap as the hit.
        assertIndexed("""
                SELECT * FROM circuit_run
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND trip_id = '00000000-0000-0000-0000-000000000002'
                """, "circuit_run");
    }

    @Test
    @DisplayName("the circuit listing pages by day and route without a sort")
    void circuitRunListingIsKeysetSeekable() {
        // The same unique key that makes generation idempotent orders the
        // listing, so the cursor rides it.
        assertIndexed("""
                SELECT * FROM circuit_run
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND run_date >= DATE '2030-01-07'
                   AND (run_date, route_code) > (DATE '2030-01-07', 'MR-NORTH')
                 ORDER BY run_date, route_code
                 LIMIT 50
                """, "circuit_run");
    }

    @Test
    @DisplayName("a circuit's stops are read by index")
    void circuitRunStops() {
        assertIndexed("""
                SELECT * FROM circuit_run_stop
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND run_id = '00000000-0000-0000-0000-000000000002'
                 ORDER BY sequence
                """, "circuit_run_stop");
    }

    @Test
    @DisplayName("the route versions the circuit planner reads are index-served")
    void milkRunRoutesCoveringAWindow() {
        assertIndexed("""
                SELECT * FROM milk_run_route
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND effective_from <= DATE '2030-01-13'
                   AND (effective_to IS NULL OR effective_to >= DATE '2030-01-07')
                 ORDER BY code, effective_from DESC
                """, "milk_run_route");
    }

    @Test
    @DisplayName("the routes in force page by code without a sort")
    void milkRunRouteListingIsKeysetSeekable() {
        assertIndexed("""
                SELECT * FROM milk_run_route
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND effective_to IS NULL
                   AND code > 'MR-NORTH'
                 ORDER BY code
                 LIMIT 50
                """, "milk_run_route");
    }

    @Test
    @DisplayName("a route version's stops are read by index")
    void milkRunRouteStops() {
        assertIndexed("""
                SELECT * FROM milk_run_route_stop
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND route_id = '00000000-0000-0000-0000-000000000002'
                 ORDER BY sequence
                """, "milk_run_route_stop");
    }

    @Test
    @DisplayName("terminal bounding-box candidate filter is index-served")
    void terminalBoundingBox() {
        // This is the substitute for a spatial index (DOCS/adr/0004) and runs on
        // every GPS ping. If it ever degrades to a sequential scan, telematics
        // ingest degrades with it.
        assertIndexed("""
                SELECT * FROM terminal
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND min_lat <= 19.5 AND max_lat >= 19.5
                   AND min_lon <= 72.8 AND max_lon >= 72.8
                """, "terminal");
    }

    @Test
    @DisplayName("the itinerary listing pages newest first without a sort")
    void itineraryListing() {
        assertIndexed("""
                SELECT * FROM itinerary
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND (created_at, id) < (TIMESTAMPTZ '2026-10-01 00:00:00+00',
                                           '00000000-0000-0000-0000-000000000009')
                 ORDER BY created_at DESC, id DESC
                 LIMIT 51
                """, "itinerary");
    }

    @Test
    @DisplayName("an itinerary's legs are index-served in order")
    void itineraryLegs() {
        // Read on every terminal event and every trip event that touches an
        // itinerary, and by each correlated subquery of the listing.
        assertIndexed("""
                SELECT * FROM itinerary_leg
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND itinerary_id = '00000000-0000-0000-0000-000000000002'
                 ORDER BY seq
                """, "itinerary_leg");
    }

    @Test
    @DisplayName("the leg a trip runs is index-served")
    void legByTrip() {
        // Asked on every trip dispatch and completion, almost always to learn
        // that the trip runs no leg at all.
        assertIndexed("""
                SELECT * FROM itinerary_leg
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND trip_id = '00000000-0000-0000-0000-000000000002'
                """, "itinerary_leg");
    }

    @Test
    @DisplayName("the missed-connection sweep's candidates are index-served")
    void legsAwaitingAnOverduePredecessor() {
        // Run every few minutes for every tenant. The partial index holds only
        // legs not yet started and not yet marked, and each predecessor is a
        // point lookup on (itinerary, seq).
        assertIndexed("""
                SELECT n.* FROM itinerary_leg n
                  JOIN itinerary_leg p
                    ON p.tenant_id = n.tenant_id
                   AND p.itinerary_id = n.itinerary_id
                   AND p.seq = n.seq - 1
                 WHERE n.tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND n.departed_at IS NULL
                   AND n.connection_missed_at IS NULL
                   AND n.seq > 1
                   AND p.arrived_at IS NULL
                   AND p.planned_arrival_at + n.transfer_dwell_minutes * INTERVAL '1 minute'
                       < TIMESTAMPTZ '2026-10-01 00:00:00+00'
                   AND n.planned_departure_at < TIMESTAMPTZ '2026-10-01 00:00:00+00'
                       + n.transfer_dwell_minutes * INTERVAL '1 minute'
                 ORDER BY n.planned_departure_at, n.id
                 LIMIT 200
                """, "itinerary_leg");
    }

    @Test
    @DisplayName("the rail rate card in force on a day is index-served")
    void railTariffInForce() {
        // Asked for every rail leg planned without a cost.
        assertIndexed("""
                SELECT * FROM rail_tariff
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND carrier_partner_id = '00000000-0000-0000-0000-000000000002'
                   AND origin_terminal_id = '00000000-0000-0000-0000-000000000003'
                   AND destination_terminal_id = '00000000-0000-0000-0000-000000000004'
                   AND effective_from <= DATE '2026-10-01'
                   AND (effective_to IS NULL OR effective_to >= DATE '2026-10-01')
                 ORDER BY effective_from DESC
                 LIMIT 1
                """, "rail_tariff");
    }

    @Test
    @DisplayName("the rail rate card listing pages newest first without a sort")
    void railTariffListing() {
        assertIndexed("""
                SELECT * FROM rail_tariff
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND (created_at, id) < (TIMESTAMPTZ '2026-10-01 00:00:00+00',
                                           '00000000-0000-0000-0000-000000000009')
                 ORDER BY created_at DESC, id DESC
                 LIMIT 51
                """, "rail_tariff");
    }

    @Test
    @DisplayName("the road rate card listing pages newest first without a sort")
    void tariffListing() {
        assertIndexed("""
                SELECT * FROM tariff
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND (created_at, id) < (TIMESTAMPTZ '2026-10-01 00:00:00+00',
                                           '00000000-0000-0000-0000-000000000009')
                 ORDER BY created_at DESC, id DESC
                 LIMIT 51
                """, "tariff");
    }

    @Test
    @DisplayName("compliance document expiry lookup is index-served")
    void complianceDocumentExpiry() {
        assertIndexed("""
                SELECT * FROM compliance_document
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND owner_type = 'VEHICLE'
                   AND owner_id = '00000000-0000-0000-0000-000000000002'
                """, "compliance_document");
    }

    @Test
    @DisplayName("what is in a bin is index-served")
    void stockInABin() {
        // Asked on every putaway and adjustment to check capacity, and again
        // inside the ledger trigger under a lock on the bin -- so a slow plan
        // here holds that lock for the length of the scan.
        assertIndexed("""
                SELECT coalesce(sum(on_hand), 0) FROM stock_balance
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND location_type = 'BIN'
                   AND location_id = '00000000-0000-0000-0000-000000000002'
                """, "stock_balance");
    }

    @Test
    @DisplayName("where a SKU can be picked from is index-served")
    void pickableStockOfASku() {
        // One probe per outbound line on every pick.
        assertIndexed("""
                SELECT location_id, lot_no, on_hand FROM stock_balance
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND sku_id = '00000000-0000-0000-0000-000000000002'
                   AND warehouse_id = '00000000-0000-0000-0000-000000000003'
                   AND location_type = 'BIN'
                   AND on_hand > 0
                """, "stock_balance");
    }

    @Test
    @DisplayName("the stock screen pages a warehouse without a sort")
    void warehouseStockPage() {
        assertIndexed("""
                SELECT * FROM stock_balance
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND warehouse_id = '00000000-0000-0000-0000-000000000002'
                   AND id > '00000000-0000-0000-0000-000000000009'
                 ORDER BY id
                 LIMIT 51
                """, "stock_balance");
    }

    @Test
    @DisplayName("a SKU's ledger pages newest first without a sort")
    void skuMovementHistory() {
        // The ledger only grows, so this is the table that will be largest in
        // the warehouse schema; the keyset has to ride stock_movement_sku_idx.
        assertIndexed("""
                SELECT * FROM stock_movement
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND sku_id = '00000000-0000-0000-0000-000000000002'
                   AND (occurred_at, id) < (TIMESTAMPTZ '2026-10-01 00:00:00+00',
                                            '00000000-0000-0000-0000-000000000009')
                 ORDER BY occurred_at DESC, id DESC
                 LIMIT 51
                """, "stock_movement");
    }

    @Test
    @DisplayName("a warehouse's ASNs page newest first without a sort")
    void asnListingPage() {
        assertIndexed("""
                SELECT * FROM asn
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND warehouse_id = '00000000-0000-0000-0000-000000000002'
                   AND (created_at, id) < (TIMESTAMPTZ '2026-10-01 00:00:00+00',
                                           '00000000-0000-0000-0000-000000000009')
                 ORDER BY created_at DESC, id DESC
                 LIMIT 51
                """, "asn");
        assertNoSort("""
                SELECT * FROM asn
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND warehouse_id = '00000000-0000-0000-0000-000000000002'
                 ORDER BY created_at DESC, id DESC
                 LIMIT 51
                """);
    }

    @Test
    @DisplayName("a warehouse's ASNs narrowed to one status page without a sort")
    void asnListingByStatus() {
        assertIndexed("""
                SELECT * FROM asn
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND warehouse_id = '00000000-0000-0000-0000-000000000002'
                   AND status = 'PACKED'
                   AND (created_at, id) < (TIMESTAMPTZ '2026-10-01 00:00:00+00',
                                           '00000000-0000-0000-0000-000000000009')
                 ORDER BY created_at DESC, id DESC
                 LIMIT 51
                """, "asn");
        assertNoSort("""
                SELECT * FROM asn
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND warehouse_id = '00000000-0000-0000-0000-000000000002'
                   AND status = 'PACKED'
                 ORDER BY created_at DESC, id DESC
                 LIMIT 51
                """);
    }

    @Test
    @DisplayName("a warehouse's outbound orders page newest first without a sort")
    void outboundOrderListingPage() {
        assertIndexed("""
                SELECT * FROM outbound_order
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND warehouse_id = '00000000-0000-0000-0000-000000000002'
                   AND (created_at, id) < (TIMESTAMPTZ '2026-10-01 00:00:00+00',
                                           '00000000-0000-0000-0000-000000000009')
                 ORDER BY created_at DESC, id DESC
                 LIMIT 51
                """, "outbound_order");
        assertNoSort("""
                SELECT * FROM outbound_order
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND warehouse_id = '00000000-0000-0000-0000-000000000002'
                 ORDER BY created_at DESC, id DESC
                 LIMIT 51
                """);
    }

    @Test
    @DisplayName("a warehouse's outbound orders narrowed to one status page without a sort")
    void outboundOrderListingByStatus() {
        assertIndexed("""
                SELECT * FROM outbound_order
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND warehouse_id = '00000000-0000-0000-0000-000000000002'
                   AND status = 'PACKED'
                   AND (created_at, id) < (TIMESTAMPTZ '2026-10-01 00:00:00+00',
                                           '00000000-0000-0000-0000-000000000009')
                 ORDER BY created_at DESC, id DESC
                 LIMIT 51
                """, "outbound_order");
        assertNoSort("""
                SELECT * FROM outbound_order
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND warehouse_id = '00000000-0000-0000-0000-000000000002'
                   AND status = 'PACKED'
                 ORDER BY created_at DESC, id DESC
                 LIMIT 51
                """);
    }

    @Test
    @DisplayName("a warehouse's handling units page newest first without a sort")
    void handlingUnitListingPage() {
        assertIndexed("""
                SELECT * FROM handling_unit
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND warehouse_id = '00000000-0000-0000-0000-000000000002'
                   AND (created_at, id) < (TIMESTAMPTZ '2026-10-01 00:00:00+00',
                                           '00000000-0000-0000-0000-000000000009')
                 ORDER BY created_at DESC, id DESC
                 LIMIT 51
                """, "handling_unit");
        assertNoSort("""
                SELECT * FROM handling_unit
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND warehouse_id = '00000000-0000-0000-0000-000000000002'
                 ORDER BY created_at DESC, id DESC
                 LIMIT 51
                """);
    }

    @Test
    @DisplayName("a warehouse's handling units narrowed to one status page without a sort")
    void handlingUnitListingByStatus() {
        assertIndexed("""
                SELECT * FROM handling_unit
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND warehouse_id = '00000000-0000-0000-0000-000000000002'
                   AND status = 'PACKED'
                   AND (created_at, id) < (TIMESTAMPTZ '2026-10-01 00:00:00+00',
                                           '00000000-0000-0000-0000-000000000009')
                 ORDER BY created_at DESC, id DESC
                 LIMIT 51
                """, "handling_unit");
        assertNoSort("""
                SELECT * FROM handling_unit
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND warehouse_id = '00000000-0000-0000-0000-000000000002'
                   AND status = 'PACKED'
                 ORDER BY created_at DESC, id DESC
                 LIMIT 51
                """);
    }

    @Test
    @DisplayName("a portal user's ASN page probes each ASN's lines by index")
    void partnerScopedAsnListing() {
        // The partner filter is an EXISTS per candidate row; it must be a seek
        // into the ASN's lines, never a walk of every line the tenant has.
        assertIndexed("""
                SELECT a.id FROM asn a
                 WHERE a.tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND a.warehouse_id = '00000000-0000-0000-0000-000000000002'
                   AND EXISTS (SELECT 1 FROM asn_line l
                                 JOIN sku s ON s.id = l.sku_id
                                WHERE l.tenant_id = a.tenant_id AND l.asn_id = a.id
                                  AND s.owner_partner_id = '00000000-0000-0000-0000-000000000003')
                 ORDER BY a.created_at DESC, a.id DESC
                 LIMIT 51
                """, "asn_line");
    }

    @Test
    @DisplayName("whether an order waits in a wave is index-served")
    void orderAwaitingWave() {
        // Asked before every single-order pick.
        assertIndexed("""
                SELECT wave_id FROM pick_wave_order
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND order_id = '00000000-0000-0000-0000-000000000002'
                """, "pick_wave_order");
    }

    @Test
    @DisplayName("a wave's orders come back in sequence from its key")
    void waveOrdersInSequence() {
        assertIndexed("""
                SELECT order_id FROM pick_wave_order
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND wave_id = '00000000-0000-0000-0000-000000000002'
                 ORDER BY seq
                """, "pick_wave_order");
    }

    @Test
    @DisplayName("an order's lines are summed for the listing from an index")
    void outboundLineTotals() {
        assertIndexed("""
                SELECT sum(ordered_qty), sum(picked_qty) FROM outbound_line
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND order_id = '00000000-0000-0000-0000-000000000002'
                """, "outbound_line");
    }

    @Test
    @DisplayName("active refresh token lookup by hash is index-served")
    void refreshTokenByHash() {
        assertIndexed("""
                SELECT * FROM refresh_token WHERE token_hash = 'deadbeef'
                """, "refresh_token");
    }

    @Test
    @DisplayName("the certificate expiry list is served by the partial index")
    void complianceExpiryList() {
        // compliance_document_expiry_idx is partial on `expires_on IS NOT NULL`.
        // The predicate below has to match that partiality or the planner
        // cannot use it -- which is the kind of mismatch that only shows up as
        // a slow screen months later.
        assertIndexed("""
                SELECT * FROM compliance_document
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND expires_on IS NOT NULL
                   AND expires_on <= current_date + 30
                 ORDER BY expires_on
                """, "compliance_document");
    }

    @Test
    @DisplayName("the LTL rate card in force between two zones is index-served")
    void ltlRateCardInForce() {
        // Runs once per booked docket. Descending effective_from lets the plan
        // stop at the first version that had started by the booking date.
        assertIndexed("""
                SELECT * FROM ltl_rate_card
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND origin_zone = 'WEST'
                   AND destination_zone = 'NORTH'
                   AND effective_from <= DATE '2026-01-15'
                 ORDER BY effective_from DESC
                 LIMIT 1
                """, "ltl_rate_card");
    }

    @Test
    @DisplayName("a terminal's LTL zone placement is index-served")
    void ltlZonePlacement() {
        // Read three times per docket priced: both hubs and the delivery point.
        assertIndexed("""
                SELECT zone_code, out_of_delivery_area FROM ltl_zone_terminal
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND terminal_id = '00000000-0000-0000-0000-000000000002'
                """, "ltl_zone_terminal");
    }

    @Test
    @DisplayName("a hub's dockets awaiting delivery page by docket number from the index")
    void docketsAwaitingDeliveryAtAHub() {
        // The delivery planner's work queue. Leading with the hub and the
        // status keeps dockets still on the road -- most of them -- out of
        // the seek altogether.
        assertIndexed("""
                SELECT * FROM consignment
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND destination_hub_terminal_id = '00000000-0000-0000-0000-000000000002'
                   AND status = 'ARRIVED_AT_HUB'
                   AND tracking_ref > 'DKT-0000000000'
                 ORDER BY tracking_ref
                 LIMIT 50
                """, "consignment");
    }

    @Test
    @DisplayName("the load a consignment is on now is found from its links by index")
    void currentLoadOfAConsignment() {
        // A docket may be linked to a line haul and a delivery load; every
        // consignment read picks one of at most two links this way.
        assertIndexed("""
                SELECT load_id FROM load_consignment
                 WHERE consignment_id = '00000000-0000-0000-0000-000000000002'
                 ORDER BY (leg = 'DELIVERY') DESC
                 LIMIT 1
                """, "load_consignment");
    }

    @Test
    @DisplayName("the docket receivable listing pages by docket number without a sort")
    void docketReceivablesByDocketNo() {
        assertIndexed("""
                SELECT * FROM docket_receivable
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND docket_no > 'DKT-0000000000'
                 ORDER BY docket_no
                 LIMIT 50
                """, "docket_receivable");
    }

    @Test
    @DisplayName("a customer's docket statement pages by docket number without a sort")
    void docketReceivablesForCustomer() {
        assertIndexed("""
                SELECT * FROM docket_receivable
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND customer_partner_id = '00000000-0000-0000-0000-000000000002'
                   AND docket_no > 'DKT-0000000000'
                 ORDER BY docket_no
                 LIMIT 50
                """, "docket_receivable");
    }

    @Test
    @DisplayName("the fleet listing pages by registration without a sort")
    void vehicleListingIsKeysetSeekable() {
        // The keyset predicate and the ORDER BY have to ride the same unique
        // index, or every page costs a sort of the whole fleet.
        assertIndexed("""
                SELECT * FROM vehicle
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND registration_no > 'MH-01-AB-0000'
                 ORDER BY registration_no
                 LIMIT 50
                """, "vehicle");
    }

    @Test
    @DisplayName("the client contract in force on a date is index-served")
    void clientContractInForceOnADate() {
        // Runs once per distinct charge date in every billing run.
        assertIndexed("""
                SELECT * FROM client_contract
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND client_partner_id = '00000000-0000-0000-0000-000000000002'
                   AND status = 'ACTIVE'
                   AND effective_from <= DATE '2026-01-15'
                   AND (effective_to IS NULL OR effective_to >= DATE '2026-01-15')
                   AND (valid_till IS NULL OR valid_till >= DATE '2026-01-15')
                 ORDER BY effective_from DESC
                 LIMIT 1
                """, "client_contract");
    }

    @Test
    @DisplayName("the contract lifecycle's reads are index-served: one version, the version in force, a client's versions")
    void clientContractLifecycleReads() {
        // GET /contracts/{id}, the checklist's and the activation's lookup of the open version, and
        // the per-client listing (newest first) that the consoles and the portal read.
        assertIndexed("""
                SELECT * FROM client_contract
                 WHERE id = '00000000-0000-0000-0000-000000000003'
                """, "client_contract");
        assertIndexed("""
                SELECT * FROM client_contract
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND client_partner_id = '00000000-0000-0000-0000-000000000002'
                   AND effective_to IS NULL
                   AND status = 'ACTIVE'
                """, "client_contract");
        assertIndexed("""
                SELECT * FROM client_contract
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND client_partner_id = '00000000-0000-0000-0000-000000000002'
                   AND status = 'ACTIVE'
                 ORDER BY effective_from DESC, created_at DESC
                 LIMIT 200
                """, "client_contract");
    }

    @Test
    @DisplayName("contract lanes, their slabs and party lists, and a contract family are index-served")
    void contractLaneReads() {
        // The order desk's lanes, the resolver's lanes of the version in force, and the lane lists.
        assertIndexed("""
                SELECT * FROM client_contract_lane
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND contract_id IN ('00000000-0000-0000-0000-000000000002')
                 ORDER BY contract_id, lane_code
                """, "client_contract_lane");
        assertIndexed("""
                SELECT * FROM client_contract_lane_slab
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND lane_id IN ('00000000-0000-0000-0000-000000000003')
                 ORDER BY lane_id, min_weight_kg
                """, "client_contract_lane_slab");
        assertIndexed("""
                SELECT * FROM client_contract_lane_party
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND lane_id IN ('00000000-0000-0000-0000-000000000003')
                 ORDER BY lane_id, role, partner_id
                """, "client_contract_lane_party");
        // The CONTRACT settings scope names a contract by its family.
        assertIndexed("""
                SELECT 1 FROM client_contract
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND coalesce(family_id, id) = '00000000-0000-0000-0000-000000000002'
                 LIMIT 1
                """, "client_contract");
        // Which orders ride a lane, and which a contract version.
        assertIndexed("""
                SELECT id FROM sales_order
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND contract_lane_id = '00000000-0000-0000-0000-000000000003'
                """, "sales_order");
        assertIndexed("""
                SELECT id FROM sales_order
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND contract_id = '00000000-0000-0000-0000-000000000003'
                """, "sales_order");
    }

    @Test
    @DisplayName("a client's charges for one billing month are index-served and keyset-seekable")
    void clientChargesForAPeriod() {
        // The billing run's read and the charge listing share
        // client_charge_period_idx; the listing's cursor rides its trailing id.
        assertIndexed("""
                SELECT * FROM client_charge
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND client_partner_id = '00000000-0000-0000-0000-000000000002'
                   AND billing_period = DATE '2026-01-01'
                   AND id > '00000000-0000-0000-0000-000000000009'
                 ORDER BY id
                 LIMIT 51
                """, "client_charge");
    }

    @Test
    @DisplayName("a client's invoices page newest period first without a sort")
    void clientInvoicesNewestFirst() {
        // Also the period-close check: is there an invoice for this client and
        // month? Both ride the unique (tenant, client, period_start) index.
        assertIndexed("""
                SELECT * FROM client_invoice
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND client_partner_id = '00000000-0000-0000-0000-000000000002'
                   AND period_start < DATE '2026-01-01'
                 ORDER BY period_start DESC
                 LIMIT 51
                """, "client_invoice");
    }

    @Test
    @DisplayName("the client margin view is index-served end to end")
    void clientMarginView() {
        // One period's invoices, then per invoice its transport lines and its
        // charges joined to the carrier's freight bill by trip. Any of the four
        // falling back to a sequential scan makes the view cost the whole
        // tenant's billing history.
        String sql = """
                SELECT i.id,
                       (SELECT sum(l.amount) FROM client_invoice_line l
                         WHERE l.tenant_id = i.tenant_id AND l.invoice_id = i.id
                           AND l.service_type = 'TRANSPORT') AS transport,
                       (SELECT sum(coalesce(fb.computed_amount, 0) * ch.cost_share)
                          FROM client_charge ch
                          LEFT JOIN freight_bill fb
                                 ON fb.tenant_id = ch.tenant_id AND fb.trip_id = ch.trip_id
                         WHERE ch.tenant_id = i.tenant_id AND ch.invoice_id = i.id
                           AND ch.service_type = 'TRANSPORT') AS payable
                  FROM client_invoice i
                 WHERE i.tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND i.period_start = DATE '2026-01-01'
                   AND i.client_partner_id > '00000000-0000-0000-0000-000000000002'
                 ORDER BY i.client_partner_id
                 LIMIT 51
                """;
        assertIndexed(sql, "client_invoice");
        assertIndexed(sql, "client_charge");
        assertIndexed(sql, "freight_bill");
    }

    @Test
    @DisplayName("a client's statement pages invoices and credit notes in issue order by index")
    void clientStatement() {
        // Both halves of the union seek on (tenant, client, issued_at, id), so
        // every page costs the same however long the client's history is.
        String sql = """
                SELECT * FROM (
                    SELECT i.id AS document_id, i.issued_at, i.total_amount AS debit
                      FROM client_invoice i
                     WHERE i.tenant_id = '00000000-0000-0000-0000-000000000001'
                       AND i.client_partner_id = '00000000-0000-0000-0000-000000000002'
                       AND (i.issued_at, i.id) > (TIMESTAMPTZ '2026-01-01 00:00:00+00',
                                                  '00000000-0000-0000-0000-000000000009')
                    UNION ALL
                    SELECT cn.id, cn.issued_at, 0
                      FROM client_credit_note cn
                     WHERE cn.tenant_id = '00000000-0000-0000-0000-000000000001'
                       AND cn.client_partner_id = '00000000-0000-0000-0000-000000000002'
                       AND (cn.issued_at, cn.id) > (TIMESTAMPTZ '2026-01-01 00:00:00+00',
                                                    '00000000-0000-0000-0000-000000000009')
                ) entries
                 ORDER BY issued_at, document_id
                 LIMIT 51
                """;
        assertIndexed(sql, "client_invoice");
        assertIndexed(sql, "client_credit_note");
    }

    @Test
    @DisplayName("what has been credited against an invoice and its lines is index-served")
    void clientCreditedAmounts() {
        // Read for every invoice shown, for every margin row, and under the
        // lock before every credit note is issued.
        assertIndexed("""
                SELECT sum(total_amount) FROM client_credit_note
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND invoice_id = '00000000-0000-0000-0000-000000000002'
                """, "client_credit_note");
        assertIndexed("""
                SELECT sum(taxable_amount), sum(tax_amount) FROM client_credit_note_line
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND invoice_line_id = '00000000-0000-0000-0000-000000000002'
                """, "client_credit_note_line");
        assertIndexed("""
                SELECT * FROM client_credit_note_line
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND credit_note_id = '00000000-0000-0000-0000-000000000002'
                 ORDER BY line_no
                """, "client_credit_note_line");
    }

    @Test
    @DisplayName("a client's ledger-derived storage month is index-served")
    void clientStorageDays() {
        assertIndexed("""
                SELECT held_on, units_held, pallets FROM client_storage_day
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND client_partner_id = '00000000-0000-0000-0000-000000000002'
                   AND period_start = DATE '2026-01-01'
                 ORDER BY held_on
                """, "client_storage_day");
    }

    @Test
    @DisplayName("the stock an owner holds, and how it moved since a day, is index-served")
    void ownerStockFromTheLedger() {
        // The warehouse::api read behind ledger storage: the owner's SKUs,
        // their balances now, and their movements since the month began --
        // never the whole ledger.
        String sql = """
                WITH owned AS (
                    SELECT id FROM sku
                     WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                       AND owner_partner_id = '00000000-0000-0000-0000-000000000002'
                )
                SELECT (SELECT sum(b.on_hand) FROM stock_balance b
                         WHERE b.tenant_id = '00000000-0000-0000-0000-000000000001'
                           AND b.sku_id IN (SELECT id FROM owned)),
                       (SELECT sum(m.quantity) FROM stock_movement m
                         WHERE m.tenant_id = '00000000-0000-0000-0000-000000000001'
                           AND m.sku_id IN (SELECT id FROM owned)
                           AND m.occurred_at >= TIMESTAMPTZ '2026-01-01 00:00:00+00')
                """;
        assertIndexed(sql, "sku");
        assertIndexed(sql, "stock_balance");
        assertIndexed(sql, "stock_movement");
    }

    @Test
    @DisplayName("the client margin's credit-note reduction is index-served")
    void clientMarginCredits() {
        assertIndexed("""
                SELECT sum(cl.taxable_amount)
                  FROM client_credit_note cn
                  JOIN client_credit_note_line cl
                    ON cl.tenant_id = cn.tenant_id AND cl.credit_note_id = cn.id
                 WHERE cn.tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND cn.invoice_id = '00000000-0000-0000-0000-000000000002'
                """, "client_credit_note_line");
    }

    @Test
    @DisplayName("the client portal binding lookup is index-served")
    void clientPortalBinding() {
        // Asked on every client-billing read and command.
        assertIndexed("""
                SELECT client_partner_id FROM client_portal_user
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND user_id = '00000000-0000-0000-0000-000000000002'
                """, "client_portal_user");
    }

    @Test
    @DisplayName("the rate card in force on a date is index-served")
    void tariffInForceOnADate() {
        // Runs once per completed trip, and the shape matters as much as the
        // presence: effective_from descending is what lets the plan stop at the
        // first row instead of reading a lane's whole rate history to sort it.
        assertIndexed("""
                SELECT * FROM tariff
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND vendor_partner_id = '00000000-0000-0000-0000-000000000002'
                   AND origin_terminal_id = '00000000-0000-0000-0000-000000000003'
                   AND destination_terminal_id = '00000000-0000-0000-0000-000000000004'
                   AND vehicle_type = 'RIGID'
                   AND effective_from <= DATE '2026-01-15'
                   AND (effective_to IS NULL OR effective_to >= DATE '2026-01-15')
                 ORDER BY effective_from DESC
                 LIMIT 1
                """, "tariff");
    }

    @Test
    @DisplayName("the settlement queue is index-served")
    void freightBillsByStatus() {
        assertIndexed("""
                SELECT * FROM freight_bill
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND status = 'DISPUTED'
                 ORDER BY created_at DESC
                """, "freight_bill");
    }

    @Test
    @DisplayName("a circuit's payable is found by index")
    void freightBillByCircuitRun() {
        // Runs on every delivery of a circuit completion, redeliveries included.
        assertIndexed("""
                SELECT * FROM freight_bill
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND circuit_run_id = '00000000-0000-0000-0000-000000000002'
                """, "freight_bill");
    }

    @Test
    @DisplayName("a rail leg's payable is found by index")
    void freightBillByItineraryLeg() {
        // Runs on every delivery of a rail leg's release, redeliveries included.
        assertIndexed("""
                SELECT * FROM freight_bill
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND itinerary_leg_id = '00000000-0000-0000-0000-000000000002'
                """, "freight_bill");
    }

    @Test
    @DisplayName("the bill a trip is paid on is found by index, cancelled ones included")
    void freightBillByTrip() {
        // The redelivery check for trip bills. It must see a superseded,
        // cancelled trip bill too, so it cannot lean on the partial unique
        // index of live bills alone.
        assertIndexed("""
                SELECT * FROM freight_bill
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND trip_id = '00000000-0000-0000-0000-000000000002'
                 ORDER BY (status = 'CANCELLED'), created_at DESC
                 LIMIT 1
                """, "freight_bill");
    }

    @Test
    @DisplayName("a split consignment's parts page by reference from the index, without a sort")
    void consignmentParts() {
        // Read by the parts listing and by every split consignment's view and
        // public tracking. The partial index holds parts only.
        String sql = """
                SELECT * FROM consignment
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND parent_consignment_id = '00000000-0000-0000-0000-000000000002'
                   AND tracking_ref > 'LR-0000000000-050'
                 ORDER BY tracking_ref
                 LIMIT 51
                """;
        assertIndexed(sql, "consignment");
        assertNoSort(sql);
    }

    @Test
    @DisplayName("the consignment made from a handling unit is found by index")
    void consignmentByHandlingUnit() {
        // Runs on every delivery of a warehouse hand-off.
        assertIndexed("""
                SELECT * FROM consignment
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND handling_unit_id = '00000000-0000-0000-0000-000000000002'
                """, "consignment");
    }

    @Test
    @DisplayName("the exception board pages newest first without a sort")
    void exceptionBoard() {
        // Keyset on (raised_at, id), descending. The row comparison and the
        // ORDER BY must both ride exception_ticket_board_idx, or every page of
        // the board sorts every ticket the tenant ever raised.
        assertIndexed("""
                SELECT * FROM exception_ticket
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND (raised_at, id) < (TIMESTAMPTZ '2026-10-01 00:00:00+00',
                                          '00000000-0000-0000-0000-000000000009')
                 ORDER BY raised_at DESC, id DESC
                 LIMIT 51
                """, "exception_ticket");
    }

    @Test
    @DisplayName("the exception board narrowed to one status is index-served")
    void exceptionBoardByStatus() {
        assertIndexed("""
                SELECT * FROM exception_ticket
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND status = 'RAISED'
                 ORDER BY raised_at DESC, id DESC
                 LIMIT 51
                """, "exception_ticket");
    }

    @Test
    @DisplayName("a redelivered detection finds its ticket by index")
    void exceptionBySourceKey() {
        // Asked on every delivery of every event the control tower turns into
        // a ticket -- route deviations, missed connections, milk-run stops,
        // receipt variances and pick shortfalls. The partial unique index
        // serves it, because an equality on source_key implies it is not null.
        assertIndexed("""
                SELECT * FROM exception_ticket
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND source_key = 'pick-shortfall:00000000-0000-0000-0000-000000000002:1'
                """, "exception_ticket");
    }

    @Test
    @DisplayName("the exception summary counts open tickets by index")
    void exceptionSummary() {
        // Now also counts the open tickets that have no trip; still one pass
        // over the open statuses, never the whole history.
        assertIndexed("""
                SELECT count(*) FILTER (WHERE severity = 'P1')::int AS open_p1,
                       count(*) FILTER (WHERE suspends_settlement)::int AS settlement_holds,
                       count(*) FILTER (WHERE trip_id IS NULL)::int AS open_without_trip
                  FROM exception_ticket
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND status IN ('RAISED', 'ASSIGNED', 'UNDER_INVESTIGATION', 'RESOLVED')
                """, "exception_ticket");
    }

    @Test
    @DisplayName("the settlement hold check is index-served")
    void openSettlementHolds() {
        // Asked on every vendor-invoice match. Served by the partial index, so
        // its predicate has to say released_at IS NULL exactly as the index does.
        assertIndexed("""
                SELECT ticket_no FROM settlement_hold
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND trip_id = '00000000-0000-0000-0000-000000000002'
                   AND released_at IS NULL
                 ORDER BY placed_at
                """, "settlement_hold");
    }

    @Test
    @DisplayName("a vehicle's latest fix is index-served")
    void latestVehicleFix() {
        // Runs on every exception raised by hand, against the largest table in
        // the schema.
        assertIndexed("""
                SELECT lat, lon, recorded_at FROM gps_ping
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND vehicle_id = '00000000-0000-0000-0000-000000000002'
                 ORDER BY recorded_at DESC
                 LIMIT 1
                """, "gps_ping");
    }

    @Test
    @DisplayName("a trip's track read after a cursor is index-served")
    void tripTrackAfterCursor() {
        // The incremental read a console makes on every visit to a trip it has
        // cached the track of: only the new end of the largest table, never a
        // walk of the trip's points.
        assertIndexed("""
                SELECT lon, lat, recorded_at FROM gps_ping
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND trip_id = '00000000-0000-0000-0000-000000000002'
                   AND recorded_at > '2026-01-01T10:00:00Z'
                 ORDER BY recorded_at
                 LIMIT 5000
                """, "gps_ping");
    }

    @Test
    @DisplayName("the gate register pages newest entry first from an index, whole or open only, without sorting")
    void gateRegisterPages() {
        String all = """
                SELECT g.id FROM gate_visit g
                 WHERE g.tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND (g.entered_at, g.id) < ('2026-11-04T00:00:00Z'::timestamptz,
                                               '00000000-0000-0000-0000-000000000003'::uuid)
                 ORDER BY g.entered_at DESC, g.id DESC
                 LIMIT 51
                """;
        assertIndexed(all, "gate_visit");
        assertNoSort(all);
        String open = """
                SELECT g.id FROM gate_visit g
                 WHERE g.tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND g.status = 'OPEN'
                   AND (g.entered_at, g.id) < ('2026-11-04T00:00:00Z'::timestamptz,
                                               '00000000-0000-0000-0000-000000000003'::uuid)
                 ORDER BY g.entered_at DESC, g.id DESC
                 LIMIT 51
                """;
        assertIndexed(open, "gate_visit");
        assertNoSort(open);
    }

    @Test
    @DisplayName("who is inside, a vehicle's open visit and a trip's visits are index-served")
    void gateVisitLookups() {
        assertIndexed("""
                SELECT * FROM gate_visit
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND registration_no = 'MH12AB1234' AND status = 'OPEN'
                """, "gate_visit");
        assertIndexed("""
                SELECT * FROM gate_visit
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND status = 'OPEN'
                 ORDER BY entered_at, id
                 LIMIT 500
                """, "gate_visit");
        assertIndexed("""
                SELECT * FROM gate_visit
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND trip_id = '00000000-0000-0000-0000-000000000002'
                 ORDER BY entered_at DESC, id DESC
                 LIMIT 51
                """, "gate_visit");
    }

    @Test
    @DisplayName("gate passes are found by number, by vehicle and unit, and listed newest first from an index")
    void gatePassReads() {
        assertIndexed("""
                SELECT * FROM gate_pass
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001' AND pass_no = 'GP-00001'
                """, "gate_pass");
        assertIndexed("""
                SELECT * FROM gate_pass
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND registration_no = 'MH12AB1234'
                   AND org_unit_id = '00000000-0000-0000-0000-000000000002'
                 ORDER BY valid_until DESC
                 LIMIT 50
                """, "gate_pass");
        String page = """
                SELECT p.id FROM gate_pass p
                 WHERE p.tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND (p.created_at, p.id) < ('2026-11-04T00:00:00Z'::timestamptz,
                                               '00000000-0000-0000-0000-000000000003'::uuid)
                 ORDER BY p.created_at DESC, p.id DESC
                 LIMIT 51
                """;
        assertIndexed(page, "gate_pass");
        assertNoSort(page);
    }

    @Test
    @DisplayName("a trip's stops are read in order from an index")
    void tripStopsInOrder() {
        String sql = """
                SELECT * FROM trip_stop
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND trip_id = '00000000-0000-0000-0000-000000000002'
                 ORDER BY seq
                """;
        assertIndexed(sql, "trip_stop");
        assertNoSort(sql);
    }

    @Test
    @DisplayName("a lane's corridor and the corridor list are index-served, the list without sorting")
    void routeCorridorReads() {
        assertIndexed("""
                SELECT id FROM route_corridor
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND origin_terminal_id = '00000000-0000-0000-0000-000000000002'
                   AND destination_terminal_id = '00000000-0000-0000-0000-000000000003'
                """, "route_corridor");
        String list = """
                SELECT c.id FROM route_corridor c
                 WHERE c.tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND (c.origin_terminal_id, c.destination_terminal_id)
                       > ('00000000-0000-0000-0000-000000000002'::uuid, '00000000-0000-0000-0000-000000000003'::uuid)
                 ORDER BY c.origin_terminal_id, c.destination_terminal_id
                 LIMIT 51
                """;
        assertIndexed(list, "route_corridor");
        assertNoSort(list);
    }

    @Test
    @DisplayName("every tenant-scoped table leads its primary lookup index with tenant_id")
    void indexesLeadWithTenantId() {
        // Row-level security adds `tenant_id = …` to every query, so an index
        // that does not lead with it cannot serve the combined predicate.
        List<String> offenders = jdbc.sql("""
                        SELECT t.relname || '.' || i.relname
                          FROM pg_index x
                          JOIN pg_class i ON i.oid = x.indexrelid
                          JOIN pg_class t ON t.oid = x.indrelid
                          JOIN pg_namespace n ON n.oid = t.relnamespace
                         WHERE n.nspname = 'public'
                           AND NOT x.indisprimary
                           AND NOT x.indisunique
                           AND EXISTS (SELECT 1 FROM information_schema.columns c
                                        WHERE c.table_name = t.relname
                                          AND c.column_name = 'tenant_id')
                           -- Cross-tenant maintenance indexes are the one
                           -- exemption, and they must say so in their name.
                           -- Housekeeping sweeps run platform-wide, so leading
                           -- with tenant_id would defeat their only purpose.
                           AND i.relname NOT LIKE '%\\_sweep\\_idx'
                           AND (SELECT a.attname FROM pg_attribute a
                                 WHERE a.attrelid = t.oid AND a.attnum = x.indkey[0]) <> 'tenant_id'
                        """)
                .query(String.class)
                .list();

        assertThat(offenders)
                .as("non-unique indexes on tenant-scoped tables must lead with tenant_id")
                .isEmpty();
    }

    /**
     * A keyset page must ride its index in order: a Sort node would mean
     * every page sorts every matching row before taking the first 51.
     */
    private void assertNoSort(String sql) {
        // One transaction, so the SET LOCAL holds for the EXPLAIN after it.
        String plan = new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.sql("SET LOCAL enable_seqscan = off").update();
            return String.join("\n", jdbc.sql("EXPLAIN " + sql).query(String.class).list());
        });
        assertThat(plan).as("keyset page should not sort:%n%s", plan).doesNotContain("Sort");
    }

    @Test
    @DisplayName("the approvals inbox by status is a keyset page on its index")
    void approvalInboxByStatus() {
        String sql = """
                SELECT * FROM approval_request r
                 WHERE r.tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND r.status = 'REQUESTED'
                   AND (r.requested_at, r.id) < ('2026-11-10T00:00:00Z', '00000000-0000-0000-0000-000000000009')
                 ORDER BY r.requested_at DESC, r.id DESC
                 LIMIT 51
                """;
        assertIndexed(sql, "approval_request");
        assertNoSort(sql);
    }

    @Test
    @DisplayName("the approvals inbox unfiltered, by requester and by subject are keyset pages on their indexes")
    void approvalInboxShapes() {
        for (String filter : List.of("",
                " AND r.requested_by = '00000000-0000-0000-0000-000000000002'",
                " AND r.subject_type = 'ORDER_CANCELLATION' AND r.subject_id = '00000000-0000-0000-0000-000000000003'")) {
            String sql = "SELECT * FROM approval_request r WHERE r.tenant_id = '00000000-0000-0000-0000-000000000001'"
                    + filter + " ORDER BY r.requested_at DESC, r.id DESC LIMIT 51";
            assertIndexed(sql, "approval_request");
            assertNoSort(sql);
        }
    }

    @Test
    @DisplayName("an open request for a subject, and the expiry sweep's lapsed requests, are index-served")
    void approvalLookups() {
        assertIndexed("""
                SELECT * FROM approval_request
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001' AND subject_type = 'ORDER_CANCELLATION'
                   AND subject_id = '00000000-0000-0000-0000-000000000003' AND action = 'CANCEL'
                   AND status = 'REQUESTED'
                """, "approval_request");
        assertIndexed("""
                SELECT id FROM approval_request
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001' AND status = 'REQUESTED'
                   AND due_at IS NOT NULL AND due_at <= '2026-11-10T00:00:00Z'
                 ORDER BY due_at LIMIT 200
                """, "approval_request");
        assertIndexed("""
                SELECT * FROM approval_decision
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND request_id = '00000000-0000-0000-0000-000000000004'
                 ORDER BY decided_at, id
                """, "approval_decision");
    }

    @Test
    @DisplayName("a customer's open orders, for its credit exposure, are index-served")
    void openOrdersOfACustomer() {
        // Asked on every validation, amendment and line add of a customer with
        // a limit, so it must never walk the tenant's order history.
        assertIndexed("""
                SELECT o.id FROM sales_order o
                 WHERE o.tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND o.customer_partner_id = '00000000-0000-0000-0000-000000000002'
                   AND o.status IN ('VALIDATED', 'PARTIALLY_PLANNED', 'FULLY_PLANNED')
                """, "sales_order");
    }

    @Test
    @DisplayName("a client's uninvoiced charges, for its credit exposure, are index-served")
    void unbilledChargesOfAClient() {
        assertIndexed("""
                SELECT * FROM client_charge
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND client_partner_id = '00000000-0000-0000-0000-000000000002'
                   AND invoice_id IS NULL
                """, "client_charge");
    }

    @Test
    @DisplayName("the weight of open orders already charged, for credit exposure, is index-served")
    void chargedWeightOfOpenOrders() {
        String sql = """
                SELECT c.order_id, sum(c.chargeable_weight_kg) AS charged_kg
                  FROM consignment c
                  JOIN load_consignment lc ON lc.consignment_id = c.id
                  JOIN trip t ON t.tenant_id = c.tenant_id AND t.load_id = lc.load_id
                             AND t.status <> 'CANCELLED'
                  JOIN client_charge ch ON ch.tenant_id = c.tenant_id
                             AND ch.source_key = 'TRIP:' || t.id || ':00000000-0000-0000-0000-000000000002'
                 WHERE c.tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND c.order_id IN ('00000000-0000-0000-0000-000000000003')
                   AND c.freight_mode <> 'LTL'
                 GROUP BY c.order_id
                """;
        assertIndexed(sql, "consignment");
        assertIndexed(sql, "client_charge");
        assertIndexed(sql, "trip");
    }

    @Test
    @DisplayName("the credit limit change queue pages newest first without a sort")
    void creditLimitChangeQueue() {
        assertIndexed("""
                SELECT * FROM credit_limit_change c
                 WHERE c.tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND c.status = 'PENDING'
                   AND (c.requested_at, c.id) < (now(), '00000000-0000-0000-0000-000000000003')
                 ORDER BY c.requested_at DESC, c.id DESC
                 LIMIT 51
                """, "credit_limit_change");
        assertIndexed("""
                SELECT * FROM credit_limit_change c
                 WHERE c.tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND c.partner_id = '00000000-0000-0000-0000-000000000002'
                 ORDER BY c.requested_at DESC, c.id DESC
                 LIMIT 51
                """, "credit_limit_change");
    }

    @Test
    @DisplayName("the freight carrying each line of an order, for closing it, is index-served")
    void freightByOrderLine() {
        String sql = """
                SELECT cl.order_line_id,
                       count(*) FILTER (WHERE c.status NOT IN ('CANCELLED', 'SPLIT')) AS live,
                       count(*) FILTER (WHERE c.status = 'PLANNED'
                           OR (c.status = 'ASSIGNED_TO_LOAD' AND NOT EXISTS (
                                SELECT 1 FROM load_consignment lc
                                  JOIN load_unit l ON l.id = lc.load_id
                                 WHERE lc.tenant_id = '00000000-0000-0000-0000-000000000001'
                                   AND lc.consignment_id = c.id
                                   AND l.status IN ('DISPATCHED', 'COMPLETED')))) AS waiting
                  FROM consignment c
                  JOIN consignment_line cl
                    ON cl.tenant_id = '00000000-0000-0000-0000-000000000001' AND cl.consignment_id = c.id
                 WHERE c.tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND c.order_id = '00000000-0000-0000-0000-000000000003'
                 GROUP BY cl.order_line_id
                """;
        assertIndexed(sql, "consignment");
        assertIndexed(sql, "consignment_line");
        assertIndexed(sql, "load_consignment");
        assertIndexed("""
                SELECT * FROM order_close_setting WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                """, "order_close_setting");
    }

    @Test
    @DisplayName("resolving a context's settings and listing profiles are index-served")
    void settingProfiles() {
        // The statement itself, with literals for its parameters: the
        // jurisdiction CTEs, the ancestors read by path and the combination
        // conditions are all part of it.
        String resolve = literal(EffectiveSettingsQuery.RESOLVE, Map.ofEntries(
                Map.entry("tenantId", "'00000000-0000-0000-0000-000000000001'"),
                Map.entry("keys", "'order.close.tolerancePercent,ui.theme'"),
                Map.entry("unitPath", "NULL"),
                Map.entry("unitId", "'00000000-0000-0000-0000-000000000002'"),
                Map.entry("customerId", "'00000000-0000-0000-0000-000000000003'"),
                Map.entry("vehicleId", "NULL"),
                Map.entry("vendorId", "'00000000-0000-0000-0000-000000000005'"),
                Map.entry("userId", "NULL"),
                Map.entry("country", "NULL"),
                Map.entry("state", "NULL"),
                Map.entry("freightMode", "'LTL'"),
                Map.entry("contractId", "'00000000-0000-0000-0000-000000000006'")));
        assertIndexed(resolve, "setting_profile");
        assertIndexed(resolve, "setting_value");
        assertIndexed(resolve, "org_unit");
        assertIndexed(resolve, "business_partner");
        assertIndexed(resolve, "vehicle");
        assertIndexed("""
                SELECT p.id FROM setting_profile p
                 WHERE p.tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND p.scope_type <> 'USER'
                   AND (p.scope_type, p.created_at, p.id) > ('ORG_DEFAULT', now(),
                        '00000000-0000-0000-0000-000000000004'::uuid)
                 ORDER BY p.scope_type, p.created_at, p.id
                 LIMIT 51
                """, "setting_profile");
        assertIndexed("""
                SELECT id, name, path, unit_type FROM org_unit
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND path LIKE '/acme/' || '%'
                   AND path > '/acme/a/'
                 ORDER BY path
                 LIMIT 51
                """, "org_unit");
    }

    @Test
    @DisplayName("the tenant's other combinations, read to refuse an ambiguous one, and the org-unit tree are index-served")
    void combinationsAndOrgTree() {
        String others = """
                SELECT p.id, p.name, p.match_org_unit_id, u.path, p.priority,
                       (SELECT string_agg(DISTINCT v.setting_key, ',') FROM setting_value v
                         WHERE v.profile_id = p.id) AS keys
                  FROM setting_profile p
                  LEFT JOIN org_unit u ON u.id = p.match_org_unit_id
                 WHERE p.tenant_id = '00000000-0000-0000-0000-000000000001' AND p.scope_type = 'COMBINATION'
                   AND p.id <> '00000000-0000-0000-0000-000000000002'
                """;
        assertIndexed(others, "setting_profile");
        assertIndexed(others, "setting_value");
        assertIndexed(others, "org_unit");
        String tree = """
                SELECT u.id, c.country_code, s.state_code
                  FROM org_unit u
                  LEFT JOIN LATERAL (
                       SELECT a.country_code, a.path FROM org_unit a
                        WHERE a.tenant_id = u.tenant_id AND a.country_code IS NOT NULL
                          AND a.path = ANY (ARRAY(SELECT left(u.path, i) FROM generate_series(2, length(u.path)) i
                                                   WHERE substr(u.path, i, 1) = '/'))
                        ORDER BY length(a.path) DESC LIMIT 1) c ON true
                  LEFT JOIN LATERAL (
                       SELECT a.state_code FROM org_unit a
                        WHERE a.tenant_id = u.tenant_id AND a.state_code IS NOT NULL
                          AND length(a.path) > length(c.path)
                          AND a.path = ANY (ARRAY(SELECT left(u.path, i) FROM generate_series(2, length(u.path)) i
                                                   WHERE substr(u.path, i, 1) = '/'))
                        ORDER BY length(a.path) DESC LIMIT 1) s ON true
                 WHERE u.tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND u.path LIKE '/acme/' || '%'
                   AND u.path > '/acme/a/'
                 ORDER BY u.path
                 LIMIT 51
                """;
        assertIndexed(tree, "org_unit");
    }

    /** {@code sql} with each {@code :name} parameter replaced by its literal. */
    private static String literal(String sql, Map<String, String> values) {
        Matcher m = Pattern.compile("(?<!:):([a-zA-Z]+)").matcher(sql);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = values.get(m.group(1));
            assertThat(value).as("a literal for :%s", m.group(1)).isNotNull();
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    @Test
    @DisplayName("the customers aboard a load, read for its trip's settings, are index-served")
    void customersAboardALoad() {
        String sql = """
                SELECT DISTINCT o.customer_partner_id
                  FROM load_consignment lc
                  JOIN consignment c ON c.id = lc.consignment_id
                  JOIN sales_order o ON o.id = c.order_id
                 WHERE lc.tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND lc.load_id = '00000000-0000-0000-0000-000000000002'
                 ORDER BY o.customer_partner_id
                """;
        assertIndexed(sql, "load_consignment");
        assertIndexed(sql, "consignment");
        assertIndexed(sql, "sales_order");
    }

    @Test
    @DisplayName("a tenant's root unit, which a tenant-wide rate write is held to, is index-served")
    void tenantRootUnit() {
        assertIndexed("""
                SELECT id FROM org_unit
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001' AND parent_id IS NULL
                 ORDER BY path
                 LIMIT 1
                """, "org_unit");
    }

    private void assertIndexed(String sql, String table) {
        // Disabling seqscan is a probe, not a recommendation: on an empty table
        // the planner would otherwise always choose one, and the question here
        // is whether a usable index exists at all.
        jdbc.sql("SET LOCAL enable_seqscan = off").update();

        String plan = String.join("\n",
                jdbc.sql("EXPLAIN " + sql).query(String.class).list());

        assertThat(plan)
                .as("query over %s should not fall back to a sequential scan:%n%s", table, plan)
                .doesNotContain("Seq Scan on " + table);
    }
}
