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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.order.command.enquiry.EnquiryConversion;
import com.lms.order.command.enquiry.EnquiryDeadlines;
import com.lms.order.command.enquiry.EnquiryLineSpec;
import com.lms.order.command.enquiry.EnquiryService;
import com.lms.order.command.enquiry.LineOutcome;
import com.lms.order.command.intake.BulkIntakeService;
import com.lms.order.command.intake.BulkItem;
import com.lms.order.command.intake.BulkResult;
import com.lms.shared.error.BusinessRuleViolationException;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

/** Step definitions for {@code enquiry_bulk_intake.feature}. */
public class EnquiryIntakeSteps {

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private EnquiryService enquiries;
    @Autowired
    private EnquiryConversion conversion;
    @Autowired
    private EnquiryDeadlines deadlines;
    @Autowired
    private BulkIntakeService bulk;
    @Autowired
    private JdbcClient jdbc;

    private final Map<String, UUID> conversions = new HashMap<>();
    private final Map<String, UUID> reconversions = new HashMap<>();
    private List<BulkItem> lastBatch;
    private List<BulkResult> lastResults;
    private BusinessRuleViolationException refusal;

    // ------------------------------------------------------------------ given

    @Given("an enquiry {string} for {string} from {string} with lines to {string} and {string}, {int} EA of {int} kg each")
    public void anEnquiry(String name, String customer, String from, String first, String second, int quantity,
                          int weight) {
        UUID id = enquiries.create(world.orgUnitId(), world.ref(customer), "REF-" + name, null,
                List.of(spec(from, first, quantity, weight), spec(from, second, quantity, weight)));
        world.putRef("enquiry:" + name, id);
    }

    @Given("a quote of {int} is issued on line {int} of enquiry {string}")
    public void aQuote(int amount, int lineNo, String name) {
        enquiries.issueQuote(id(name), lineOf(name, lineNo), version(name), BigDecimal.valueOf(amount), null, null);
    }

    // ------------------------------------------------------------------- when

    @When("line {int} of enquiry {string} is decided as WON")
    public void won(int lineNo, String name) {
        decide(name, lineNo, LineOutcome.WON, null);
    }

    @When("line {int} of enquiry {string} is decided as LOST because {string}")
    public void lost(int lineNo, String name, String reason) {
        decide(name, lineNo, LineOutcome.LOST, reason);
    }

    @When("line {int} of enquiry {string} is decided as LOST without a reason")
    public void lostWithoutReason(int lineNo, String name) {
        refusal = null;
        try {
            decide(name, lineNo, LineOutcome.LOST, null);
        } catch (BusinessRuleViolationException e) {
            refusal = e;
        }
    }

    @When("enquiry {string} is converted")
    public void converted(String name) {
        refusal = null;
        try {
            conversions.put(name, conversion.convert(id(name)).orderId());
        } catch (BusinessRuleViolationException e) {
            refusal = e;
        }
    }

    @When("enquiry {string} is converted again")
    public void convertedAgain(String name) {
        EnquiryConversion.Conversion again = conversion.convert(id(name));
        assertThat(again.alreadyConverted()).isTrue();
        reconversions.put(name, again.orderId());
    }

    @When("the deadline of enquiry {string} has passed")
    public void deadlinePassed(String name) {
        jdbc.sql("UPDATE enquiry SET deadline_at = now() - interval '1 hour' WHERE id = :id")
                .param("id", id(name)).update();
    }

    @When("the enquiry deadline sweep runs")
    public void sweepRuns() {
        deadlines.sweep(Instant.now());
    }

    @When("a batch of orders for {string} from {string} is submitted:")
    public void batch(String customer, String from, DataTable table) {
        lastBatch = table.asMaps().stream().map(row -> {
            String to = row.get("destination");
            UUID destination = to.equals("NOWHERE") ? UUID.randomUUID() : world.ref(to);
            return new BulkItem(row.get("key") + "-" + world.uniqueCode("b"), world.ref(customer), null,
                    world.ref(from), null, null, null,
                    List.of(new BulkItem.Line("MAT", "Goods", null, null, new BigDecimal(row.get("quantity")), "EA",
                            new BigDecimal("100"), null, destination)));
        }).toList();
        lastResults = bulk.submit(world.orgUnitId(), lastBatch);
    }

    @When("the same batch is submitted again")
    public void batchAgain() {
        lastResults = bulk.submit(world.orgUnitId(), lastBatch);
    }

    // ------------------------------------------------------------------- then

    @Then("enquiry {string} is {string}")
    public void enquiryStatus(String name, String expected) {
        assertThat(jdbc.sql("SELECT status FROM enquiry WHERE id = :id").param("id", id(name)).query(String.class)
                .single()).isEqualTo(expected);
    }

    @Then("line {int} of enquiry {string} is {string}")
    public void lineStatus(int lineNo, String name, String expected) {
        assertThat(jdbc.sql("SELECT status FROM enquiry_line WHERE id = :id").param("id", lineOf(name, lineNo))
                .query(String.class).single()).isEqualTo(expected);
    }

    @Then("the quote on line {int} of enquiry {string} is {string}")
    public void quoteStatus(int lineNo, String name, String expected) {
        assertThat(jdbc.sql("SELECT status FROM enquiry_quote WHERE line_id = :id").param("id", lineOf(name, lineNo))
                .query(String.class).single()).isEqualTo(expected);
    }

    @Then("both conversions of enquiry {string} name the same draft order with {int} line")
    public void sameOrder(String name, int lines) {
        UUID order = conversions.get(name);
        assertThat(order).isNotNull();
        assertThat(reconversions.get(name)).isEqualTo(order);
        assertThat(jdbc.sql("SELECT order_id FROM enquiry WHERE id = :id").param("id", id(name)).query(UUID.class)
                .single()).isEqualTo(order);
        assertThat(jdbc.sql("SELECT status FROM sales_order WHERE id = :id").param("id", order).query(String.class)
                .single()).isEqualTo("DRAFT");
        assertThat(jdbc.sql("SELECT count(*) FROM order_line WHERE order_id = :id").param("id", order)
                .query(Long.class).single()).isEqualTo((long) lines);
    }

    @Then("enquiry {string} has been audited as {string} once")
    public void audited(String name, String action) {
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE resource_id = :id AND action = :a")
                .param("id", id(name)).param("a", action).query(Long.class).single()).isEqualTo(1L);
    }

    @Then("item {int} of the batch is {string}")
    public void itemStatus(int position, String expected) {
        assertThat(lastResults.get(position - 1).status()).isEqualTo(BulkResult.Status.valueOf(expected));
        if (expected.equals("FAILED")) {
            assertThat(lastResults.get(position - 1).reasons()).isNotEmpty();
        }
    }

    @Then("the tenant holds {int} bulk upload orders")
    public void bulkOrders(int expected) {
        assertThat(jdbc.sql("SELECT count(*) FROM sales_order WHERE tenant_id = :t AND source = 'BULK_UPLOAD'")
                .param("t", world.tenantId("acme")).query(Long.class).single()).isEqualTo((long) expected);
    }

    @Then("the enquiry step is refused with {string}")
    public void refused(String code) {
        assertThat(refusal).as("expected a refusal").isNotNull();
        assertThat(refusal.code()).isEqualTo(code);
    }

    // ---------------------------------------------------------------- helpers

    private EnquiryLineSpec spec(String from, String to, int quantity, int weight) {
        return new EnquiryLineSpec(world.ref(from), world.ref(to), null, null, BigDecimal.valueOf(quantity), "EA",
                BigDecimal.valueOf(weight), null, null);
    }

    private UUID id(String name) {
        return world.ref("enquiry:" + name);
    }

    private long version(String name) {
        return jdbc.sql("SELECT version FROM enquiry WHERE id = :id").param("id", id(name)).query(Long.class)
                .single();
    }

    private UUID lineOf(String name, int lineNo) {
        return jdbc.sql("SELECT id FROM enquiry_line WHERE enquiry_id = :id AND line_no = :n").param("id", id(name))
                .param("n", lineNo).query(UUID.class).single();
    }

    private void decide(String name, int lineNo, LineOutcome outcome, String reason) {
        enquiries.recordOutcome(id(name), lineOf(name, lineNo), version(name), outcome, null, reason);
    }
}
