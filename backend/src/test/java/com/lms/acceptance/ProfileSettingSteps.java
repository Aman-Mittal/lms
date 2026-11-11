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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.execution.command.ExecutionSettingDefinitions;
import com.lms.settings.api.ScopeType;
import com.lms.settings.api.SettingContext;
import com.lms.settings.api.Settings;
import com.lms.settings.command.SettingProfileService;
import com.lms.settings.command.SettingRegistry;
import com.lms.settings.query.SettingProfileQueryService;
import com.lms.shared.error.BusinessRuleViolationException;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A profile setting a value for one scope -- a customer, a vendor, a lorry or
 * an organisational unit -- for the scenarios showing that the modules which
 * read settings resolve them for the record in hand. Values are written as
 * text: true and false, a number, a JSON object, or anything else as a string
 * (a duration such as PT20M). A dated setting's value takes effect today
 * unless the step says otherwise.
 */
public class ProfileSettingSteps {

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private SettingProfileService profiles;
    @Autowired
    private SettingProfileQueryService profileQueries;
    @Autowired
    private SettingRegistry registry;
    @Autowired
    private Settings settings;
    @Autowired
    private JsonMapper json;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private SpineFixtures spine;

    /** The profile made for each scope in this scenario, so a second value joins it. */
    private final Map<String, UUID> made = new HashMap<>();

    @Given("customer {string} has a profile setting {string} to {string}")
    public void customerSetting(String code, String key, String value) {
        set(ScopeType.CUSTOMER, world.ref(code), key, value, null);
    }

    @Given("customer {string} has a profile setting {string} to {string} from tomorrow")
    public void customerSettingFromTomorrow(String code, String key, String value) {
        set(ScopeType.CUSTOMER, world.ref(code), key, value, LocalDate.now(ZoneOffset.UTC).plusDays(1));
    }

    @Given("vendor {string} has a profile setting {string} to {string}")
    public void vendorSetting(String code, String key, String value) {
        set(ScopeType.VENDOR, world.ref(code), key, value, null);
    }

    @Given("lorry {string} has a profile setting {string} to {string}")
    public void lorrySetting(String registration, String key, String value) {
        set(ScopeType.VEHICLE, world.ref("vehicle:" + registration), key, value, null);
    }

    @Given("org unit {string} has a profile setting {string} to {string}")
    public void unitSetting(String orgPath, String key, String value) {
        UUID unit = world.orgUnitId(orgPath);
        assertThat(unit).as("scenario has no unit %s", orgPath).isNotNull();
        set(ScopeType.ORG_UNIT, unit, key, value, null);
    }

    @Given("an awarded load {string} of {int} kg for customer {string} on lorry {string}")
    public void loadForCustomer(String loadNo, int weightKg, String customerCode, String registration) {
        world.putRef("load:" + loadNo, spine.awardedLoad(world.orgUnitId(), world.uniqueCode(loadNo),
                world.ref(customerCode), world.ref("ORIGIN"), world.ref("CONS-A"), world.ref("DEST-A"),
                world.ref("vehicle:" + registration), world.ref("VENDOR-1"), BigDecimal.valueOf(weightKg),
                false));
    }

    @Then("the documents customer {string} must carry from tomorrow include {string}")
    public void documentsFromTomorrow(String code, String documentType) {
        Map<String, Object> checklist = new TransactionTemplate(transactionManager).execute(s -> settings.effective(
                ExecutionSettingDefinitions.REQUIRED_DOCUMENTS,
                SettingContext.ofOrgUnit(world.orgUnitId()).withCustomer(world.ref(code))
                        .asOf(LocalDate.now(ZoneOffset.UTC).plusDays(1))));
        assertThat(checklist).containsEntry(documentType, true);
        Map<String, Object> today = new TransactionTemplate(transactionManager).execute(s -> settings.effective(
                ExecutionSettingDefinitions.REQUIRED_DOCUMENTS,
                SettingContext.ofOrgUnit(world.orgUnitId()).withCustomer(world.ref(code))));
        assertThat(today).as("not yet in force today").doesNotContainKey(documentType);
    }

    @Then("a profile for customer {string} setting {string} to {string} is refused with {string}")
    public void refused(String code, String key, String text, String refusal) {
        assertThatThrownBy(() -> profiles.create(new SettingProfileService.Scope(ScopeType.CUSTOMER, world.ref(code),
                        null, null), "refused", List.of(new SettingRegistry.Requested(key,
                        LocalDate.now(ZoneOffset.UTC), parse(text)))))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo(refusal));
    }

    private void set(ScopeType type, UUID scopeId, String key, String text, LocalDate from) {
        boolean dated = registry.find(key).orElseThrow(() -> new AssertionError("no setting " + key))
                .effectiveDated();
        LocalDate effectiveFrom = from != null ? from : dated ? LocalDate.now(ZoneOffset.UTC) : null;
        List<SettingRegistry.Requested> requested =
                List.of(new SettingRegistry.Requested(key, effectiveFrom, parse(text)));
        String scope = type + ":" + scopeId;
        UUID existing = made.get(scope);
        if (existing == null) {
            made.put(scope, profiles.create(new SettingProfileService.Scope(type, scopeId, null, null),
                    type.name().toLowerCase() + " terms " + world.uniqueCode("P"), requested));
        } else {
            long version = profileQueries.find(existing).orElseThrow().version();
            profiles.update(existing, version, null, null, requested);
        }
    }

    private Object parse(String text) {
        if ("true".equals(text) || "false".equals(text)) {
            return Boolean.valueOf(text);
        }
        if (text.matches("-?[0-9]+(\\.[0-9]+)?")) {
            return new BigDecimal(text);
        }
        if (text.startsWith("{")) {
            return json.readValue(text, Map.class);
        }
        return text;
    }
}
