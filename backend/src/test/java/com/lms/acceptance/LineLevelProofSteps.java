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
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.lms.delivery.command.DeliveryService;
import com.lms.delivery.command.ProofFeedingService;
import com.lms.delivery.command.ProofFeedingService.LineInput;
import com.lms.delivery.query.DeliveryProofView;
import com.lms.delivery.query.DeliveryQueryService;
import com.lms.planning.command.ConsignmentRepository;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.tenant.TenantContext;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Step definitions for {@code line_level_pod.feature}. */
public class LineLevelProofSteps {

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private ProofFeedingService feeding;
    @Autowired
    private DeliveryService delivery;
    @Autowired
    private DeliveryQueryService queries;
    @Autowired
    private ConsignmentRepository consignments;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private BusinessRuleViolationException refusal;

    @When("the proof of delivery for the shipment of order {string} for {string} is started")
    public void start(String orderNo, String consigneeCode) {
        feeding.ensure(shipment(orderNo, consigneeCode));
    }

    @Then("the shipment of order {string} for {string} has {int} proof of delivery record")
    public void recordCount(String orderNo, String consigneeCode, int expected) {
        UUID id = shipment(orderNo, consigneeCode);
        assertThat(jdbc.sql("SELECT count(*) FROM delivery_proof WHERE consignment_id = :c")
                .param("c", id).query(Long.class).single()).isEqualTo((long) expected);
    }

    @Then("the proof of delivery for the shipment of order {string} for {string} is {string}")
    public void status(String orderNo, String consigneeCode, String expected) {
        assertThat(view(orderNo, consigneeCode).status()).isEqualTo(expected);
    }

    @When("{int} received and {int} short is fed for line {int} of the proof for the shipment of order {string} for {string}")
    public void feedLine(int received, int missing, int lineNo, String orderNo, String consigneeCode) {
        DeliveryProofView view = view(orderNo, consigneeCode);
        UUID line = view.lines().stream().filter(l -> l.lineNo() == lineNo).findFirst().orElseThrow()
                .orderLineId();
        feeding.feedLines(shipment(orderNo, consigneeCode), java.util.List.of(new LineInput(line,
                BigDecimal.valueOf(received), BigDecimal.valueOf(missing), null, null)));
    }

    @When("the times are set to reporting {int}, unloading start {int} and unloading end {int} minutes ago for the shipment of order {string} for {string}")
    public void timings(int reporting, int start, int end, String orderNo, String consigneeCode) {
        refusal = null;
        Instant now = Instant.now();
        try {
            feeding.setTimings(shipment(orderNo, consigneeCode), now.minus(reporting, ChronoUnit.MINUTES),
                    now.minus(start, ChronoUnit.MINUTES), now.minus(end, ChronoUnit.MINUTES));
        } catch (BusinessRuleViolationException e) {
            refusal = e;
        }
    }

    @When("paper is keyed by the receiving clerk for the shipment of order {string} for {string}")
    public void keyPaper(String orderNo, String consigneeCode) {
        delivery.keyFromPaper(shipment(orderNo, consigneeCode), "Receiving clerk", null, null, null, null, null);
    }

    @Then("the proof for the shipment of order {string} for {string} is a paper proof")
    public void isPaper(String orderNo, String consigneeCode) {
        DeliveryProofView view = view(orderNo, consigneeCode);
        assertThat(view.paper()).isTrue();
        assertThat(view.recipientName()).isEqualTo("Receiving clerk");
    }

    @Then("the proof for the shipment of order {string} for {string} shows {int} received and {int} short on line {int}")
    public void showsLine(String orderNo, String consigneeCode, int received, int missing, int lineNo) {
        DeliveryProofView.Line line = view(orderNo, consigneeCode).lines().stream()
                .filter(l -> l.lineNo() == lineNo).findFirst().orElseThrow();
        assertThat(line.receivedQty()).isEqualByComparingTo(BigDecimal.valueOf(received));
        assertThat(line.shortQty()).isEqualByComparingTo(BigDecimal.valueOf(missing));
    }

    @Then("the proof for the shipment of order {string} for {string} has no times")
    public void noTimes(String orderNo, String consigneeCode) {
        DeliveryProofView view = view(orderNo, consigneeCode);
        assertThat(view.reportedAt()).isNull();
        assertThat(view.unloadingStartedAt()).isNull();
        assertThat(view.unloadingEndedAt()).isNull();
    }

    @Then("the step is refused with {string}")
    public void refused(String code) {
        assertThat(refusal).as("expected a refusal").isNotNull();
        assertThat(refusal.code()).isEqualTo(code);
    }

    private DeliveryProofView view(String orderNo, String consigneeCode) {
        return queries.proofOf(shipment(orderNo, consigneeCode)).orElseThrow();
    }

    private UUID shipment(String orderNo, String consigneeCode) {
        UUID orderId = world.ref("order:" + orderNo);
        UUID consigneeId = world.ref(consigneeCode);
        return new TransactionTemplate(transactionManager).execute(status -> consignments
                .findByOrder(TenantContext.requireTenantId(), orderId).stream()
                .filter(c -> c.consigneePartnerId().equals(consigneeId))
                .findFirst().orElseThrow().id());
    }
}
