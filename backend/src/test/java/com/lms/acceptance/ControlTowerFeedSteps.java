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
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.lms.controltower.query.ControlTowerQueryService;
import com.lms.controltower.query.ExceptionSummaryView;
import com.lms.controltower.query.ExceptionView;
import com.lms.multimodal.events.ConnectionMissed;
import com.lms.shared.tenant.TenantContext;
import com.lms.warehouse.events.ReceiptVarianceRecorded;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step definitions for the tickets the control tower raises from other
 * modules' events.
 *
 * <p>The scenarios live in the features of the modules that publish -- a
 * missed connection needs a multimodal itinerary, a skipped supplier a milk
 * run -- and these steps only look at what reached the board. Subjects are
 * found through the references those modules' steps registered.
 */
public class ControlTowerFeedSteps {

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private ControlTowerQueryService controlTower;
    @Autowired
    private MultimodalEventProbe multimodal;
    @Autowired
    private WarehouseEventProbe warehouse;
    @Autowired
    private ApplicationEventPublisher publisher;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    /** What the last "holds" step found, for the steps that describe it. */
    private List<ExceptionView> found = List.of();
    private DataIntegrityViolationException refusal;

    // ------------------------------------------------------------ redelivery

    /** Delivers the very same event again, in its own committed transaction. */
    @When("the missed connection on leg {int} of shipment {string} is delivered to the control tower again")
    public void missedConnectionRedelivered(int seq, String shipment) {
        UUID itineraryId = world.ref("itinerary:" + shipment);
        List<ConnectionMissed> heard = multimodal.missed().stream()
                .filter(event -> event.itineraryId().equals(itineraryId) && event.legSeq() == seq)
                .toList();
        assertThat(heard).as("missed connections heard on leg %d", seq).isNotEmpty();
        heard.forEach(this::redeliver);
    }

    @When("the receipt variances of ASN {string} are delivered to the control tower again")
    public void variancesRedelivered(String asnNo) {
        UUID asnId = world.ref("asn:" + asnNo);
        List<ReceiptVarianceRecorded> heard = warehouse.heard(ReceiptVarianceRecorded.class)
                .stream()
                .filter(event -> event.asnId().equals(asnId))
                .toList();
        assertThat(heard).as("receipt variances heard for %s", asnNo).isNotEmpty();
        heard.forEach(this::redeliver);
    }

    // ---------------------------------------------------- the database's word

    /**
     * Goes round the service on purpose: the rule is meant to hold whoever
     * writes the row.
     */
    @When("a P2 ticket about no trip is written straight to the database holding settlement")
    public void holdWithoutTripWrittenDirectly() {
        UUID tenantId = TenantContext.requireTenantId();
        refusal = null;
        try {
            inTransaction(() -> jdbc.sql("""
                            INSERT INTO exception_ticket (id, tenant_id, ticket_no, subject_type,
                                   subject_id, subject_label, category, severity, status,
                                   description, source, source_key, position_source,
                                   suspends_settlement, raised_by, raised_at, version)
                            VALUES (:id, :tenantId, :ticketNo, 'ITINERARY', :subjectId, 'SHP-X',
                                    'MISSED_CONNECTION', 'P2', 'RAISED', 'forced',
                                    'MISSED_CONNECTION', :sourceKey, 'NONE', TRUE, 'test',
                                    :at, 0)
                            """)
                    .param("id", UUID.randomUUID())
                    .param("tenantId", tenantId)
                    .param("ticketNo", "EX-" + UUID.randomUUID().toString().substring(0, 8))
                    .param("subjectId", UUID.randomUUID())
                    .param("sourceKey", "forced:" + UUID.randomUUID())
                    .param("at", Instant.now().atOffset(ZoneOffset.UTC))
                    .update());
        } catch (DataIntegrityViolationException e) {
            refusal = e;
        }
    }

    @Then("the database refuses it as a hold without a trip")
    public void databaseRefused() {
        assertThat(refusal).as("the database should have refused the row").isNotNull();
        assertThat(refusal.getMessage()).contains("exception_ticket_hold_matches_severity");
    }

    // ------------------------------------------------------------ assertions

    @Then("the control tower holds {int} {string} ticket(s) about {word} {string}")
    public void holds(int count, String category, String kind, String name) {
        Subject subject = Subject.of(kind);
        UUID subjectId = world.ref(subject.refPrefix() + name);
        found = inTransaction(() -> controlTower.list(null, null, null, 200).items().stream()
                .filter(ticket -> subjectId.equals(ticket.subjectId()))
                .filter(ticket -> ticket.category().equals(category))
                .toList());
        assertThat(found).as("%s tickets about %s %s", category, kind, name).hasSize(count);
        assertThat(found).allSatisfy(ticket -> {
            assertThat(ticket.subjectType()).isEqualTo(subject.type());
            assertThat(ticket.subjectLabel()).isNotBlank();
            assertThat(ticket.raisedBy()).isEqualTo("system");
            assertThat(ticket.status()).isEqualTo("RAISED");
            assertThat(ticket.positionSource()).isEqualTo("NONE");
        });
    }

    @Then("that/those ticket(s) is/are of severity {string} on trip {string}")
    public void onTrip(String severity, String tripNo) {
        UUID tripId = tripId(tripNo);
        assertThat(found).isNotEmpty().allSatisfy(ticket -> {
            assertThat(ticket.severity()).isEqualTo(severity);
            assertThat(ticket.tripId()).isEqualTo(tripId);
            assertThat(ticket.tripNo()).isNotBlank();
        });
    }

    @Then("that/those ticket(s) is/are of severity {string} on no trip")
    public void onNoTrip(String severity) {
        assertThat(found).isNotEmpty().allSatisfy(ticket -> {
            assertThat(ticket.severity()).isEqualTo(severity);
            assertThat(ticket.tripId()).isNull();
            assertThat(ticket.tripNo()).isNull();
        });
    }

    @Then("that/those ticket(s) suspend(s) settlement")
    public void suspends() {
        assertThat(found).isNotEmpty()
                .allSatisfy(ticket -> assertThat(ticket.suspendsSettlement()).isTrue());
    }

    @Then("that/those ticket(s) do(es) not suspend settlement")
    public void doesNotSuspend() {
        assertThat(found).isNotEmpty()
                .allSatisfy(ticket -> assertThat(ticket.suspendsSettlement()).isFalse());
    }

    @Then("the board counts {int} open ticket(s) without a trip and {int} holding settlement")
    public void boardWithoutTrip(int withoutTrip, int holding) {
        ExceptionSummaryView summary = inTransaction(controlTower::summary);
        assertThat(summary.openWithoutTrip()).isEqualTo(withoutTrip);
        assertThat(summary.settlementHolds()).isEqualTo(holding);
    }

    // --------------------------------------------------------------- helpers

    /** How a scenario names a subject, and where its steps registered it. */
    private record Subject(String type, String refPrefix) {

        static Subject of(String kind) {
            return switch (kind) {
                case "shipment" -> new Subject("ITINERARY", "itinerary:");
                case "circuit" -> new Subject("CIRCUIT_RUN", "circuit:");
                case "ASN" -> new Subject("ASN", "asn:");
                case "order" -> new Subject("OUTBOUND_ORDER", "outbound:");
                default -> throw new IllegalArgumentException("No subject kind " + kind);
            };
        }
    }

    private void redeliver(Object event) {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> publisher.publishEvent(event));
    }

    private UUID tripId(String tripNo) {
        try {
            return world.ref("trip:" + tripNo);
        } catch (IllegalStateException unnamed) {
            return inTransaction(() -> jdbc.sql("SELECT id FROM trip WHERE trip_no = :tripNo")
                    .param("tripNo", world.uniqueCode(tripNo))
                    .query(UUID.class)
                    .single());
        }
    }

    private <T> T inTransaction(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }
}
