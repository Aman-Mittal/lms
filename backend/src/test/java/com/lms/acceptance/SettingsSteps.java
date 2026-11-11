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

import com.lms.order.command.OrderCloseSettings;
import com.lms.settings.api.ScopeType;
import com.lms.settings.api.SettingContext;
import com.lms.settings.api.SettingSource;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Setting profiles in-process: a customer's own values winning over the tenant's, and what is refused. */
public class SettingsSteps {

    private static final String TOLERANCE = "order.close.tolerancePercent";

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private SettingProfileService profiles;
    @Autowired
    private SettingProfileQueryService profileQueries;
    @Autowired
    private Settings settings;
    @Autowired
    private OrderCloseSettings closeSettings;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Given("customer {string} has its own close tolerance of {int} percent")
    public void customerTolerance(String code, int percent) {
        profiles.create(new SettingProfileService.Scope(ScopeType.CUSTOMER, world.ref(code), null, null),
                code + " terms", List.of(new SettingRegistry.Requested(TOLERANCE, null, percent)));
    }

    @Then("the close tolerance in force for customer {string} is {int} percent from its {word} profile")
    public void toleranceInForce(String code, int percent, String scopeType) {
        UUID customer = world.ref(code);
        OrderCloseSettings.Policy policy = closeSettings.effectiveFor(world.orgUnitId(), customer);
        assertThat(policy.tolerancePercent()).isEqualByComparingTo(BigDecimal.valueOf(percent));
        SettingSource source = new TransactionTemplate(transactionManager).execute(s -> settings.resolve(
                com.lms.order.command.OrderSettingDefinitions.CLOSE_TOLERANCE,
                SettingContext.ofOrgUnit(world.orgUnitId()).withCustomer(customer)).source());
        assertThat(source.scopeType()).isEqualTo(ScopeType.valueOf(scopeType));
    }

    @Then("a second settings profile for customer {string} is refused with {string}")
    public void secondProfileRefused(String code, String refusal) {
        assertThatThrownBy(() -> profiles.create(new SettingProfileService.Scope(ScopeType.CUSTOMER, world.ref(code), null, null),
                "again", List.of()))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo(refusal));
    }

    @Then("setting {string} to {string} for customer {string} is refused with {string}")
    public void valueRefused(String key, String value, String code, String refusal) {
        UUID customer = world.ref(code);
        UUID profile = new TransactionTemplate(transactionManager).execute(s -> settings.resolve(
                com.lms.order.command.OrderSettingDefinitions.CLOSE_TOLERANCE,
                SettingContext.tenant().withCustomer(customer)).source().profileId());
        Object parsed = value.matches("-?[0-9.]+") ? new BigDecimal(value) : value;
        long version = profileQueries.find(profile).orElseThrow().version();
        assertThatThrownBy(() -> profiles.update(profile, version, null, null,
                List.of(new SettingRegistry.Requested(key, null, parsed))))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo(refusal));
    }
}
