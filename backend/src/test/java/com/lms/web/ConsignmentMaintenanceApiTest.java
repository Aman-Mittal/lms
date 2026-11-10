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

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.lms.acceptance.TestFixtures;
import com.lms.client.ApiClient;
import com.lms.client.api.OrdersApi;
import com.lms.client.api.PlanningApi;
import com.lms.client.model.AddOrderLineRequest;
import com.lms.client.model.AmendmentFieldResult;
import com.lms.client.model.AssignConsignmentRequest;
import com.lms.client.model.BulkAmendConsignmentItem;
import com.lms.client.model.BulkAmendConsignmentsRequest;
import com.lms.client.model.BulkCancelConsignmentsRequest;
import com.lms.client.model.CancelConsignmentRequest;
import com.lms.client.model.CancelOrderRequest;
import com.lms.client.model.CloseKind;
import com.lms.client.model.CloseOrderRequest;
import com.lms.client.model.Consignment;
import com.lms.client.model.ConsignmentAmended;
import com.lms.client.model.ConsignmentAmendmentRequest;
import com.lms.client.model.ConsignmentBulkResult;
import com.lms.client.model.ConsignmentCancellation;
import com.lms.client.model.ConsignmentItemResult;
import com.lms.client.model.HistoryEntry;
import com.lms.client.model.OpenLoadRequest;
import com.lms.client.model.OrderLine;
import com.lms.client.model.OrderLineCancellation;
import com.lms.client.model.RaiseOrderRequest;
import com.lms.identity.security.TokenService;
import com.lms.masterdata.command.FleetCommandService;
import com.lms.masterdata.command.RegisterTerminalCommand;
import com.lms.masterdata.command.TerminalCommandService;
import com.lms.masterdata.command.domain.BusinessPartner;
import com.lms.masterdata.command.domain.Terminal;
import com.lms.shared.geo.LatLon;
import com.lms.shared.tenant.TenantContext;
import com.lms.web.ApiTestKit.Tenant;
import feign.FeignException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Amending a consignment's particulars, cancelling one consignment, one order
 * line, or many consignments at once, over HTTP through the client generated
 * from the contract.
 *
 * <p>What only the wire shows: the version travelling as ETag and If-Match,
 * the per-field and per-item results, every refusal's code, a load restated
 * from what is left on it, an order stepping back to be planned again, and the
 * consignment's history and events recording each step.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConsignmentMaintenanceApiTest {

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

    private static final String[] PERMISSIONS = {"ORDER_CREATE", "ORDER_READ", "ORDER_UPDATE",
            "LOAD_CREATE", "LOAD_READ"};

    /** What the in-process setup needs. Never put in a token. */
    private static final List<String> SETUP = List.of("PARTNER_CREATE", "PARTNER_UPDATE",
            "TERMINAL_CREATE", "VEHICLE_CREATE");

    /** A tenant with somewhere to send freight from and to, two consignees and a lorry. */
    private record Network(Tenant tenant, UUID customer, UUID consignee, UUID otherConsignee,
                           UUID origin, UUID destination, UUID lorry) {
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private TokenService tokens;
    @Autowired
    private FleetCommandService fleet;
    @Autowired
    private TerminalCommandService terminals;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private ApiTestKit kit;
    private Network acme;

    @BeforeEach
    void aNetwork() {
        kit = new ApiTestKit(fixtures, tokens, port);
        acme = network("acme");
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    // -------------------------------------------------------------- amending

    @Test
    @DisplayName("particulars are amended under If-Match, field by field, and a stale version changes nothing")
    void amendParticulars() throws Exception {
        PlanningApi planning = client(PlanningApi.class);
        UUID id = consignmentsOf(order(acme.consignee())).getFirst();
        Consignment read = planning.getConsignment(id);
        long version = read.getVersion();
        assertThat(etagOf("/api/v1/consignments/" + id)).isEqualTo("\"" + version + "\"");

        ConsignmentAmended amended = planning.amendConsignment(String.valueOf(version), id,
                new ConsignmentAmendmentRequest().contactName("Ravi at the gate").contactPhone("+91 98200 12345")
                        .clientReference("PO-7781/A").deliveryWindowFrom("2026-12-01T06:00:00Z")
                        .deliveryWindowTo("2026-12-01T14:00:00Z").remarks("Tail-lift needed"), null);

        assertThat(amended.getVersion()).isGreaterThan(version);
        assertThat(amended.getFields()).extracting(AmendmentFieldResult::getField).containsExactlyInAnyOrder(
                "contactName", "contactPhone", "clientReference", "deliveryWindowFrom", "deliveryWindowTo",
                "remarks");
        assertThat(amended.getFields()).extracting(f -> f.getOutcome().getValue()).containsOnly("APPLIED");
        Consignment after = planning.getConsignment(id);
        assertThat(after.getContactName()).isEqualTo("Ravi at the gate");
        assertThat(after.getClientReference()).isEqualTo("PO-7781/A");
        assertThat(after.getDeliveryWindowTo().toInstant()).isEqualTo(Instant.parse("2026-12-01T14:00:00Z"));
        assertThat(after.getTotalDeadWeightKg()).isEqualByComparingTo(read.getTotalDeadWeightKg());

        ConsignmentAmended again = planning.amendConsignment("\"" + amended.getVersion() + "\"", id,
                new ConsignmentAmendmentRequest().contactName("Ravi at the gate"), null);
        assertThat(again.getFields()).singleElement()
                .satisfies(f -> assertThat(f.getOutcome().getValue()).isEqualTo("UNCHANGED"));
        assertThat(again.getVersion()).isEqualTo(amended.getVersion());

        assertConflict(() -> planning.amendConsignment(String.valueOf(version), id,
                new ConsignmentAmendmentRequest().remarks("late"), null), "concurrent-modification");
        assertThat(planning.getConsignmentHistory(id, null, null).getItems())
                .extracting(HistoryEntry::getAction).startsWith("CONSIGNMENT_AMENDED");
    }

    @Test
    @DisplayName("contents are fixed, every bad field is named, and nothing of a refused amendment is applied")
    void refusedAmendments() {
        PlanningApi planning = client(PlanningApi.class);
        UUID id = consignmentsOf(order(acme.consignee())).getFirst();
        long version = planning.getConsignment(id).getVersion();

        ApiTestKit.Outcome fixed = patch(id, String.valueOf(version),
                "{\"contactName\":\"Asha\",\"totalDeadWeightKg\":5,\"lines\":[]}");
        assertThat(fixed.status()).isEqualTo(409);
        JsonNode problem = kit.parse(fixed);
        assertThat(problem.get("code").asString()).isEqualTo("consignment-contents-fixed");
        assertThat(problem.get("errors").propertyNames()).containsExactlyInAnyOrder("totalDeadWeightKg", "lines");

        ApiTestKit.Outcome invalid = patch(id, String.valueOf(version), """
                {"contactPhone":"call me","status":"DELIVERED",
                 "deliveryWindowFrom":"2026-12-02T10:00:00Z","deliveryWindowTo":"2026-12-01T10:00:00Z"}""");
        assertThat(invalid.status()).isEqualTo(409);
        problem = kit.parse(invalid);
        assertThat(problem.get("code").asString()).isEqualTo("consignment-amendment-invalid");
        assertThat(problem.get("errors").propertyNames()).containsExactlyInAnyOrder("contactPhone", "status",
                "deliveryWindowFrom", "deliveryWindowTo");

        Consignment untouched = planning.getConsignment(id);
        assertThat(untouched.getContactName()).isNull();
        assertThat(untouched.getVersion()).isEqualTo(version);

        assertThat(patch(id, String.valueOf(version), "{\"remarks\":\"x\"}").status()).isEqualTo(200);
        ApiTestKit.Outcome kept = patch(id, String.valueOf(version + 1), "{\"remarks\":null,\"contactName\":\"Asha\"}");
        assertThat(kept.status()).isEqualTo(200);
        assertThat(kit.parse(kept).get("fields")).hasSize(1);
        assertThat(planning.getConsignment(id).getRemarks()).as("null keeps a field").isEqualTo("x");
        ApiTestKit.Outcome cleared = patch(id, String.valueOf(version + 2), "{\"remarks\":\"\"}");
        assertThat(cleared.status()).isEqualTo(200);
        assertThat(planning.getConsignment(id).getRemarks()).as("empty text clears it").isNull();

        assertThat(patch(id, null, "{\"remarks\":\"x\"}").status()).isEqualTo(400);
        assertThat(patch(id, String.valueOf(version + 3), "{}").status()).isEqualTo(400);
        assertThat(patch(UUID.randomUUID(), "1", "{\"remarks\":\"x\"}").code()).isEqualTo("not-found");
    }

    @Test
    @DisplayName("a client reference names one consignment in a tenant, and is free in another")
    void clientReferenceIsUnique() {
        PlanningApi planning = client(PlanningApi.class);
        List<UUID> two = consignmentsOf(order(acme.consignee(), acme.otherConsignee()));
        amend(two.get(0), new ConsignmentAmendmentRequest().clientReference("CLIENT-42"));
        assertConflict(() -> amend(two.get(1), new ConsignmentAmendmentRequest().clientReference("CLIENT-42")),
                "client-reference-taken");
        // Setting it again on the one that holds it is no conflict.
        amend(two.get(0), new ConsignmentAmendmentRequest().clientReference("CLIENT-42"));

        Network globex = network("globex");
        Network saved = acme;
        acme = globex;
        try {
            UUID theirs = consignmentsOf(order(globex.consignee())).getFirst();
            amend(theirs, new ConsignmentAmendmentRequest().clientReference("CLIENT-42"));
            assertThat(planning(globex).getConsignment(theirs).getClientReference()).isEqualTo("CLIENT-42");
        } finally {
            acme = saved;
        }
        assertThat(planning.getConsignment(two.get(1)).getClientReference()).isNull();
    }

    // ------------------------------------------------------------ cancelling

    @Test
    @DisplayName("cancelling a consignment on a load takes it off, restates the load and gives its lines back")
    void cancelOffALoad() {
        PlanningApi planning = client(PlanningApi.class);
        OrdersApi orders = client(OrdersApi.class);
        UUID orderId = order(acme.consignee(), acme.otherConsignee());
        List<UUID> two = consignmentsOf(orderId);
        UUID load = planning.openLoad(new OpenLoadRequest().orgUnitId(acme.tenant().orgUnitId())
                .loadNo("LD-" + UUID.randomUUID().toString().substring(0, 6)).originTerminalId(acme.origin())
                .vehicleId(acme.lorry()), null).getId();
        two.forEach(c -> planning.assignConsignment(load, new AssignConsignmentRequest().consignmentId(c), null));
        assertThat(orders.getOrder(orderId).getStatus()).isEqualTo("FULLY_PLANNED");

        Consignment kept = planning.getConsignment(two.get(1));
        assertConflict(() -> amend(two.get(0), new ConsignmentAmendmentRequest().remarks("on a load")),
                "consignment-not-amendable");

        ConsignmentCancellation first = planning.cancelConsignment(two.get(0), null,
                new CancelConsignmentRequest().reason("Consignee closed for stocktake"));
        assertThat(first.getLoad()).isNotNull();
        assertThat(first.getLoad().getLoadId()).isEqualTo(load);
        assertThat(first.getLoad().getStatus()).isEqualTo("DRAFT");
        assertThat(first.getLoad().getPlannedWeightKg()).isEqualByComparingTo(kept.getTotalDeadWeightKg());
        assertThat(first.getReleasedLineIds()).hasSize(1);
        assertThat(planning.getConsignment(two.get(0)).getStatus()).isEqualTo("CANCELLED");
        assertThat(planning.listLoadConsignments(load)).extracting(Consignment::getId).containsExactly(two.get(1));
        assertThat(orders.getOrder(orderId).getStatus()).isEqualTo("PARTIALLY_PLANNED");
        assertThat(orders.listOrderLines(orderId)).filteredOn(l -> !l.getPlanned())
                .extracting(OrderLine::getId).containsExactlyElementsOf(first.getReleasedLineIds());

        assertConflict(() -> planning.cancelConsignment(two.get(0), null, new CancelConsignmentRequest()), "consignment-already-cancelled");
        assertThat(planning.getConsignmentHistory(two.get(0), null, null).getItems())
                .extracting(HistoryEntry::getAction).startsWith("CONSIGNMENT_CANCELLED");

        ConsignmentCancellation last = planning.cancelConsignment(two.get(1), null, new CancelConsignmentRequest());
        assertThat(last.getLoad().getStatus()).as("a load left empty is cancelled").isEqualTo("CANCELLED");
        assertThat(last.getLoad().getPlannedWeightKg()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(orders.getOrder(orderId).getStatus()).isEqualTo("VALIDATED");

        List<UUID> again = consignmentsOf(orderId);
        assertThat(again).as("the freed lines are planned again").hasSize(2).doesNotContainAnyElementsOf(two);
        assertThat(orders.getOrder(orderId).getStatus()).isEqualTo("FULLY_PLANNED");

        List<String> events = jdbc.sql("""
                        SELECT event_type FROM event_publication WHERE serialized_event LIKE :id
                        """)
                .param("id", "%" + two.get(0) + "%").query(String.class).list();
        assertThat(events).anyMatch(t -> t.endsWith(".ConsignmentCancelled"));
    }

    @Test
    @DisplayName("cancelling a consignment takes it off its load as a removal does: drops renumbered, removal audited")
    void cancelSharesTheRemoval() {
        PlanningApi planning = client(PlanningApi.class);
        UUID orderId = order(acme.consignee(), acme.otherConsignee(), acme.customer());
        List<UUID> three = consignmentsOf(orderId);
        UUID load = planning.openLoad(new OpenLoadRequest().orgUnitId(acme.tenant().orgUnitId())
                .loadNo("LD-" + UUID.randomUUID().toString().substring(0, 6)).originTerminalId(acme.origin())
                .vehicleId(acme.lorry()), null).getId();
        three.forEach(c -> planning.assignConsignment(load, new AssignConsignmentRequest().consignmentId(c), null));
        String trackingRef = planning.getConsignment(three.get(0)).getTrackingRef();

        planning.cancelConsignment(three.get(0), null, new CancelConsignmentRequest().reason("Not wanted"));

        assertThat(planning.listLoadConsignments(load)).extracting(Consignment::getId)
                .containsExactly(three.get(1), three.get(2));
        assertThat(dropSequences(load)).as("no gap where the cancelled one was").containsExactly(1, 2);
        HistoryEntry removed = planning.getLoadHistory(load, null, null).getItems().get(0);
        assertThat(removed.getAction()).isEqualTo("LOAD_CONSIGNMENT_REMOVED");
        assertThat(removed.getBefore()).containsEntry("consignmentCount", "3");
        assertThat(removed.getAfter()).containsEntry("consignmentCount", "2")
                .containsEntry("trackingRef", trackingRef);
    }

    @Test
    @DisplayName("a cancelled line is not closed and a closed one is not cancelled, and neither holds up a close")
    void closeAndCancelRespectEachOther() {
        OrdersApi orders = client(OrdersApi.class);
        UUID orderId = order(acme.consignee(), acme.otherConsignee(), acme.customer());
        List<OrderLine> lines = orders.listOrderLines(orderId);

        orders.cancelOrderLine(String.valueOf(orders.getOrder(orderId).getVersion()), orderId,
                lines.get(0).getId(), null, new CancelOrderRequest());
        assertConflict(() -> orders.closeOrderLine(orderId, lines.get(0).getId(), null,
                new CloseOrderRequest().kind(CloseKind.SHORT).reason("not needed")), "order-line-already-cancelled");

        orders.closeOrderLine(orderId, lines.get(1).getId(), null,
                new CloseOrderRequest().kind(CloseKind.SHORT).reason("not needed"));
        long version = orders.getOrder(orderId).getVersion();
        assertConflict(() -> orders.cancelOrderLine(String.valueOf(version), orderId, lines.get(1).getId(), null,
                new CancelOrderRequest()), "order-line-already-closed");
        assertConflict(() -> orders.cancelOrderLine(String.valueOf(version), orderId, lines.get(2).getId(), null,
                new CancelOrderRequest()), "order-line-last");

        orders.closeOrder(orderId, null, new CloseOrderRequest().kind(CloseKind.SHORT).reason("done with it"));
        assertThat(orders.getOrder(orderId).getStatus()).isEqualTo("CLOSED");
        List<OrderLine> after = orders.listOrderLines(orderId);
        assertThat(after.get(0).getCancelled()).isTrue();
        assertThat(after.get(0).getCloseKind()).as("a cancelled line is not closed with its order").isNull();
        assertThat(after.get(2).getCloseKind()).isEqualTo(CloseKind.SHORT);
    }

    @Test
    @DisplayName("bulk cancellation and amendment answer per item, in the order asked")
    void bulkPerItem() {
        PlanningApi planning = client(PlanningApi.class);
        List<UUID> two = consignmentsOf(order(acme.consignee(), acme.otherConsignee()));
        planning.cancelConsignment(two.get(1), null, new CancelConsignmentRequest());
        UUID nobody = UUID.randomUUID();

        ConsignmentBulkResult amended = planning.amendConsignments(new BulkAmendConsignmentsRequest().items(List.of(
                new BulkAmendConsignmentItem().consignmentId(two.get(0))
                        .version(planning.getConsignment(two.get(0)).getVersion())
                        .changes(new ConsignmentAmendmentRequest().remarks("Fragile")),
                new BulkAmendConsignmentItem().consignmentId(two.get(1))
                        .version(planning.getConsignment(two.get(1)).getVersion())
                        .changes(new ConsignmentAmendmentRequest().remarks("Fragile")),
                new BulkAmendConsignmentItem().consignmentId(nobody).version(0L)
                        .changes(new ConsignmentAmendmentRequest().remarks("Fragile")))), null);
        assertThat(amended.getItems()).extracting(ConsignmentItemResult::getConsignmentId)
                .containsExactly(two.get(0), two.get(1), nobody);
        assertThat(amended.getItems()).extracting(i -> i.getOutcome().getValue())
                .containsExactly("AMENDED", "REFUSED", "REFUSED");
        assertThat(amended.getItems()).extracting(ConsignmentItemResult::getCode)
                .containsExactly(null, "consignment-not-amendable", "not-found");
        assertThat(planning.getConsignment(two.get(0)).getRemarks()).isEqualTo("Fragile");

        ConsignmentBulkResult cancelled = planning.cancelConsignments(new BulkCancelConsignmentsRequest()
                .consignmentIds(List.of(two.get(0), two.get(1), nobody)).reason("Order shrank"), null);
        assertThat(cancelled.getItems()).extracting(i -> i.getOutcome().getValue())
                .containsExactly("CANCELLED", "REFUSED", "REFUSED");
        assertThat(cancelled.getItems()).extracting(ConsignmentItemResult::getCode)
                .containsExactly(null, "consignment-already-cancelled", "not-found");
        assertThat(planning.getConsignment(two.get(0)).getStatus()).isEqualTo("CANCELLED");

        assertThatThrownBy(() -> planning.cancelConsignments(new BulkCancelConsignmentsRequest()
                .consignmentIds(List.of(nobody, nobody)), null))
                .isInstanceOfSatisfying(FeignException.class, e -> assertThat(e.status()).isEqualTo(400));
    }

    @Test
    @DisplayName("without the right to build loads, a consignment is neither amended nor cancelled")
    void rightsGated() {
        UUID id = consignmentsOf(order(acme.consignee())).getFirst();
        PlanningApi reader = client(kit.token(acme.tenant(), "LOAD_READ", "ORDER_READ"), PlanningApi.class);
        long version = reader.getConsignment(id).getVersion();
        assertThatThrownBy(() -> reader.amendConsignment(String.valueOf(version), id,
                new ConsignmentAmendmentRequest().remarks("x"), null))
                .isInstanceOfSatisfying(FeignException.class, e -> assertThat(e.status()).isEqualTo(403));
        assertThatThrownBy(() -> reader.cancelConsignment(id, null, new CancelConsignmentRequest()))
                .isInstanceOfSatisfying(FeignException.class, e -> assertThat(e.status()).isEqualTo(403));
        assertThat(reader.getConsignment(id).getStatus()).isEqualTo("PLANNED");
    }

    // ------------------------------------------------------------ order lines

    @Test
    @DisplayName("cancelling a planned line cancels its consignment and frees the lines that shared it")
    void cancelAnOrderLine() {
        OrdersApi orders = client(OrdersApi.class);
        PlanningApi planning = client(PlanningApi.class);
        UUID orderId = order(acme.consignee(), acme.consignee());
        UUID consignment = consignmentsOf(orderId).getFirst();
        List<OrderLine> lines = orders.listOrderLines(orderId);
        long version = orders.getOrder(orderId).getVersion();

        assertConflict(() -> orders.cancelOrderLine(String.valueOf(version - 1), orderId, lines.get(0).getId(),
                null, new CancelOrderRequest()), "concurrent-modification");
        OrderLineCancellation done = orders.cancelOrderLine(String.valueOf(version), orderId,
                lines.get(0).getId(), null, new CancelOrderRequest().reason("Customer dropped it"));

        assertThat(done.getCancelledConsignmentIds()).containsExactly(consignment);
        assertThat(done.getReleasedLineIds()).containsExactly(lines.get(1).getId());
        assertThat(planning.getConsignment(consignment).getStatus()).isEqualTo("CANCELLED");
        assertThat(orders.getOrder(orderId).getStatus()).isEqualTo("VALIDATED");
        assertThat(orders.getOrder(orderId).getLineCount()).isEqualTo(1);
        assertThat(orders.listOrderLines(orderId)).extracting(OrderLine::getCancelled).containsExactly(true, false);

        List<UUID> regenerated = consignmentsOf(orderId);
        assertThat(regenerated).singleElement().satisfies(id -> assertThat(planning.getConsignment(id)
                .getTotalDeadWeightKg()).isEqualByComparingTo(lines.get(1).getDeadWeightKg()));

        long now = orders.getOrder(orderId).getVersion();
        assertConflict(() -> orders.cancelOrderLine(String.valueOf(now), orderId, lines.get(1).getId(), null, new CancelOrderRequest()),
                "order-line-last");
        assertConflict(() -> orders.cancelOrderLine(String.valueOf(now), orderId, lines.get(0).getId(), null, new CancelOrderRequest()),
                "order-line-already-cancelled");
        assertThat(orders.getOrderHistory(orderId, null, null).getItems())
                .extracting(HistoryEntry::getAction).contains("ORDER_LINE_CANCELLED");
    }

    @Test
    @DisplayName("a draft order's line is removed, not cancelled")
    void draftLinesAreNotCancelled() {
        OrdersApi orders = client(OrdersApi.class);
        UUID orderId = orders.raiseOrder(raise(acme), null).getId();
        orders.addOrderLine(orderId, line(acme, 1, acme.consignee()), null);
        orders.addOrderLine(orderId, line(acme, 2, acme.consignee()), null);
        UUID first = orders.listOrderLines(orderId).getFirst().getId();
        long version = orders.getOrder(orderId).getVersion();
        assertConflict(() -> orders.cancelOrderLine(String.valueOf(version), orderId, first, null, new CancelOrderRequest()),
                "order-not-validated");
    }

    // ---------------------------------------------------------------- helpers

    private List<Integer> dropSequences(UUID load) {
        return TenantContext.callWith(acme.tenant().tenantId(), acme.tenant().orgPath(),
                () -> new TransactionTemplate(transactionManager).execute(status -> jdbc.sql("""
                                SELECT drop_sequence FROM load_consignment
                                 WHERE tenant_id = :tenant AND load_id = :load ORDER BY drop_sequence
                                """)
                        .param("tenant", acme.tenant().tenantId()).param("load", load)
                        .query(Integer.class).list()));
    }

    /** A validated order with one line per consignee given. */
    private UUID order(UUID... consignees) {
        OrdersApi orders = client(OrdersApi.class);
        UUID id = orders.raiseOrder(raise(acme), null).getId();
        for (int i = 0; i < consignees.length; i++) {
            orders.addOrderLine(id, line(acme, i + 1, consignees[i]), null);
        }
        orders.validateOrder(id, null);
        return id;
    }

    private List<UUID> consignmentsOf(UUID orderId) {
        return client(PlanningApi.class).generateConsignments(orderId, null).stream()
                .map(com.lms.client.model.IdResponse::getId).toList();
    }

    private ConsignmentAmended amend(UUID id, ConsignmentAmendmentRequest request) {
        PlanningApi planning = client(PlanningApi.class);
        return planning.amendConsignment(String.valueOf(planning.getConsignment(id).getVersion()), id, request, null);
    }

    private PlanningApi planning(Network network) {
        return client(kit.token(network.tenant(), PERMISSIONS), PlanningApi.class);
    }

    private ApiTestKit.Outcome patch(UUID id, String ifMatch, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/api/v1/consignments/" + id))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + kit.token(acme.tenant(), PERMISSIONS))
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body));
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return kit.send(request.build());
    }

    private String etagOf(String path) throws Exception {
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + path))
                    .header("Authorization", "Bearer " + kit.token(acme.tenant(), PERMISSIONS)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.headers().firstValue("ETag").orElse(null);
        }
    }

    private static RaiseOrderRequest raise(Network network) {
        return new RaiseOrderRequest().orgUnitId(network.tenant().orgUnitId())
                .customerPartnerId(network.customer()).originTerminalId(network.origin());
    }

    private static AddOrderLineRequest line(Network network, int lineNo, UUID consignee) {
        return new AddOrderLineRequest().lineNo(lineNo).materialCode("MAT-" + lineNo)
                .materialDescription("General cargo").materialClass("GENERAL")
                .quantity(BigDecimal.ONE).uom("EA").deadWeightKg(new BigDecimal(1000 * lineNo))
                .consigneePartnerId(consignee).destinationTerminalId(network.destination());
    }

    private static void assertConflict(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(FeignException.class, e -> {
            assertThat(e.status()).isEqualTo(409);
            assertThat(e.contentUTF8()).contains(code);
        });
    }

    private <T extends ApiClient.Api> T client(Class<T> api) {
        return client(kit.token(acme.tenant(), PERMISSIONS), api);
    }

    private <T extends ApiClient.Api> T client(String token, Class<T> api) {
        ApiClient apiClient = new ApiClient().setBasePath("http://localhost:" + port);
        apiClient.getFeignBuilder().requestInterceptor(template ->
                template.header("Authorization", "Bearer " + token));
        return apiClient.buildClient(api);
    }

    private Network network(String prefix) {
        Tenant tenant = kit.newTenant(prefix);
        String salt = UUID.randomUUID().toString().substring(0, 6);
        return asSetup(tenant, () -> new Network(tenant,
                partner(tenant, "CUST"), partner(tenant, "CONS"), partner(tenant, "CON2"),
                depot(tenant, "ORG", 34.10, 81.10), depot(tenant, "DST", 34.40, 81.40),
                fleet.registerVehicle(tenant.orgUnitId(), null, "CM-" + salt, "TRUCK", "RIGID", "3-AXLE",
                        new BigDecimal("36000"), new BigDecimal("6000"), new BigDecimal("90"), false, false)));
    }

    private UUID partner(Tenant tenant, String code) {
        return fleet.registerPartner(tenant.orgUnitId(), code, code, BusinessPartner.PartnerType.CUSTOMER,
                "TAX-" + code + "-" + tenant.tenantId().toString().substring(0, 8));
    }

    private UUID depot(Tenant tenant, String code, double lat, double lon) {
        return terminals.register(new RegisterTerminalCommand.PointRadius(tenant.orgUnitId(), code, code,
                Terminal.FunctionalCategory.CUSTOMER_SITE, new LatLon(lat, lon), 500, 2, null,
                null, 45, List.of()));
    }

    /** Runs setup scoped to the tenant and signed in with what the setup needs. */
    private <T> T asSetup(Tenant tenant, Supplier<T> action) {
        Set<SimpleGrantedAuthority> authorities = SETUP.stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toSet());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("setup", "n/a", authorities));
        try {
            return TenantContext.callWith(tenant.tenantId(), tenant.orgPath(), action);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
