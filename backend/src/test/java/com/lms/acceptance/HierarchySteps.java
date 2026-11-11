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
import java.util.List;
import java.util.UUID;

import com.lms.identity.command.OrgUnitService;
import com.lms.identity.command.domain.OrgUnit.UnitType;
import com.lms.order.command.OrderSettingDefinitions;
import com.lms.settings.api.Resolved;
import com.lms.settings.api.ScopeType;
import com.lms.settings.api.SettingContext;
import com.lms.settings.api.Settings;
import com.lms.settings.command.SettingProfileService;
import com.lms.settings.command.SettingRegistry;
import com.lms.shared.error.BusinessRuleViolationException;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The organisational levels and the settings hierarchy in-process: units
 * added through {@link OrgUnitService} under the rank rule, the close
 * tolerance read for a unit (and a customer) with its provenance in words,
 * and combination profiles with their ambiguity rule.
 */
public class HierarchySteps {

    private static final String TOLERANCE = OrderSettingDefinitions.CLOSE_TOLERANCE.name();

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private OrgUnitService units;
    @Autowired
    private SettingProfileService profiles;
    @Autowired
    private Settings settings;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Given("country unit {string} named {string} with code {string} is added beneath {string}")
    public void countryUnit(String slug, String name, String code, String parentPath) {
        add(slug, name, UnitType.COUNTRY, code, parentPath);
    }

    @Given("unit {string} named {string} is added as a {word} beneath {string}")
    public void unit(String slug, String name, String type, String parentPath) {
        add(slug, name, UnitType.valueOf(type), null, parentPath);
    }

    @Then("adding a {word} unit {string} beneath {string} is refused with {string}")
    public void unitRefused(String type, String slug, String parentPath, String refusal) {
        assertThatThrownBy(() -> add(slug, slug, UnitType.valueOf(type), type.equals("COUNTRY") ? "LK" : null,
                parentPath))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo(refusal));
    }

    @Then("adding a {word} unit {string} beneath {string} is accepted")
    public void unitAccepted(String type, String slug, String parentPath) {
        add(slug, slug, UnitType.valueOf(type), null, parentPath);
        assertThat(world.orgUnitId(parentPath + slug + "/")).isNotNull();
    }

    @Given("a combination of unit {string} and customer {string} sets the close tolerance to {int} percent")
    public void unitAndCustomer(String orgPath, String code, int percent) {
        combination(new SettingProfileService.Conditions(world.orgUnitId(orgPath), world.ref(code), null, null,
                null, 0), percent);
    }

    @Then("a combination of unit {string} and freight mode {string} setting the close tolerance is refused with {string}")
    public void unitAndModeRefused(String orgPath, String mode, String refusal) {
        assertThatThrownBy(() -> combination(new SettingProfileService.Conditions(world.orgUnitId(orgPath), null,
                null, null, mode, 0), 9))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo(refusal));
    }

    @Then("a combination of unit {string} and freight mode {string} setting the close tolerance is accepted")
    public void unitAndModeAccepted(String orgPath, String mode) {
        assertThat(combination(new SettingProfileService.Conditions(world.orgUnitId(orgPath), null, null, null,
                mode, 0), 9)).isNotNull();
    }

    @Then("the close tolerance in force at unit {string} is {int} percent from {string}")
    public void atUnit(String orgPath, int percent, String label) {
        assertTolerance(SettingContext.ofOrgUnit(world.orgUnitId(orgPath)), percent, label);
    }

    @Then("the close tolerance for customer {string} at unit {string} is {int} percent from {string}")
    public void atUnitForCustomer(String code, String orgPath, int percent, String label) {
        assertTolerance(SettingContext.ofOrgUnit(world.orgUnitId(orgPath)).withCustomer(world.ref(code)), percent,
                label);
    }

    private void assertTolerance(SettingContext context, int percent, String label) {
        Resolved<BigDecimal> resolved = new TransactionTemplate(transactionManager).execute(s ->
                settings.resolve(OrderSettingDefinitions.CLOSE_TOLERANCE, context));
        assertThat(resolved.value()).isEqualByComparingTo(BigDecimal.valueOf(percent));
        assertThat(resolved.source().label()).isEqualTo(label);
    }

    private UUID combination(SettingProfileService.Conditions conditions, int percent) {
        return profiles.create(new SettingProfileService.Scope(ScopeType.COMBINATION, null, null, null, conditions),
                "combination " + world.uniqueCode("C"),
                List.of(new SettingRegistry.Requested(TOLERANCE, null, BigDecimal.valueOf(percent))));
    }

    private void add(String slug, String name, UnitType type, String countryCode, String parentPath) {
        UUID parent = world.orgUnitId(parentPath);
        assertThat(parent).as("scenario has no unit %s", parentPath).isNotNull();
        UUID id = units.create(parent, slug, new OrgUnitService.Draft(name, type, countryCode, null));
        world.putOrgUnit(parentPath + slug + "/", id);
    }
}
