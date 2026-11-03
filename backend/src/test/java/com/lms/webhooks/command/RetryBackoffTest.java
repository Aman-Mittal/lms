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
package com.lms.webhooks.command;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryBackoffTest {

    private final RetryBackoff backoff = new RetryBackoff(Duration.ofSeconds(30), Duration.ofMinutes(10));

    @Test
    @DisplayName("each failure doubles the wait, up to the cap")
    void doublesToTheCap() {
        assertThat(backoff.after(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(backoff.after(2)).isEqualTo(Duration.ofSeconds(60));
        assertThat(backoff.after(3)).isEqualTo(Duration.ofSeconds(120));
        assertThat(backoff.after(5)).isEqualTo(Duration.ofSeconds(480));
        assertThat(backoff.after(6)).isEqualTo(Duration.ofMinutes(10));
        assertThat(backoff.after(60)).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("a backoff that starts at zero or caps below its start is refused")
    void nonsenseRefused() {
        assertThatThrownBy(() -> new RetryBackoff(Duration.ZERO, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryBackoff(Duration.ofMinutes(2), Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
