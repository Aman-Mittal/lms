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

import java.time.LocalDate;
import java.time.Year;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.lms.identity.command.NumberIssuer;
import com.lms.identity.command.NumberSeriesService;
import com.lms.identity.command.domain.SeriesFormat;
import com.lms.identity.numbering.DocumentType;
import com.lms.identity.query.NumberSeriesQueryService;
import com.lms.identity.query.NumberVerificationView;
import com.lms.order.command.OrderCommandService;
import com.lms.order.command.SalesOrderRepository;
import com.lms.order.command.domain.SalesOrder;
import com.lms.shared.error.BusinessRuleViolationException;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step definitions for {@code number_series.feature}. Orders, lines,
 * validation and consignment generation use the steps {@link PlanningSteps}
 * already defines; the tenant, its units and the scope those of
 * {@link IdentitySteps} and {@link MasterDataSteps}.
 */
public class NumberSeriesSteps {

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private NumberSeriesService series;
    @Autowired
    private NumberSeriesQueryService seriesQueries;
    @Autowired
    private NumberIssuer issuer;
    @Autowired
    private OrderCommandService orderCommands;
    @Autowired
    private SalesOrderRepository orders;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private RuntimeException failure;
    private final List<String> notes = new ArrayList<>();

    // ----------------------------------------------------------------- series

    @Given("a number series {string} numbers {word} for the whole tenant as {string} with {int} digits and no year")
    public void tenantSeries(String code, String type, String prefix, int digits) {
        create(code, type, null, format(prefix, SeriesFormat.YearCode.NONE, digits, 4), false);
    }

    @Given("a number series {string} numbers {word} for the whole tenant as {string} with {int} digits and no year, "
            + "reusing released numbers")
    public void reusingSeries(String code, String type, String prefix, int digits) {
        create(code, type, null, format(prefix, SeriesFormat.YearCode.NONE, digits, 4), true);
    }

    @Given("a number series {string} numbers {word} for {string} as {string} with {int} digits and no year")
    public void unitSeries(String code, String type, String unitPath, String prefix, int digits) {
        create(code, type, world.orgUnitId(unitPath), format(prefix, SeriesFormat.YearCode.NONE, digits, 4),
                false);
    }

    @Given("a number series {string} numbers {word} for the whole tenant as {string} with {int} digits "
            + "and the start year, the year starting in month {int}")
    public void yearlySeries(String code, String type, String prefix, int digits, int month) {
        create(code, type, null, format(prefix, SeriesFormat.YearCode.START_YEAR, digits, month), false);
    }

    @When("a number series {string} is set up numbering {word} for the whole tenant as {string}")
    public void attemptSeries(String code, String type, String prefix) {
        failure = null;
        try {
            create(code, type, null, format(prefix, SeriesFormat.YearCode.START_YEAR, 5, 4), false);
        } catch (BusinessRuleViolationException | AccessDeniedException e) {
            failure = e;
        }
    }

    @Then("setting up the series is refused with {string}")
    public void refusedWith(String code) {
        assertThat(failure).as("setting up the series should have been refused")
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThat(((BusinessRuleViolationException) failure).code()).isEqualTo(code);
    }

    @Then("setting up the series is refused as not permitted")
    public void refusedAsNotPermitted() {
        assertThat(failure).isInstanceOf(AccessDeniedException.class);
    }

    @When("number series {string} is deactivated")
    public void deactivate(String code) {
        series.deactivate(world.ref("series:" + code));
    }

    // ----------------------------------------------------------------- orders

    @When("an order {string} is raised at {string} with no number")
    public void raiseAt(String ref, String unitPath) {
        world.putRef("order:" + ref, orderCommands.raiseOrder(world.orgUnitId(unitPath), world.ref("CUST"),
                null, world.ref("ORIGIN"), null, null, SalesOrder.OrderSource.MANUAL));
    }

    @Then("order {string} carries number {string}")
    public void orderCarries(String ref, String number) {
        assertThat(orderNumber(ref)).isEqualTo(number);
    }

    @Then("order {string} carries this year's built-in number {int}")
    public void orderCarriesBuiltIn(String ref, int counter) {
        assertThat(orderNumber(ref)).isEqualTo("ORD-%d-%06d".formatted(Year.now(ZoneOffset.UTC).getValue(), counter));
    }

    // ---------------------------------------------------------------- numbers

    @Given("{int} numbers have been issued from series {string}")
    public void issued(int count, String code) {
        for (int i = 0; i < count; i++) {
            series.issue(world.ref("series:" + code));
        }
    }

    @When("number {string} of {word} is given back because {string}")
    public void giveBack(String number, String type, String reason) {
        boolean recorded = inTransaction(() -> issuer.release(DocumentType.valueOf(type), number, reason));
        assertThat(recorded).as("the release should have been recorded").isTrue();
    }

    @Then("giving back number {string} of series {string} again is refused with {string}")
    public void giveBackAgain(String number, String code, String expected) {
        try {
            series.release(world.ref("series:" + code), number, "once more");
            throw new AssertionError("Releasing " + number + " again should have been refused");
        } catch (BusinessRuleViolationException e) {
            assertThat(e.code()).isEqualTo(expected);
        }
        // Port callers are told rather than refused: a redelivered cancellation must not fail.
        assertThat(inTransaction(() -> issuer.release(DocumentType.GATE_PASS, number, "redelivered"))).isFalse();
    }

    @Then("the next number of series {string} is {string}")
    public void nextIs(String code, String expected) {
        assertThat(series.issue(world.ref("series:" + code))).isEqualTo(expected);
    }

    @Then("number {string} of {word} verifies as {word} in series {string}")
    public void verifiesAs(String number, String type, String status, String code) {
        NumberVerificationView verdict = seriesQueries.verify(DocumentType.valueOf(type), number);
        assertThat(verdict.belongs()).isTrue();
        assertThat(verdict.seriesId()).isEqualTo(world.ref("series:" + code));
        assertThat(verdict.status()).isEqualTo(status);
    }

    @Then("number {string} of {word} belongs to no series")
    public void belongsToNone(String number, String type) {
        assertThat(seriesQueries.verify(DocumentType.valueOf(type), number).belongs()).isFalse();
    }

    @When("a consignment note is numbered on {string}")
    public void numberedOn(String day) {
        notes.add(inTransaction(() -> issuer.next(DocumentType.CONSIGNMENT_NOTE, world.orgUnitId("/acme/"),
                LocalDate.parse(day).atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(3600)))
                .orElseThrow());
    }

    @Then("the consignment notes were numbered {string}")
    public void notesNumbered(String expected) {
        assertThat(String.join(", ", notes)).isEqualTo(expected);
    }

    // ------------------------------------------------------------ consignments

    @Then("the consignment of order {string} has a random {string} reference")
    public void randomReference(String ref, String prefix) {
        String reference = consignmentRef(ref);
        assertThat(reference).startsWith(prefix).hasSize(prefix.length() + 10);
        assertThat(seriesQueries.verify(DocumentType.CONSIGNMENT_NOTE, reference).belongs()).isFalse();
    }

    @Then("the consignment of order {string} is numbered {string}")
    public void numberedReference(String ref, String expected) {
        assertThat(consignmentRef(ref)).isEqualTo(expected);
    }

    // -------------------------------------------------------------- internals

    private void create(String code, String type, UUID unit, SeriesFormat format, boolean reuse) {
        world.putRef("series:" + code, series.create(code, DocumentType.valueOf(type), unit, format, reuse,
                true, null));
    }

    private static SeriesFormat format(String prefix, SeriesFormat.YearCode code, int digits, int month) {
        return new SeriesFormat(prefix, code, "-", digits, month, ZoneId.of("UTC"));
    }

    private String orderNumber(String ref) {
        return inTransaction(() -> orders.findById(world.ref("order:" + ref)).orElseThrow().orderNo());
    }

    private String consignmentRef(String ref) {
        return inTransaction(() -> jdbc.sql("SELECT tracking_ref FROM consignment WHERE order_id = :id")
                .param("id", world.ref("order:" + ref))
                .query(String.class)
                .single());
    }

    private <T> T inTransaction(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }
}
