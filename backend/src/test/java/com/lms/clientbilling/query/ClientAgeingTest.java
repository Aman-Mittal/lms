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
package com.lms.clientbilling.query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Payment allocation and ageing buckets, without a database. */
class ClientAgeingTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 31);

    private static ClientAgeing.Invoice invoice(String no, LocalDate issued, String total,
                                                String credited) {
        return new ClientAgeing.Invoice(UUID.randomUUID(), no, issued.withDayOfMonth(1), issued,
                new BigDecimal(total), new BigDecimal(credited));
    }

    @Test
    void bucketsFollowDaysSinceIssue() {
        assertThat(ClientAgeing.bucketOf(0)).isEqualTo("0-30");
        assertThat(ClientAgeing.bucketOf(30)).isEqualTo("0-30");
        assertThat(ClientAgeing.bucketOf(31)).isEqualTo("31-60");
        assertThat(ClientAgeing.bucketOf(60)).isEqualTo("31-60");
        assertThat(ClientAgeing.bucketOf(61)).isEqualTo("61-90");
        assertThat(ClientAgeing.bucketOf(90)).isEqualTo("61-90");
        assertThat(ClientAgeing.bucketOf(91)).isEqualTo("90+");
    }

    @Test
    void paymentsSettleTheOldestInvoiceFirst() {
        var result = ClientAgeing.allocate(List.of(
                invoice("INV-1", TODAY.minusDays(70), "1000", "0"),
                invoice("INV-2", TODAY.minusDays(40), "500", "0"),
                invoice("INV-3", TODAY.minusDays(5), "300", "0")), new BigDecimal("1200"), TODAY);

        assertThat(result.rows()).extracting(ClientAgeing.Row::outstanding)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(BigDecimal.ZERO, new BigDecimal("300"), new BigDecimal("300"));
        assertThat(result.rows()).extracting(ClientAgeing.Row::paid)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("1000"), new BigDecimal("200"), BigDecimal.ZERO);
        assertThat(result.unappliedCredit()).isEqualByComparingTo("0");
    }

    @Test
    void aCreditNoteReducesItsOwnInvoiceAndNoOther() {
        var result = ClientAgeing.allocate(List.of(
                invoice("INV-1", TODAY.minusDays(70), "1000", "400"),
                invoice("INV-2", TODAY.minusDays(10), "500", "0")), BigDecimal.ZERO, TODAY);

        assertThat(result.rows().get(0).outstanding()).isEqualByComparingTo("600");
        assertThat(result.rows().get(1).outstanding()).isEqualByComparingTo("500");
    }

    @Test
    void whatPaymentsExceedIsUnappliedCredit() {
        var result = ClientAgeing.allocate(List.of(
                invoice("INV-1", TODAY.minusDays(10), "500", "0")), new BigDecimal("800"), TODAY);

        assertThat(result.rows().get(0).outstanding()).isEqualByComparingTo("0");
        assertThat(result.unappliedCredit()).isEqualByComparingTo("300");
    }

    @Test
    void anInvoiceFullyCreditedOwesNothingAndConsumesNoPayment() {
        var result = ClientAgeing.allocate(List.of(
                invoice("INV-1", TODAY.minusDays(50), "500", "500"),
                invoice("INV-2", TODAY.minusDays(10), "200", "0")), new BigDecimal("200"), TODAY);

        assertThat(result.rows().get(0).paid()).isEqualByComparingTo("0");
        assertThat(result.rows().get(1).outstanding()).isEqualByComparingTo("0");
    }

    @Test
    void anInvoiceIssuedAfterTheDayAgedToIsNotOverdue() {
        var result = ClientAgeing.allocate(List.of(
                invoice("INV-1", TODAY.plusDays(3), "100", "0")), BigDecimal.ZERO, TODAY);

        assertThat(result.rows().get(0).ageDays()).isZero();
        assertThat(result.rows().get(0).bucket()).isEqualTo("0-30");
    }
}
