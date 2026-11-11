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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.lms.approvals.api.ApprovalRequests;
import com.lms.approvals.api.ApprovalSubjectTypes;
import com.lms.approvals.api.ApprovalTicket;
import com.lms.approvals.command.ApprovalCommandService;
import com.lms.approvals.command.ApprovalPolicyService;
import com.lms.order.command.OrderCommandService;
import com.lms.settings.api.ScopeType;
import com.lms.settings.command.SettingProfileService;
import com.lms.settings.command.SettingRegistry;
import com.lms.shared.error.BusinessRuleViolationException;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step definitions for {@code approvals.feature}: an order's cancellation
 * through the approvals module, decided by named people. Each name signs in
 * as its own user, holding every permission, so the self-decision rule is
 * what tells them apart.
 */
public class ApprovalSteps {

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private OrderCommandService orders;
    @Autowired
    private ApprovalRequests approvals;
    @Autowired
    private ApprovalCommandService decisions;
    @Autowired
    private ApprovalPolicyService policies;
    @Autowired
    private SettingProfileService profiles;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private final Map<String, UUID> people = new HashMap<>();
    private RuntimeException failure;

    @Given("the tenant requires approval to cancel an order")
    public void requireApproval() {
        policies.set(ApprovalSubjectTypes.ORDER_CANCELLATION,
                new ApprovalPolicyService.Change(true, null, null, null, null, null));
    }

    @Given("the unit {string} requires approval to cancel an order")
    public void unitRequiresApproval(String orgPath) {
        as("Admin");
        profiles.create(new SettingProfileService.Scope(ScopeType.ORG_UNIT, world.orgUnitId(orgPath), null, null),
                "Approvals for " + orgPath, List.of(new SettingRegistry.Requested(
                        "approvals.policy.orderCancellation", null, Map.of("required", true))));
    }

    @When("{string} asks to cancel order {string}")
    public void asksToCancel(String who, String orderNo) {
        as(who);
        attempt(() -> orders.cancel(order(orderNo), "asked in a scenario"));
    }

    @When("{string} approves the cancellation of order {string}")
    public void approves(String who, String orderNo) {
        ApprovalTicket ticket = open(orderNo);
        as(who);
        attempt(() -> decisions.approve(ticket.id(), ticket.version(), null));
    }

    @When("{string} rejects the cancellation of order {string} because {string}")
    public void rejects(String who, String orderNo, String reason) {
        ApprovalTicket ticket = open(orderNo);
        as(who);
        attempt(() -> {
            decisions.reject(ticket.id(), ticket.version(), reason);
            return null;
        });
    }

    @Then("the approval step is refused with {string}")
    public void refused(String code) {
        assertThat(failure).as("the step should have been refused")
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThat(((BusinessRuleViolationException) failure).code()).isEqualTo(code);
        failure = null;
    }

    @Then("order {string} now stands {word}")
    public void orderStands(String orderNo, String status) {
        assertThat(failure).as("no step should have failed").isNull();
        assertThat(inTransaction(() -> jdbc.sql("SELECT status FROM sales_order WHERE id = :id")
                .param("id", order(orderNo)).query(String.class).single())).isEqualTo(status);
    }

    @Then("the cancellation of order {string} awaits approval")
    public void awaits(String orderNo) {
        assertThat(open(orderNo).status()).isEqualTo("REQUESTED");
    }

    @Then("the cancellation request of order {string} is {word}")
    public void requestIs(String orderNo, String status) {
        assertThat(inTransaction(() -> jdbc.sql("""
                        SELECT status FROM approval_request WHERE subject_id = :id
                         ORDER BY requested_at DESC LIMIT 1
                        """).param("id", order(orderNo)).query(String.class).single())).isEqualTo(status);
    }

    @Then("no approval was asked for order {string}")
    public void noneAsked(String orderNo) {
        assertThat(inTransaction(() -> jdbc.sql("SELECT count(*) FROM approval_request WHERE subject_id = :id")
                .param("id", order(orderNo)).query(Long.class).single())).isZero();
    }

    private ApprovalTicket open(String orderNo) {
        return inTransaction(() -> approvals.openRequest(ApprovalSubjectTypes.ORDER_CANCELLATION, order(orderNo),
                        OrderCommandService.CANCEL_ACTION))
                .orElseThrow(() -> new AssertionError("No open cancellation request for order " + orderNo));
    }

    /** Signs in as a named person with every permission, as their own user. */
    private void as(String who) {
        UUID id = people.computeIfAbsent(who, k -> UUID.randomUUID());
        Set<SimpleGrantedAuthority> authorities = AuthorityHooks.ALL_PERMISSIONS.stream()
                .map(SimpleGrantedAuthority::new).collect(Collectors.toSet());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(id.toString(), "n/a", authorities));
    }

    private UUID order(String orderNo) {
        return world.ref("order:" + orderNo);
    }

    private void attempt(Supplier<?> action) {
        failure = null;
        try {
            var _ = action.get();
        } catch (RuntimeException e) {
            failure = e;
        }
    }

    private <T> T inTransaction(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }
}
