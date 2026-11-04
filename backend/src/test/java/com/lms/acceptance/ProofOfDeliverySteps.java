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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.lms.clientbilling.command.ConsignmentBillingService;
import com.lms.clientbilling.query.ClientBillingQueryService;
import com.lms.clientbilling.query.ClientChargeView;
import com.lms.delivery.command.domain.GoodsCondition;
import com.lms.delivery.events.DeliveryProofRecorded;
import com.lms.finance.command.BillingService;
import com.lms.finance.query.FinanceQueryService;
import com.lms.finance.query.FreightBillView;
import com.lms.planning.command.ConsignmentRepository;
import com.lms.shared.tenant.TenantContext;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Step definitions for {@code proof_of_delivery.feature}. */
public class ProofOfDeliverySteps {

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private ProofFixtures proofs;
    @Autowired
    private BillingService billing;
    @Autowired
    private FinanceQueryService finance;
    @Autowired
    private ConsignmentBillingService consignmentBilling;
    @Autowired
    private ClientBillingQueryService clientQueries;
    @Autowired
    private ConsignmentRepository consignments;
    @Autowired
    private ApplicationEventPublisher publisher;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private RuntimeException failure;

    // ------------------------------------------------------------- proving

    @When("a clean proof of delivery is recorded for every consignment on trip {string}")
    public void proveTripClean(String tripNo) {
        proofs.proveTrip(trip(tripNo), GoodsCondition.CLEAN, null);
    }

    @When("a proof of delivery recording a {word} because {string} is recorded for every consignment on trip {string}")
    public void proveTripWithException(String condition, String remark, String tripNo) {
        proofs.proveTrip(trip(tripNo), GoodsCondition.valueOf(condition), remark);
    }

    @When("a clean proof of delivery is recorded for the shipment of order {string} for {string}")
    public void proveShipment(String orderNo, String consigneeCode) {
        proofs.prove(shipment(orderNo, consigneeCode), GoodsCondition.CLEAN, null);
    }

    /** What the outbox does after a restart: the same fact, delivered again. */
    @When("the proof of delivery for the shipment of order {string} for {string} is announced again")
    public void announceAgain(String orderNo, String consigneeCode) {
        UUID consignmentId = shipment(orderNo, consigneeCode);
        UUID tenantId = TenantContext.requireTenantId();
        inTransaction(() -> {
            publisher.publishEvent(new DeliveryProofRecorded(tenantId, consignmentId,
                    Instant.now(), Instant.now(), "CLEAN"));
            return null;
        });
    }

    // --------------------------------------------------- the carrier's money

    @When("the proof hold on {string} is released because {string}")
    public void releaseBecause(String billNo, String reason) {
        release(billNo, reason);
    }

    @When("the proof hold on {string} is released with no reason given")
    public void releaseWithoutReason(String billNo) {
        release(billNo, " ");
    }

    @Then("bill {string} is awaiting proofs of delivery")
    public void awaitingProof(String billNo) {
        assertThat(bill(billNo).awaitingProof()).isTrue();
    }

    @Then("bill {string} is not awaiting proofs of delivery")
    public void notAwaitingProof(String billNo) {
        assertThat(bill(billNo).awaitingProof()).isFalse();
    }

    @Then("bill {string} shows the proof hold released because {string}")
    public void releasedBecause(String billNo, String reason) {
        FreightBillView view = bill(billNo);
        assertThat(view.awaitingProof()).isFalse();
        assertThat(view.proofHoldReleaseNote()).isEqualTo(reason);
    }

    @Then("releasing the proof hold is refused mentioning {string}")
    public void releaseRefused(String fragment) {
        assertThat(failure).as("expected a refusal").isNotNull();
        assertThat(failure).hasMessageContaining(fragment);
    }

    @Then("releasing the proof hold is refused as unauthorised")
    public void releaseUnauthorised() {
        assertThat(failure).isInstanceOf(AccessDeniedException.class);
    }

    @Then("the release of the proof hold on {string} is audited with the reason {string}")
    public void releaseAudited(String billNo, String reason) {
        List<String> reasons = inTransaction(() -> jdbc.sql("""
                        SELECT after_state ->> 'reason' FROM audit_log
                         WHERE action = 'SETTLEMENT_PROOF_HOLD_RELEASED' AND resource_id = :bill
                        """)
                .param("bill", world.ref("bill:" + billNo))
                .query(String.class)
                .list());
        assertThat(reasons).containsExactly(reason);
    }

    // ------------------------------------------------------ the client's money

    @When("every consignment on trip {string} is marked billable because {string}")
    public void markBillable(String tripNo, String reason) {
        failure = null;
        try {
            for (UUID consignmentId : proofs.consignmentsOnTrip(trip(tripNo))) {
                consignmentBilling.markBillable(consignmentId, reason);
            }
        } catch (RuntimeException e) {
            failure = e;
        }
    }

    @Then("marking billable is refused mentioning {string}")
    public void markRefused(String fragment) {
        assertThat(failure).as("expected a refusal").isNotNull();
        assertThat(failure).hasMessageContaining(fragment);
    }

    @Then("the marks on trip {string} are audited once each with the reason {string}")
    public void marksAudited(String tripNo, String reason) {
        List<UUID> onTrip = proofs.consignmentsOnTrip(trip(tripNo));
        List<String> reasons = inTransaction(() -> jdbc.sql("""
                        SELECT after_state ->> 'reason' FROM audit_log
                         WHERE action = 'CONSIGNMENT_BILLING_ELIGIBLE_MARKED'
                           AND resource_id IN (:ids)
                        """)
                .param("ids", onTrip)
                .query(String.class)
                .list());
        assertThat(reasons).hasSize(onTrip.size()).containsOnly(reason);
    }

    @Then("the transport charge of {string} for trip {string} is billable")
    public void chargeBillable(String clientCode, String tripNo) {
        assertThat(transportCharge(clientCode, tripNo).billable()).isTrue();
    }

    @Then("the transport charge of {string} for trip {string} waits for proofs of delivery")
    public void chargeWaits(String clientCode, String tripNo) {
        assertThat(transportCharge(clientCode, tripNo).billable()).isFalse();
    }

    @Then("the invoice of {string} for the month {int} days ago bills transport on {int} kg")
    public void invoiceBillsTransport(String clientCode, int daysAgo, int kg) {
        LocalDate period = LocalDate.now(ZoneOffset.UTC).minusDays(daysAgo).withDayOfMonth(1);
        List<BigDecimal> quantities = inTransaction(() -> jdbc.sql("""
                        SELECT ch.quantity
                          FROM client_invoice i
                          JOIN client_charge ch ON ch.invoice_id = i.id
                         WHERE i.client_partner_id = :client AND i.period_start = :period
                           AND ch.service_type = 'TRANSPORT'
                        """)
                .param("client", world.ref(clientCode))
                .param("period", period)
                .query(BigDecimal.class)
                .list());
        assertThat(quantities).hasSize(1);
        assertThat(quantities.get(0)).isEqualByComparingTo(BigDecimal.valueOf(kg));
    }

    // ------------------------------------------------------------ internals

    private void release(String billNo, String reason) {
        failure = null;
        try {
            billing.releaseProofHold(world.ref("bill:" + billNo), reason);
        } catch (RuntimeException e) {
            failure = e;
        }
    }

    private ClientChargeView transportCharge(String clientCode, String tripNo) {
        UUID client = world.ref(clientCode);
        UUID tripId = trip(tripNo);
        LocalDate period = inTransaction(() -> jdbc.sql("""
                        SELECT billing_period FROM client_charge
                         WHERE client_partner_id = :client AND trip_id = :trip
                        """)
                .param("client", client)
                .param("trip", tripId)
                .query(LocalDate.class)
                .single());
        return inTransaction(() -> clientQueries.chargesOf(client, period, null, null)).items()
                .stream()
                .filter(c -> tripId.equals(c.tripId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No transport charge for " + tripNo));
    }

    private FreightBillView bill(String billNo) {
        return inTransaction(() -> finance.findById(world.ref("bill:" + billNo))
                .orElseThrow(() -> new AssertionError("No bill " + billNo)));
    }

    private UUID trip(String tripNo) {
        return proofs.tripNamed(world.uniqueCode(tripNo));
    }

    private UUID shipment(String orderNo, String consigneeCode) {
        UUID orderId = world.ref("order:" + orderNo);
        UUID consigneeId = world.ref(consigneeCode);
        return inTransaction(() -> consignments
                .findByOrder(TenantContext.requireTenantId(), orderId).stream()
                .filter(c -> c.consigneePartnerId().equals(consigneeId))
                .findFirst().orElseThrow().id());
    }

    private <T> T inTransaction(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }
}
