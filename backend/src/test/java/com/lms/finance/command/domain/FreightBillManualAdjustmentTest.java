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

package com.lms.finance.command.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A manual charge moves a bill's amount only while the bill is a draft nobody has judged. */
class FreightBillManualAdjustmentTest {

    private static FreightBill draft() {
        BigDecimal amount = new BigDecimal("10000");
        CostSheet sheet = new CostSheet("INR", new BigDecimal("5000"), BigDecimal.ZERO, 1, amount, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO,
                List.of(new CostSheet.Charge(FreightBillLine.ChargeType.BASE_FREIGHT, "Base", BigDecimal.ONE, "run",
                        amount, amount)));
        return FreightBill.priced(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "FB-1", null, LocalDate.now(), sheet);
    }

    @Test
    @DisplayName("a draft takes a charge and gives it back; the tariff's own figures are untouched")
    void draftIsAdjusted() {
        FreightBill bill = draft();
        FreightBill with = bill.withManualAdjustment(new BigDecimal("300"));
        assertThat(with.computedAmount()).isEqualByComparingTo("10300");
        assertThat(with.baseFreight()).isEqualByComparingTo("10000");
        assertThat(with.withManualAdjustment(new BigDecimal("-300")).computedAmount()).isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("an approved, disputed or cancelled bill is never moved")
    void judgedBillIsNotMoved() {
        FreightBill approved = draft().matchAgainst(new BigDecimal("10000"), new BigDecimal("2"), new BigDecimal("100"),
                java.time.Instant.now());
        assertThat(approved.status()).isEqualTo(FreightBill.BillStatus.APPROVED_FOR_PAYMENT);
        assertThatThrownBy(() -> approved.withManualAdjustment(BigDecimal.TEN))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo("bill-not-open"));
        FreightBill disputed = draft().matchAgainst(new BigDecimal("20000"), new BigDecimal("2"), new BigDecimal("100"),
                java.time.Instant.now());
        assertThat(disputed.status()).isEqualTo(FreightBill.BillStatus.DISPUTED);
        assertThatThrownBy(() -> disputed.withManualAdjustment(BigDecimal.TEN))
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> draft().cancel("Superseded").withManualAdjustment(BigDecimal.TEN))
                .isInstanceOf(BusinessRuleViolationException.class);
    }
}
