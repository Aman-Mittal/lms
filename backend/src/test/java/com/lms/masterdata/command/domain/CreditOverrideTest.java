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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** What a credit override covers, and for how long. */
class CreditOverrideTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

    private static CreditOverride upTo(String amount) {
        return CreditOverride.grant(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                CreditOverride.SubjectType.ORDER, "ORDER:x", new BigDecimal(amount),
                "strategic", "controller", NOW);
    }

    @Test
    @DisplayName("covers exposure up to its figure and no further")
    void coversUpToItsFigure() {
        CreditOverride pass = upTo("1200");
        assertThat(pass.covers(new BigDecimal("1200"), NOW)).isTrue();
        assertThat(pass.covers(new BigDecimal("1200.01"), NOW)).isFalse();
    }

    @Test
    @DisplayName("is used up by the first action and lapses unused after a day")
    void usedOnceAndLapses() {
        CreditOverride pass = upTo("1200");
        assertThat(pass.use(new BigDecimal("1100"), NOW).covers(new BigDecimal("1"), NOW)).isFalse();
        assertThat(pass.covers(BigDecimal.ONE, NOW.plus(CreditOverride.VALIDITY))).isFalse();
        assertThat(pass.covers(BigDecimal.ONE, NOW.plus(CreditOverride.VALIDITY).minusSeconds(1)))
                .isTrue();
    }
}
