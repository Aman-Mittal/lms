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
package com.lms.clientbilling;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.clientbilling.command.ContractChecklist;
import com.lms.clientbilling.command.ContractChecklist.Facts;
import com.lms.clientbilling.command.domain.ClientContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The completeness rules a draft passes before verification can be asked for. */
class ContractChecklistTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);
    private static final Facts GOOD = new Facts(true, TODAY, null);

    private static ClientContract draft(BigDecimal perKg, BigDecimal minimum, LocalDate from, LocalDate till) {
        return ClientContract.draft(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "C-1",
                UUID.randomUUID(), "INR", from, till,
                new ClientContract.Rates(perKg, minimum, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), false);
    }

    private static ClientContract complete() {
        return draft(new BigDecimal("5"), BigDecimal.ZERO, TODAY.minusDays(1), null);
    }

    @Test
    @DisplayName("a complete draft fails nothing")
    void completePasses() {
        assertThat(ContractChecklist.failures(complete(), GOOD, ContractChecklist.ITEMS)).isEmpty();
    }

    @Test
    @DisplayName("every failing rule is named, in checklist order, not only the first")
    void listsEveryFailure() {
        ClientContract bad = draft(BigDecimal.ZERO, BigDecimal.ZERO, TODAY.minusDays(20), TODAY.minusDays(2));
        Map<String, String> failures = ContractChecklist.failures(bad, new Facts(false, TODAY, null),
                ContractChecklist.ITEMS);
        assertThat(failures.keySet()).containsExactly("CUSTOMER_TRADING", "EFFECTIVE_DATES", "TRANSPORT_RATE");
        assertThat(failures.get("EFFECTIVE_DATES")).contains("already passed");
    }

    @Test
    @DisplayName("a minimum charge alone prices transport")
    void minimumIsAPrice() {
        assertThat(ContractChecklist.failures(draft(BigDecimal.ZERO, new BigDecimal("100"), TODAY, null), GOOD,
                List.of(ContractChecklist.TRANSPORT_RATE))).isEmpty();
        assertThat(ContractChecklist.failures(draft(BigDecimal.ZERO, BigDecimal.ZERO, TODAY, null), GOOD,
                List.of(ContractChecklist.TRANSPORT_RATE))).containsKey("TRANSPORT_RATE");
    }

    @Test
    @DisplayName("the start must follow the version in force, which is judged by its own start")
    void startFollowsIncumbent() {
        Facts incumbent = new Facts(true, TODAY, TODAY.minusDays(1));
        assertThat(ContractChecklist.failures(complete(), incumbent, List.of(ContractChecklist.EFFECTIVE_DATES)))
                .containsKey("EFFECTIVE_DATES");
        assertThat(ContractChecklist.failures(complete(), new Facts(true, TODAY, TODAY.minusDays(2)),
                List.of(ContractChecklist.EFFECTIVE_DATES))).isEmpty();
    }

    @Test
    @DisplayName("a rule the tenant does not require is not checked")
    void onlyRequiredRulesApply() {
        ClientContract bad = draft(BigDecimal.ZERO, BigDecimal.ZERO, TODAY, null);
        assertThat(ContractChecklist.failures(bad, new Facts(false, TODAY, null), List.of())).isEmpty();
        assertThat(ContractChecklist.failures(bad, new Facts(false, TODAY, null),
                List.of(ContractChecklist.CUSTOMER_TRADING))).containsOnlyKeys("CUSTOMER_TRADING");
    }

    @Test
    @DisplayName("tax rates are percentages and the unit is named")
    void taxAndUnit() {
        ClientContract taxed = ClientContract.draft(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "C",
                UUID.randomUUID(), "INR", TODAY, null,
                new ClientContract.Rates(BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, new BigDecimal("18"), new BigDecimal("12.5"), null), false);
        assertThat(ContractChecklist.failures(taxed, GOOD, ContractChecklist.ITEMS)).isEmpty();
    }
}
