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
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.lms.approvals.api.ApprovalRequests;
import com.lms.approvals.api.ApprovalSubjectTypes;
import com.lms.approvals.api.ApprovalTicket;
import com.lms.approvals.command.ApprovalCommandService;
import com.lms.approvals.command.ApprovalPolicyService;
import com.lms.clientbilling.command.ClientContractLifecycleService;
import com.lms.clientbilling.command.ClientContractService;
import com.lms.clientbilling.command.domain.ClientContract;
import com.lms.shared.error.BusinessRuleViolationException;
import io.cucumber.java.Before;
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
 * Step definitions for {@code contract_lifecycle.feature}. Each name signs in
 * as its own user holding every permission, so the self-decision rule is what
 * tells the maker from the checker.
 */
public class ContractLifecycleSteps {

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private ClientContractLifecycleService lifecycle;
    @Autowired
    private ApprovalRequests approvals;
    @Autowired
    private ApprovalCommandService decisions;
    @Autowired
    private ApprovalPolicyService policies;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private final Map<String, UUID> people = new HashMap<>();
    private RuntimeException failure;

    @Before
    public void resetContractLifecycleState() {
        failure = null;
    }

    @Given("the tenant asks for no verification of contracts")
    public void noVerification() {
        policies.set(ApprovalSubjectTypes.CONTRACT_ACTIVATION,
                new ApprovalPolicyService.Change(false, null, null, null, null, null));
    }

    @Given("{string} drafts contract {string} for {string} starting {int} days ago with a transport rate of {int}")
    public void drafts(String who, String code, String clientCode, int daysAgo, int perKg) {
        as(who);
        UUID id = lifecycle.createDraft(world.orgUnitId(), world.uniqueCode(code), world.ref(clientCode), "INR",
                LocalDate.now(ZoneOffset.UTC).minusDays(daysAgo), null,
                new ClientContract.Rates(BigDecimal.valueOf(perKg), BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO),
                List.<ClientContractService.ServiceRateTerm>of(), false);
        world.putRef("contract-draft:" + code, id);
    }

    @When("{string} asks for contract {string} to be verified")
    public void asksForVerification(String who, String code) {
        as(who);
        attempt(() -> lifecycle.requestVerification(id(code), versionOf(code)));
    }

    @When("{string} approves the verification of contract {string}")
    public void approves(String who, String code) {
        ApprovalTicket ticket = open(code);
        as(who);
        attempt(() -> decisions.approve(ticket.id(), ticket.version(), null));
    }

    @When("{string} rejects the verification of contract {string} because {string}")
    public void rejects(String who, String code, String reason) {
        ApprovalTicket ticket = open(code);
        as(who);
        attempt(() -> {
            decisions.reject(ticket.id(), ticket.version(), reason);
            return null;
        });
    }

    @Then("the contract step is refused with {string}")
    public void refused(String code) {
        assertThat(failure).as("the step should have been refused").isInstanceOf(BusinessRuleViolationException.class);
        assertThat(((BusinessRuleViolationException) failure).code()).isEqualTo(code);
    }

    @Then("the refusal names rule {string}")
    public void refusalNames(String rule) {
        assertThat(((BusinessRuleViolationException) failure).errors()).containsKey(rule);
    }

    @Then("contract {string} stands {word}")
    public void stands(String code, String status) {
        assertThat(failure).as("no step should have failed").isNull();
        assertThat(row(code, "status")).isEqualTo(status);
    }

    @Then("contract {string} carries the rejection reason {string}")
    public void carriesReason(String code, String reason) {
        assertThat(row(code, "rejection_reason")).isEqualTo(reason);
    }

    @Then("contract {string} is in force today")
    public void inForceToday(String code) {
        assertThat(inForce(code, LocalDate.now(ZoneOffset.UTC))).isTrue();
    }

    @Then("contract {string} is in force on no day")
    public void inForceNever(String code) {
        assertThat(inForce(code, LocalDate.now(ZoneOffset.UTC))).isFalse();
    }

    @Then("contract {string} ended {int} days ago")
    public void endedDaysAgo(String code, int daysAgo) {
        assertThat(row(code, "effective_to::text")).isEqualTo(LocalDate.now(ZoneOffset.UTC).minusDays(daysAgo).toString());
    }

    @Then("contract {string} was audited as {string}")
    public void audited(String code, String action) {
        assertThat(inTransaction(() -> jdbc.sql("SELECT count(*) FROM audit_log WHERE action = :a AND resource_id = :id")
                .param("a", action).param("id", id(code)).query(Long.class).single())).isEqualTo(1L);
    }

    // ----------------------------------------------------------------- helpers

    private UUID id(String code) {
        return world.ref("contract-draft:" + code);
    }

    private long versionOf(String code) {
        return Long.parseLong(row(code, "version::text"));
    }

    private String row(String code, String column) {
        return inTransaction(() -> jdbc.sql("SELECT " + column + " FROM client_contract WHERE id = :id")
                .param("id", id(code)).query(String.class).single());
    }

    private boolean inForce(String code, LocalDate day) {
        return inTransaction(() -> jdbc.sql("""
                        SELECT count(*) FROM client_contract
                         WHERE id = :id AND status = 'ACTIVE' AND effective_from <= :day
                           AND (effective_to IS NULL OR effective_to >= :day)
                           AND (valid_till IS NULL OR valid_till >= :day)
                        """).param("id", id(code)).param("day", day).query(Long.class).single()) > 0;
    }

    private ApprovalTicket open(String code) {
        return inTransaction(() -> approvals.openRequest(ApprovalSubjectTypes.CONTRACT_ACTIVATION, id(code),
                ClientContractLifecycleService.VERIFY_ACTION))
                .orElseThrow(() -> new AssertionError("No open verification request for contract " + code));
    }

    private void as(String who) {
        UUID id = people.computeIfAbsent(who, k -> UUID.randomUUID());
        Set<SimpleGrantedAuthority> authorities = AuthorityHooks.ALL_PERMISSIONS.stream()
                .map(SimpleGrantedAuthority::new).collect(Collectors.toSet());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(id.toString(), "n/a", authorities));
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
