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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.lms.controltower.command.ExceptionCommandService;
import com.lms.controltower.command.domain.ExceptionCategory;
import com.lms.execution.command.TripCommandService;
import com.lms.masterdata.command.FleetCommandService;
import com.lms.masterdata.command.RegisterTerminalCommand;
import com.lms.masterdata.command.TerminalCommandService;
import com.lms.masterdata.command.domain.Terminal;
import com.lms.performance.TripScaffold;
import com.lms.shared.geo.LatLon;
import com.lms.shared.tenant.TenantContext;
import com.lms.warehouse.command.InboundCommandService;
import com.lms.warehouse.command.OutboundCommandService;
import com.lms.warehouse.command.WarehouseSetupCommandService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * A tenant with one row of everything the isolation tests probe: an order, a
 * consignment, a load, a trip, an allocation with its offer, a freight bill, a
 * client invoice, an exception ticket, and a warehouse's ASN, outbound order
 * and handling unit.
 *
 * <p>Built through the real command services wherever one exists, so the rows
 * are the shape production writes. Only the freight bill and the client
 * invoice are inserted directly: their normal producers are an event listener
 * on trip completion and a month-end billing run over contracts, charges and a
 * storage computation, which is the wrong amount of machinery for a fixture
 * whose only job is to be a row that belongs to somebody.
 */
@Component
public class TenantSeeds {

    /** The ids by table name, plus the few other values the probes need. */
    public record Seeded(UUID tenantId, String orgPath, UUID orgUnitId, UUID clientId,
                         String trackingRef, Map<String, UUID> ids) {

        public UUID id(String table) {
            return ids.get(table);
        }
    }

    private static final String SETUP_PRINCIPAL = "isolation-setup";

    private final TestFixtures fixtures;
    private final SpineFixtures spine;
    private final FleetCommandService fleet;
    private final TerminalCommandService terminals;
    private final TripCommandService trips;
    private final WarehouseSetupCommandService warehouseSetup;
    private final InboundCommandService inbound;
    private final OutboundCommandService outbound;
    private final ExceptionCommandService exceptions;
    private final JdbcClient jdbc;

    public TenantSeeds(TestFixtures fixtures, SpineFixtures spine, FleetCommandService fleet,
                       TerminalCommandService terminals, TripCommandService trips,
                       WarehouseSetupCommandService warehouseSetup,
                       InboundCommandService inbound, OutboundCommandService outbound,
                       ExceptionCommandService exceptions, JdbcClient jdbc) {
        this.fixtures = fixtures;
        this.spine = spine;
        this.fleet = fleet;
        this.terminals = terminals;
        this.trips = trips;
        this.warehouseSetup = warehouseSetup;
        this.inbound = inbound;
        this.outbound = outbound;
        this.exceptions = exceptions;
        this.jdbc = jdbc;
    }

    /** The code of every permission, so a caller is never refused for lack of one. */
    public List<String> allPermissions() {
        return jdbc.sql("SELECT code FROM permission").query(String.class).list();
    }

    public Seeded seed(double lonOffset) {
        TripScaffold.Built built =
                TripScaffold.build(fixtures, spine, fleet, terminals, trips, lonOffset);
        UUID tenantId = built.tenantId();
        String salt = built.orgPath().replaceAll("[^a-z0-9]", "").replace("perf", "");

        Map<String, UUID> ids = new LinkedHashMap<>();
        ids.put("trip", built.tripId());
        ids.put("sales_order", one("sales_order", tenantId));
        ids.put("consignment", one("consignment", tenantId));
        ids.put("load_unit", one("load_unit", tenantId));
        String trackingRef = jdbc.sql("SELECT tracking_ref FROM consignment WHERE tenant_id = :t")
                .param("t", tenantId).query(String.class).list().get(0);
        UUID vendor = jdbc.sql("""
                        SELECT id FROM business_partner
                         WHERE tenant_id = :t AND partner_type = 'VENDOR'
                        """).param("t", tenantId).query(UUID.class).list().get(0);
        UUID client = jdbc.sql("""
                        SELECT id FROM business_partner
                         WHERE tenant_id = :t AND partner_type = 'CUSTOMER' LIMIT 1
                        """).param("t", tenantId).query(UUID.class).list().get(0);

        UUID allocation = insertAllocation(tenantId, ids.get("load_unit"), vendor);
        ids.put("allocation", allocation);
        ids.put("allocation_offer", insertOffer(tenantId, allocation, vendor));
        ids.put("freight_bill", insertFreightBill(built, vendor, salt));
        ids.put("client_invoice", insertInvoice(tenantId, client, salt));

        as(built, () -> {
            ids.put("exception_ticket", exceptions.raise(built.tripId(), ExceptionCategory.THEFT,
                    "Seal broken at the gate", null, null));

            UUID warehouse = terminals.register(new RegisterTerminalCommand.PointRadius(
                    built.orgUnitId(), "WH-" + salt, "Bhiwandi DC",
                    Terminal.FunctionalCategory.WAREHOUSE, new LatLon(19.5, 73.5 + lonOffset),
                    400, 4, null, null, 45, List.of()));
            ids.put("warehouse", warehouse);
            UUID sku = warehouseSetup.createSku("SKU-" + salt, "Widgets", null, "EA");
            UUID asn = inbound.createAsn(warehouse, "ASN-" + salt, null,
                    List.of(new InboundCommandService.ExpectedLine(sku, null, 10)));
            ids.put("asn", asn);
            // A cross-dock receipt, because it is the shortest way to a handling unit.
            ids.put("handling_unit", inbound.receive(asn,
                    List.of(new InboundCommandService.CountedLine(1, 10, 0)), true));
            ids.put("outbound_order", outbound.createOrder(warehouse, "OUT-" + salt, "Pune DC",
                    List.of(new OutboundCommandService.RequestedLine(sku, 5))));
            return null;
        });

        return new Seeded(tenantId, built.orgPath(), built.orgUnitId(), client, trackingRef, ids);
    }

    private UUID one(String table, UUID tenantId) {
        return jdbc.sql("SELECT id FROM " + table + " WHERE tenant_id = :t")
                .param("t", tenantId).query(UUID.class).list().get(0);
    }

    /**
     * Inserted rather than produced by the sourcing service, which needs a
     * routing guide and a vendor response: the isolation tests only need the
     * rows to exist and belong to somebody.
     */
    private UUID insertAllocation(UUID tenantId, UUID load, UUID vendor) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO allocation (id, tenant_id, load_id, strategy, status,
                                                current_rank, awarded_vendor_partner_id, awarded_at)
                        VALUES (:id, :t, :load, 'CONTRACTUAL', 'AWARDED', 1, :vendor, now())
                        """)
                .param("id", id).param("t", tenantId).param("load", load).param("vendor", vendor)
                .update();
        return id;
    }

    private UUID insertOffer(UUID tenantId, UUID allocation, UUID vendor) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO allocation_offer (id, tenant_id, allocation_id, rank_no,
                                                      vendor_partner_id, responds_by, outcome)
                        VALUES (:id, :t, :a, 1, :vendor, now() + interval '1 hour', 'ACCEPTED')
                        """)
                .param("id", id).param("t", tenantId).param("a", allocation)
                .param("vendor", vendor).update();
        return id;
    }

    private UUID insertFreightBill(TripScaffold.Built built, UUID vendor, String salt) {
        UUID id = UUID.randomUUID();
        UUID load = one("load_unit", built.tenantId());
        jdbc.sql("""
                        INSERT INTO freight_bill (id, tenant_id, org_unit_id, trip_id, load_id,
                                                  vendor_partner_id, bill_no)
                        VALUES (:id, :t, :ou, :trip, :load, :vendor, :no)
                        """)
                .param("id", id).param("t", built.tenantId()).param("ou", built.orgUnitId())
                .param("trip", built.tripId()).param("load", load).param("vendor", vendor)
                .param("no", "FB-" + salt).update();
        return id;
    }

    private UUID insertInvoice(UUID tenantId, UUID client, String salt) {
        UUID id = UUID.randomUUID();
        LocalDate start = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).minusMonths(1);
        jdbc.sql("""
                        INSERT INTO client_invoice (id, tenant_id, client_partner_id, invoice_no,
                                                    period_start, period_end, total_amount,
                                                    line_count, issued_at)
                        VALUES (:id, :t, :client, :no, :start, :end, 1000, 0, :issued)
                        """)
                .param("id", id).param("t", tenantId).param("client", client)
                .param("no", "INV-" + salt).param("start", start)
                .param("end", start.plusMonths(1).minusDays(1))
                .param("issued", Instant.now().atOffset(ZoneOffset.UTC)).update();
        return id;
    }

    private <T> T as(TripScaffold.Built built, Supplier<T> action) {
        Set<SimpleGrantedAuthority> authorities = allPermissions().stream()
                .map(SimpleGrantedAuthority::new).collect(Collectors.toSet());
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(SETUP_PRINCIPAL, null, authorities));
        try {
            return TenantContext.callWith(built.tenantId(), built.orgPath(), action);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
