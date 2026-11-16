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

package com.lms.order.command.enquiry;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.lms.shared.error.BusinessRuleViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnquiryRulesTest {

    private static final List<LineStatus> NONE = List.of();

    @Test
    @DisplayName("any open line keeps the enquiry open, whatever else was decided")
    void openWhileAnyLineIsOpen() {
        assertThat(EnquiryRules.derive(List.of(LineStatus.WON, LineStatus.OPEN))).isEqualTo(EnquiryStatus.OPEN);
        assertThat(EnquiryRules.derive(NONE)).isEqualTo(EnquiryStatus.OPEN);
    }

    @Test
    @DisplayName("decided lines make the enquiry won, partial or lost")
    void decided() {
        assertThat(EnquiryRules.derive(List.of(LineStatus.WON, LineStatus.WON))).isEqualTo(EnquiryStatus.WON);
        assertThat(EnquiryRules.derive(List.of(LineStatus.WON, LineStatus.LOST))).isEqualTo(EnquiryStatus.PARTIAL);
        assertThat(EnquiryRules.derive(List.of(LineStatus.PARTIAL))).isEqualTo(EnquiryStatus.PARTIAL);
        assertThat(EnquiryRules.derive(List.of(LineStatus.WON, LineStatus.CANCELLED)))
                .as("a won line kept beside a cancelled one is a partial win").isEqualTo(EnquiryStatus.PARTIAL);
        assertThat(EnquiryRules.derive(List.of(LineStatus.LOST, LineStatus.CANCELLED))).isEqualTo(EnquiryStatus.LOST);
    }

    @Test
    @DisplayName("the deadline is the creation time plus the policy's hours, and nothing else")
    void deadlineIsFixed() {
        Instant created = Instant.parse("2026-11-16T10:00:00Z");
        assertThat(EnquiryRules.deadline(created, 72)).isEqualTo(created.plus(Duration.ofHours(72)));
        assertThat(EnquiryRules.deadline(created, 1)).isEqualTo(Instant.parse("2026-11-16T11:00:00Z"));
    }

    @Test
    @DisplayName("an outcome states what it needs: a reason when lost, a quantity inside the line when partial")
    void outcomes() {
        BigDecimal ten = new BigDecimal("10");
        assertThat(EnquiryRules.wonQuantity(LineOutcome.WON, ten, null, null)).isEqualByComparingTo("10");
        assertThat(EnquiryRules.wonQuantity(LineOutcome.WON, ten, new BigDecimal("3"), null))
                .as("a stated quantity is ignored for WON").isEqualByComparingTo("10");
        assertThat(EnquiryRules.wonQuantity(LineOutcome.PARTIAL, ten, new BigDecimal("4.5"), null))
                .isEqualByComparingTo("4.5");
        assertThat(EnquiryRules.wonQuantity(LineOutcome.LOST, ten, null, "price")).isNull();

        assertThatThrownBy(() -> EnquiryRules.wonQuantity(LineOutcome.LOST, ten, null, "  "))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo("enquiry-lost-reason-required"));
        for (String stated : new String[] {null, "0", "-1", "10", "11"}) {
            assertThatThrownBy(() -> EnquiryRules.wonQuantity(LineOutcome.PARTIAL, ten,
                    stated == null ? null : new BigDecimal(stated), null))
                    .as("partial " + stated)
                    .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                            e -> assertThat(e.code()).isEqualTo("enquiry-partial-quantity-invalid"));
        }
    }

    @Test
    @DisplayName("load types compare upper case and blank is none")
    void loadTypes() {
        assertThat(EnquiryRules.normaliseLoadType(" 32ft ")).isEqualTo("32FT");
        assertThat(EnquiryRules.normaliseLoadType("  ")).isNull();
        assertThat(EnquiryRules.normaliseLoadType(null)).isNull();
    }
}
