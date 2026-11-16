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
import java.util.Locale;

import com.lms.shared.error.BusinessRuleViolationException;

/**
 * The enquiry's rules that need no database: what the lines say about the
 * enquiry, what an outcome may be, and when a deadline falls. Kept apart so
 * they are read, and tested, on their own.
 */
public final class EnquiryRules {

    /** Lines in one enquiry. */
    public static final int MAX_LINES = 100;

    private EnquiryRules() {
    }

    /**
     * The status the lines give an open or decided enquiry. Any open line keeps
     * it OPEN; with none, it is WON when every line won, PARTIAL when some did,
     * LOST when none did (a lone cancelled line counts as not won).
     */
    public static EnquiryStatus derive(List<LineStatus> lines) {
        if (lines.isEmpty() || lines.contains(LineStatus.OPEN)) {
            return EnquiryStatus.OPEN;
        }
        long won = lines.stream().filter(LineStatus::isWon).count();
        if (won == 0) {
            return EnquiryStatus.LOST;
        }
        boolean allWholeWins = lines.stream().allMatch(l -> l == LineStatus.WON);
        return allWholeWins ? EnquiryStatus.WON : EnquiryStatus.PARTIAL;
    }

    /** The deadline an enquiry made at {@code created} is given: fixed, never moved afterwards. */
    public static Instant deadline(Instant created, int hours) {
        return created.plus(Duration.ofHours(hours));
    }

    /**
     * The quantity won for an outcome, or the refusal.
     *
     * @param quantity the line's quantity
     * @param stated   the quantity won, for PARTIAL; ignored for WON and LOST
     * @param reason   why it was lost; required for LOST
     */
    public static BigDecimal wonQuantity(LineOutcome outcome, BigDecimal quantity, BigDecimal stated, String reason) {
        return switch (outcome) {
            case WON -> quantity;
            case LOST -> {
                if (reason == null || reason.isBlank()) {
                    throw new BusinessRuleViolationException("enquiry-lost-reason-required",
                            "A line that is lost says why");
                }
                yield null;
            }
            case PARTIAL -> {
                if (stated == null || stated.signum() <= 0 || stated.compareTo(quantity) >= 0) {
                    throw new BusinessRuleViolationException("enquiry-partial-quantity-invalid",
                            "A partly won line states a quantity above zero and below the " + quantity
                                    + " enquired");
                }
                yield stated;
            }
        };
    }

    /** Upper case, trimmed; blank is none. */
    public static String normaliseLoadType(String value) {
        return value == null || value.isBlank() ? null : value.strip().toUpperCase(Locale.ROOT);
    }
}
