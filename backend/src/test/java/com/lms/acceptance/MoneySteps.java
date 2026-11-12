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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import com.lms.approvals.api.ApprovalRequests;
import com.lms.approvals.api.ApprovalSubjectTypes;
import com.lms.finance.command.ChargeCatalogueService;
import com.lms.finance.command.FinanceSettingDefinitions;
import com.lms.finance.command.ManualChargeService;
import com.lms.finance.command.VendorAdvanceService;
import com.lms.finance.command.VendorPayablesService;
import com.lms.finance.command.domain.ChargeBasis;
import com.lms.finance.command.domain.ChargeDirection;
import com.lms.finance.command.domain.ManualCharge;
import com.lms.finance.command.domain.VendorAdvance;
import com.lms.finance.command.domain.VendorPayment;
import com.lms.finance.query.ChargeCatalogueQueryService;
import com.lms.finance.query.FinanceQueryService;
import com.lms.finance.query.ManualChargeQueryService;
import com.lms.finance.query.ManualChargeView;
import com.lms.finance.query.PayablesQueryService;
import com.lms.finance.query.VendorAdvanceQueryService;
import com.lms.finance.query.VendorAdvanceView;
import com.lms.finance.query.VendorInvoiceView;
import com.lms.settings.api.Settings;
import com.lms.shared.error.BusinessRuleViolationException;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step definitions for {@code money.feature}: the charge catalogue, manual
 * charges, vendor invoices and payments, and vendor advances (issue #225).
 *
 * <p>The freight bills and trips come from {@code FinanceSteps}; a bill's trip
 * is found through the bill itself, so these steps need nothing of that class.
 */
public class MoneySteps {

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private ChargeCatalogueService catalogue;
    @Autowired
    private ChargeCatalogueQueryService catalogueQueries;
    @Autowired
    private ManualChargeService charges;
    @Autowired
    private ManualChargeQueryService chargeQueries;
    @Autowired
    private VendorPayablesService payables;
    @Autowired
    private PayablesQueryService payableQueries;
    @Autowired
    private VendorAdvanceService advances;
    @Autowired
    private VendorAdvanceQueryService advanceQueries;
    @Autowired
    private FinanceQueryService finance;
    @Autowired
    private ApprovalRequests approvals;
    @Autowired
    private Settings settings;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private org.springframework.jdbc.core.simple.JdbcClient jdbc;

    private final Map<String, UUID> invoices = new HashMap<>();
    private RuntimeException failure;
    private UUID lastCharge;
    private UUID lastAdvance;

    // ------------------------------------------------------------------ given

    @Given("a catalogue charge {string} counted {string} at {int} for {string}")
    public void aCatalogueCharge(String code, String basis, int rate, String direction) {
        UUID id = catalogue.create(code, code + " charge", ChargeBasis.valueOf(basis), BigDecimal.valueOf(rate),
                ChargeDirection.valueOf(direction), true);
        world.putRef("charge-entry:" + code, id);
    }

    @Given("the manual charge approval threshold is {int}")
    public void theThreshold(int amount) {
        inTransaction(() -> {
            settings.setOrgDefaults(Map.of(FinanceSettingDefinitions.MANUAL_CHARGE_APPROVAL_THRESHOLD,
                    BigDecimal.valueOf(amount)));
            return null;
        });
    }

    // ------------------------------------------------------------------- when

    @When("a charge {string} of {int} is raised against the trip of bill {string}")
    public void aChargeAgainstTheTrip(String code, int quantity, String billNo) {
        raise(code, quantity, ManualCharge.SubjectType.TRIP, tripOf(billNo));
    }

    @When("a charge {string} of {int} is raised on the order of bill {string}")
    public void aChargeOnTheOrder(String code, int quantity, String billNo) {
        UUID trip = tripOf(billNo);
        raise(code, quantity, ManualCharge.SubjectType.ORDER, orderOfTrip(trip));
    }

    @When("the charge is cancelled because {string}")
    public void theChargeIsCancelled(String reason) {
        attempt(() -> {
            long version = inTransaction(() -> chargeQueries.find(lastCharge).orElseThrow().version());
            charges.cancel(lastCharge, version, reason);
            return null;
        });
    }

    @When("the vendor's invoice {string} for {int} is captured against bill {string}")
    public void invoiceCaptured(String vendorNo, int amount, String billNo) {
        UUID invoice = attempt(() -> payables.capture(new VendorPayablesService.CaptureCommand(vendorNo,
                LocalDate.now(ZoneOffset.UTC), BigDecimal.valueOf(amount), BigDecimal.ZERO,
                java.util.List.of(world.ref("bill:" + billNo)), null, null)));
        if (invoice != null) {
            invoices.put(vendorNo, invoice);
        }
    }

    @When("{int} is paid against invoice {string} by reference {string}")
    public void paid(int amount, String vendorNo, String reference) {
        attempt(() -> payables.recordPayment(invoices.get(vendorNo), new VendorPayablesService.PaymentCommand(
                BigDecimal.valueOf(amount), LocalDate.now(ZoneOffset.UTC), VendorPayment.Mode.BANK_TRANSFER,
                reference)));
    }

    @When("an advance of {int} by {string} is requested for the trip of bill {string}")
    public void anAdvance(int amount, String mode, String billNo) {
        UUID trip = tripOf(billNo);
        VendorAdvanceService.Outcome outcome = attempt(() -> advances.request(new VendorAdvanceService.RequestCommand(
                trip, VendorAdvance.Mode.valueOf(mode), BigDecimal.valueOf(amount), "Fuel and tolls")));
        if (outcome != null) {
            lastAdvance = outcome.id();
        }
    }

    // ------------------------------------------------------------------- then

    @Then("the charge is {string}")
    public void theChargeIs(String status) {
        assertThat(failure).as("the charge should have been raised").isNull();
        assertThat(lastCharge()).isNotNull().extracting(ManualChargeView::status).isEqualTo(status);
    }

    @Then("the charge is a line of bill {string}")
    public void theChargeIsALineOf(String billNo) {
        assertThat(lastCharge().freightBillId()).isEqualTo(world.ref("bill:" + billNo));
    }

    @Then("the charge waits for the vendor's next bill")
    public void theChargeWaits() {
        ManualChargeView view = lastCharge();
        assertThat(view.status()).isEqualTo("APPROVED");
        assertThat(view.freightBillId()).isNull();
    }

    @Then("an approval request is open for the charge")
    public void anApprovalIsOpen() {
        assertThat(inTransaction(() -> approvals.openRequest(ApprovalSubjectTypes.MANUAL_CHARGE, lastCharge,
                ManualChargeService.APPROVE_ACTION))).isPresent();
    }

    @Then("no approval request is open for the charge")
    public void noApprovalIsOpen() {
        assertThat(inTransaction(() -> approvals.openRequest(ApprovalSubjectTypes.MANUAL_CHARGE, lastCharge,
                ManualChargeService.APPROVE_ACTION))).isEmpty();
    }

    @Then("raising the charge is refused mentioning {string}")
    public void raisingIsRefused(String fragment) {
        assertRefused(fragment);
    }

    @Then("capturing the invoice is refused mentioning {string}")
    public void capturingIsRefused(String fragment) {
        assertRefused(fragment);
    }

    @Then("paying is refused mentioning {string}")
    public void payingIsRefused(String fragment) {
        assertRefused(fragment);
    }

    @Then("the advance request is refused mentioning {string}")
    public void advanceIsRefused(String fragment) {
        assertRefused(fragment);
    }

    @Then("the advance is {string}")
    public void theAdvanceIs(String status) {
        assertThat(failure).as("the advance should have been requested").isNull();
        VendorAdvanceView view = inTransaction(() -> advanceQueries.find(lastAdvance).orElseThrow());
        assertThat(view.status()).isEqualTo(status);
    }

    @Then("invoice {string} has {int} outstanding")
    public void outstanding(String vendorNo, int amount) {
        assertThat(failure).isNull();
        assertThat(invoice(vendorNo).outstanding()).isEqualByComparingTo(BigDecimal.valueOf(amount));
    }

    @Then("invoice {string} is {string}")
    public void invoiceIs(String vendorNo, String status) {
        assertThat(invoice(vendorNo).status()).isEqualTo(status);
    }

    // -------------------------------------------------------------- internals

    private void raise(String code, int quantity, ManualCharge.SubjectType type, UUID subject) {
        failure = null;
        ManualChargeService.Outcome outcome = attempt(() -> charges.record(new ManualChargeService.RecordCommand(
                world.ref("charge-entry:" + code), type, subject, null, null, BigDecimal.valueOf(quantity), null,
                "Waited at the gate", null)));
        if (outcome != null) {
            lastCharge = outcome.id();
        }
    }

    private ManualChargeView lastCharge() {
        return inTransaction(() -> chargeQueries.find(lastCharge).orElseThrow());
    }

    private VendorInvoiceView invoice(String vendorNo) {
        return inTransaction(() -> payableQueries.invoice(invoices.get(vendorNo)).orElseThrow());
    }

    private UUID tripOf(String billNo) {
        return inTransaction(() -> finance.findById(world.ref("bill:" + billNo)).orElseThrow().tripId());
    }

    private UUID orderOfTrip(UUID trip) {
        return inTransaction(() -> jdbcOrder(trip));
    }

    private UUID jdbcOrder(UUID trip) {
        return jdbc.sql("""
                SELECT c.order_id FROM load_consignment lc
                  JOIN trip t ON t.load_id = lc.load_id
                  JOIN consignment c ON c.id = lc.consignment_id
                 WHERE t.id = :trip LIMIT 1""").param("trip", trip).query(UUID.class).single();
    }

    private <T> T attempt(Supplier<T> action) {
        failure = null;
        try {
            return action.get();
        } catch (RuntimeException e) {
            failure = e;
            return null;
        }
    }

    private void assertRefused(String fragment) {
        assertThat(failure).as("the operation should have been refused").isNotNull();
        StringBuilder chain = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            chain.append(t.getMessage()).append(' ');
            if (t instanceof BusinessRuleViolationException rule) {
                chain.append(rule.code()).append(' ');
            }
        }
        assertThat(chain.toString()).as("refusal should mention '%s'", fragment).contains(fragment);
    }

    private <T> T inTransaction(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }
}
