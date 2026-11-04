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
package com.lms.masterdata.command.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Maker-checker rules of a credit limit change, and customer standing. */
class CreditLimitChangeTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

    private static BusinessPartner customer() {
        return BusinessPartner.register(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "CUST", "Customer", BusinessPartner.PartnerType.CUSTOMER, "TAX");
    }

    private static CreditLimitChange request(String limit, String soft) {
        return CreditLimitChange.request(UUID.randomUUID(), UUID.randomUUID(), customer(),
                new BigDecimal(limit), soft == null ? null : new BigDecimal(soft),
                "annual review", "maker", NOW);
    }

    @Test
    @DisplayName("a soft limit above the hard limit is refused; equal is allowed")
    void softLimitAtMostTheHardLimit() {
        assertThatThrownBy(() -> request("1000", "1000.01"))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo("credit-soft-limit-above-limit"));
        assertThat(request("1000", "1000").requestedSoftLimit()).isEqualByComparingTo("1000");
        assertThat(request("1000", null).status()).isEqualTo(CreditLimitChange.Status.PENDING);
    }

    @Test
    @DisplayName("the person who asked cannot approve, someone else can, and only once")
    void makerIsNotChecker() {
        CreditLimitChange change = request("1000", "800");
        assertThatThrownBy(() -> change.approve("maker", null, NOW))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo("credit-limit-change-self-approval"));

        CreditLimitChange approved = change.approve("checker", " fine ", NOW);
        assertThat(approved.status()).isEqualTo(CreditLimitChange.Status.APPROVED);
        assertThat(approved.decidedBy()).isEqualTo("checker");
        assertThat(approved.decisionNote()).isEqualTo("fine");
        assertThatThrownBy(() -> approved.reject("checker", null, NOW))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo("credit-limit-change-not-pending"));
    }

    @Test
    @DisplayName("the requester may withdraw their own request by rejecting it")
    void requesterMayWithdraw() {
        CreditLimitChange rejected = request("1000", null).reject("maker", "", NOW);
        assertThat(rejected.status()).isEqualTo(CreditLimitChange.Status.REJECTED);
        assertThat(rejected.decisionNote()).isNull();
    }

    @Test
    @DisplayName("a request keeps the limits it replaces")
    void previousLimitsAreKept() {
        BusinessPartner limited = customer().withCreditLimits(new BigDecimal("500"),
                new BigDecimal("400"));
        CreditLimitChange change = CreditLimitChange.request(UUID.randomUUID(), UUID.randomUUID(),
                limited, new BigDecimal("900"), null, "growth", "maker", NOW);
        assertThat(change.previousLimit()).isEqualByComparingTo("500");
        assertThat(change.previousSoftLimit()).isEqualByComparingTo("400");
    }

    @Test
    @DisplayName("only a suspended or blacklisted customer is out of trading")
    void tradingStanding() {
        BusinessPartner draft = customer();
        assertThat(draft.isTradingCustomer()).isTrue();
        BusinessPartner active = draft.transitionTo(BusinessPartner.PartnerStatus.PENDING_VERIFICATION)
                .transitionTo(BusinessPartner.PartnerStatus.ACTIVE);
        assertThat(active.isTradingCustomer()).isTrue();
        assertThat(active.transitionTo(BusinessPartner.PartnerStatus.SUSPENDED).isTradingCustomer())
                .isFalse();
        assertThat(active.transitionTo(BusinessPartner.PartnerStatus.BLACKLISTED).isTradingCustomer())
                .isFalse();
    }
}
