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

class SearchTest {

    @Test
    @DisplayName("nothing typed means no search at all")
    void blankIsNoSearch() {
        assertThat(Search.containsPattern(null)).isNull();
        assertThat(Search.containsPattern("")).isNull();
        assertThat(Search.containsPattern("   ")).isNull();
    }

    @Test
    @DisplayName("the text is trimmed, lower-cased and matched anywhere")
    void containsIgnoringCase() {
        assertThat(Search.containsPattern("  Steel ")).isEqualTo("%steel%");
    }

    @Test
    @DisplayName("LIKE wildcards and the escape character are taken literally")
    void wildcardsAreLiteral() {
        assertThat(Search.containsPattern("BIN_1")).isEqualTo("%bin\\_1%");
        assertThat(Search.containsPattern("50%")).isEqualTo("%50\\%%");
        assertThat(Search.containsPattern("a\\b")).isEqualTo("%a\\\\b%");
    }

    @Test
    @DisplayName("an over-long search is cut to the maximum")
    void overLongIsCut() {
        assertThat(Search.containsPattern("x".repeat(500))).hasSize(Search.MAX_LENGTH + 2);
    }
}
