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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.clientbilling.command.domain.CreditNoteAllocation;
import com.lms.clientbilling.command.domain.CreditNoteAllocation.Credit;
import com.lms.clientbilling.command.domain.CreditNoteAllocation.Creditable;
import com.lms.clientbilling.command.domain.ServiceType;
import com.lms.clientbilling.command.domain.Tax;
import com.lms.shared.error.BusinessRuleViolationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Credit-note limits and tax reversal, without a database.
 */
class CreditNoteAllocationTest {

    private static Creditable line(int no, String taxable, String rate, String creditedTaxable,
                                   String creditedTax) {
        BigDecimal amount = new BigDecimal(taxable);
        BigDecimal pct = new BigDecimal(rate);
        return new Creditable(UUID.randomUUID(), no, ServiceType.STORAGE, "Storage", amount, pct,
                Tax.on(amount, pct), new BigDecimal(creditedTaxable),
                new BigDecimal(creditedTax));
    }

    private static Map<Integer, BigDecimal> request(Object... lineAndAmount) {
        Map<Integer, BigDecimal> map = new HashMap<>();
        for (int i = 0; i < lineAndAmount.length; i += 2) {
            map.put((Integer) lineAndAmount[i],
                    lineAndAmount[i + 1] == null ? null : new BigDecimal((String) lineAndAmount[i + 1]));
        }
        return map;
    }

    @Test
    void aWholeLineReversesExactlyItsTax() {
        Creditable storage = line(1, "3600.00", "18", "0", "0");
        List<Credit> credits = CreditNoteAllocation.forLines(List.of(storage), request(1, null));
        assertThat(credits).singleElement().satisfies(c -> {
            assertThat(c.taxable()).isEqualByComparingTo("3600.00");
            assertThat(c.tax()).isEqualByComparingTo("648.00");
        });
    }

    @Test
    void aPartOfALineReversesTaxAtTheLineRate() {
        Creditable storage = line(1, "3600.00", "18", "0", "0");
        List<Credit> credits = CreditNoteAllocation.forLines(List.of(storage),
                request(1, "1000"));
        assertThat(credits.get(0).taxable()).isEqualByComparingTo("1000.00");
        assertThat(credits.get(0).tax()).isEqualByComparingTo("180.00");
    }

    @Test
    void theCreditThatExhaustsALineTakesTheTaxThatIsLeftNotARecomputedFigure() {
        // 0.25 at 18 % charged 0.05 of tax (0.045 -> 0.05). Credited as 0.13
        // and then the remaining 0.12, recomputing each piece would reverse
        // 0.02 + 0.02 = 0.04 and leave a cent of tax charged on a line that
        // has been credited in full.
        Creditable first = line(1, "0.25", "18", "0", "0");
        Credit a = CreditNoteAllocation.forLines(List.of(first), request(1, "0.13")).get(0);
        assertThat(a.tax()).isEqualByComparingTo("0.02");    // 0.0234 -> 0.02

        Creditable rest = line(1, "0.25", "18", "0.13", "0.02");
        Credit b = CreditNoteAllocation.forLines(List.of(rest), request(1, null)).get(0);
        assertThat(b.taxable()).isEqualByComparingTo("0.12");
        // 0.12 x 18 % = 0.0216 -> 0.02, but 0.03 of tax remains on the line.
        assertThat(b.tax()).isEqualByComparingTo("0.03");
        assertThat(a.tax().add(b.tax())).isEqualByComparingTo(first.tax());
    }

    @Test
    void aLineCannotBeCreditedPastWhatRemains() {
        Creditable storage = line(1, "3600.00", "18", "3000.00", "540.00");
        assertThatThrownBy(() -> CreditNoteAllocation.forLines(List.of(storage),
                request(1, "600.01")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("600.00 left to credit");
    }

    @Test
    void aFullyCreditedLineCannotBeCreditedAgain() {
        Creditable storage = line(1, "100.00", "18", "100.00", "18.00");
        assertThatThrownBy(() -> CreditNoteAllocation.forLines(List.of(storage), request(1, null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("already been credited in full");
    }

    @Test
    void anUnknownLineIsRefused() {
        assertThatThrownBy(() -> CreditNoteAllocation.forLines(
                List.of(line(1, "10", "0", "0", "0")), request(7, "1")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("no line 7");
    }

    @Test
    void anAmountIsSpreadInProportionAndAddsUpToTheCent() {
        // Remaining 100, 200 and 300; credit 100 -> 16.67, 33.33, 50.00 by
        // largest remainder (16.666.. and 33.333.. compete for one cent).
        List<Creditable> lines = List.of(line(1, "100.00", "18", "0", "0"),
                line(2, "200.00", "18", "0", "0"), line(3, "300.00", "18", "0", "0"));
        List<Credit> credits = CreditNoteAllocation.forAmount(lines, new BigDecimal("100"));
        assertThat(credits).extracting(Credit::taxable).map(BigDecimal::toPlainString)
                .containsExactly("16.67", "33.33", "50.00");
        assertThat(credits.stream().map(Credit::taxable).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("100");
    }

    @Test
    void anAmountUsesOnlyWhatRemainsOnEachLine() {
        // Line 1 is spent; the whole amount lands on line 2.
        List<Creditable> lines = List.of(line(1, "100.00", "18", "100.00", "18.00"),
                line(2, "50.00", "5", "0", "0"));
        List<Credit> credits = CreditNoteAllocation.forAmount(lines, new BigDecimal("50"));
        assertThat(credits).singleElement().satisfies(c -> {
            assertThat(c.line().lineNo()).isEqualTo(2);
            assertThat(c.taxable()).isEqualByComparingTo("50.00");
            assertThat(c.tax()).isEqualByComparingTo("2.50");
        });
    }

    @Test
    void anAmountCannotExceedWhatRemainsOnTheInvoice() {
        List<Creditable> lines = List.of(line(1, "100.00", "18", "40.00", "7.20"),
                line(2, "50.00", "18", "0", "0"));
        assertThatThrownBy(() -> CreditNoteAllocation.forAmount(lines, new BigDecimal("110.01")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("110.00 left to credit");
    }

    @Test
    void creditingEverythingByAmountReversesAllTheTax() {
        List<Creditable> lines = List.of(line(1, "33.33", "18", "0", "0"),
                line(2, "33.33", "18", "0", "0"), line(3, "33.34", "5", "0", "0"));
        List<Credit> credits = CreditNoteAllocation.forAmount(lines, new BigDecimal("100.00"));
        BigDecimal tax = credits.stream().map(Credit::tax).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal charged = lines.stream().map(Creditable::tax)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(tax).isEqualByComparingTo(charged);
    }

    @Test
    void creditsAreMoney() {
        List<Creditable> lines = List.of(line(1, "100.00", "0", "0", "0"));
        assertThatThrownBy(() -> CreditNoteAllocation.forAmount(lines, new BigDecimal("1.005")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CreditNoteAllocation.forAmount(lines, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CreditNoteAllocation.requireDistinct(List.of(1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
