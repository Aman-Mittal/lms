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
package com.lms.order.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderNumberSequenceTest {

    @Test
    @DisplayName("an assigned number is the prefix, the year and a six-digit counter")
    void format() {
        assertThat(OrderNumberSequence.format(2026, 1)).isEqualTo("ORD-2026-000001");
        assertThat(OrderNumberSequence.format(2026, 42)).isEqualTo("ORD-2026-000042");
        assertThat(OrderNumberSequence.format(2027, 999_999)).isEqualTo("ORD-2027-999999");
    }

    @Test
    @DisplayName("past six digits the counter grows a digit rather than wrapping")
    void overflowGrows() {
        assertThat(OrderNumberSequence.format(2026, 1_000_000)).isEqualTo("ORD-2026-1000000");
    }

    @Test
    @DisplayName("a counter value below one is a defect, not a number")
    void nonPositiveRefused() {
        assertThatThrownBy(() -> OrderNumberSequence.format(2026, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
