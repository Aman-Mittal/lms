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
package com.lms.shared.query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Which records a list of a switch-on-and-off register shows. */
class ActiveFilterTest {

    @Test
    @DisplayName("with no status the list is the active records, whatever else is said")
    void activeByDefault() {
        assertThat(ActiveFilter.of(null)).isTrue();
        assertThat(ActiveFilter.of("  ")).isTrue();
        assertThat(ActiveFilter.of(null, null)).isTrue();
    }

    @Test
    @DisplayName("ACTIVE, INACTIVE and ALL, in any case; ALL is no filter at all")
    void namedStatuses() {
        assertThat(ActiveFilter.of("ACTIVE")).isTrue();
        assertThat(ActiveFilter.of("inactive")).isFalse();
        assertThat(ActiveFilter.of(" All ")).isNull();
    }

    @Test
    @DisplayName("an older active flag still answers, and the status wins when both are given")
    void theOlderFlag() {
        assertThat(ActiveFilter.of(null, false)).isFalse();
        assertThat(ActiveFilter.of(null, true)).isTrue();
        assertThat(ActiveFilter.of("ALL", false)).isNull();
        assertThat(ActiveFilter.of("INACTIVE", true)).isFalse();
    }

    @Test
    @DisplayName("any other status is refused, not read as ALL")
    void unknownStatus() {
        assertThatThrownBy(() -> ActiveFilter.of("GONE")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GONE");
    }
}
