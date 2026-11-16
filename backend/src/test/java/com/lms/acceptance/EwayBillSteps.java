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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.planning.api.ConsignmentLookupPort;
import com.lms.settings.api.SettingKey;
import com.lms.settings.api.Settings;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.statutory.command.EwayBillService;
import com.lms.statutory.command.StatutorySettingDefinitions;
import com.lms.statutory.query.EwayBillQueryService;
import com.lms.statutory.query.EwayBillView;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Step definitions for {@code eway_bill.feature}. */
public class EwayBillSteps {

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private EwayBillService bills;
    @Autowired
    private EwayBillQueryService queries;
    @Autowired
    private ConsignmentLookupPort consignments;
    @Autowired
    private Settings settings;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private BusinessRuleViolationException refusal;

    @Given("the unit uses the {string} e-way bill adapter")
    public void adapter(String code) {
        set(Map.of(StatutorySettingDefinitions.ADAPTER, code));
    }

    @Given("the unit requires an e-way bill on every consignment to dispatch")
    public void requiredAtDispatch() {
        set(Map.of(StatutorySettingDefinitions.REQUIRED_AT_DISPATCH, true));
    }

    @When("an e-way bill is generated for the consignment on load {string}")
    public void generate(String loadNo) {
        generateAgainst(loadNo, null);
    }

    @When("an e-way bill is generated for the consignment on load {string} against document {string}")
    public void generateAgainst(String loadNo, String document) {
        refusal = null;
        try {
            bills.generate(consignmentOn(loadNo), document, null, null, false);
        } catch (BusinessRuleViolationException e) {
            refusal = e;
        }
    }

    @When("{string} is applied to the e-way bill of the consignment on load {string} with reason {string} and vehicle {string}")
    public void apply(String action, String loadNo, String reason, String vehicle) {
        refusal = null;
        try {
            bills.act(current(loadNo).id(), action, reason.isEmpty() ? null : reason, vehicle.isEmpty() ? null : vehicle);
        } catch (BusinessRuleViolationException e) {
            refusal = e;
        }
    }

    @Then("the e-way bill of the consignment on load {string} is {string}")
    public void status(String loadNo, String expected) {
        assertThat(newest(loadNo).status()).isEqualTo(expected);
    }

    @Then("the e-way bill of the consignment on load {string} is on Part-B for vehicle {string}")
    public void vehicle(String loadNo, String expected) {
        assertThat(newest(loadNo).vehicleNo()).isEqualTo(expected);
    }

    @Then("the e-way bill of the consignment on load {string} has a twelve digit number")
    public void number(String loadNo) {
        assertThat(newest(loadNo).billNo()).matches("\\d{12}");
    }

    @Then("the consignment on load {string} has {int} e-way bills")
    public void count(String loadNo, int expected) {
        assertThat(inTransaction(() -> queries.forConsignment(consignmentOn(loadNo)))).hasSize(expected);
    }

    @Then("the e-way bill of the consignment on load {string} is found by its number")
    public void foundByNumber(String loadNo) {
        EwayBillView bill = newest(loadNo);
        EwayBillView found = inTransaction(() -> queries.findByNumber(bill.billNo())).orElseThrow();
        assertThat(found.consignmentId()).isEqualTo(consignmentOn(loadNo));
    }

    @Then("the e-way bill step is refused with {string}")
    public void refused(String code) {
        assertThat(refusal).as("expected a refusal").isNotNull();
        assertThat(refusal.code()).isEqualTo(code);
    }

    @Then("the e-way bill step is not refused")
    public void notRefused() {
        assertThat(refusal).as(refusal == null ? "" : refusal.getMessage()).isNull();
    }

    private void set(Map<SettingKey<?>, Object> values) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> settings.setOrgDefaults(values));
    }

    private UUID consignmentOn(String loadNo) {
        List<ConsignmentLookupPort.ConsignmentRef> onLoad = inTransaction(
                () -> consignments.onLoad(world.ref("load:" + loadNo)));
        assertThat(onLoad).hasSize(1);
        return onLoad.get(0).id();
    }

    private EwayBillView newest(String loadNo) {
        return current(loadNo);
    }

    private EwayBillView current(String loadNo) {
        UUID consignment = consignmentOn(loadNo);
        List<EwayBillView> all = inTransaction(() -> queries.forConsignment(consignment));
        assertThat(all).as("the consignment has an e-way bill").isNotEmpty();
        return all.get(0);
    }

    private <T> T inTransaction(java.util.function.Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }
}
