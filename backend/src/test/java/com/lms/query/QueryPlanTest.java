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
                   AND effective_from <= DATE '2026-01-15'
                   AND (effective_to IS NULL OR effective_to >= DATE '2026-01-15')
                 ORDER BY effective_from DESC
                 LIMIT 1
                """, "client_contract");
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
