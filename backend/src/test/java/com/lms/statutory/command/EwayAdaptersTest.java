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
package com.lms.statutory.command;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.lms.statutory.api.EwayBillAction;
import com.lms.statutory.api.EwayBillAdapter;
import com.lms.statutory.api.EwayBillRequest;
import com.lms.statutory.api.EwayBillResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EwayAdaptersTest {

    private static final EwayBillRequest REQUEST = new EwayBillRequest(EwayBillAction.GENERATE, "LR-1", null, null,
            null, 100, 200, 24, null, null, null, 0, Instant.parse("2026-11-01T06:00:00Z"));

    private static EwayBillAdapter adapter(String code, java.util.function.Function<EwayBillRequest, EwayBillResponse> body) {
        return new EwayBillAdapter() {
            @Override
            public String code() {
                return code;
            }

            @Override
            public EwayBillResponse execute(EwayBillRequest request) {
                return body.apply(request);
            }
        };
    }

    @Test
    @DisplayName("adapters are found by code in any case; NONE and unknown codes find nothing")
    void lookup() {
        EwayAdapters adapters = new EwayAdapters(List.of(new StubEwayBillAdapter()));
        assertThat(adapters.find("STUB")).isPresent();
        assertThat(adapters.find("stub")).isPresent();
        assertThat(adapters.find("NONE")).isEmpty();
        assertThat(adapters.find(null)).isEmpty();
    }

    @Test
    @DisplayName("a call that answers in time returns the answer")
    void answersInTime() {
        EwayAdapters adapters = new EwayAdapters(List.of());
        EwayBillResponse answer = adapters.call(new StubEwayBillAdapter(), REQUEST, Duration.ofSeconds(5));
        assertThat(answer.accepted()).isTrue();
    }

    @Test
    @DisplayName("a call that overruns its timeout is abandoned and reported as eway-adapter-timeout")
    void timesOut() {
        EwayAdapters adapters = new EwayAdapters(List.of());
        EwayBillAdapter slow = adapter("SLOW", request -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return EwayBillResponse.accepted("1", null, null, null);
        });
        long started = System.nanoTime();
        assertThatThrownBy(() -> adapters.call(slow, REQUEST, Duration.ofMillis(100)))
                .isInstanceOfSatisfying(EwayAdapters.CallFailed.class,
                        failed -> assertThat(failed.code()).isEqualTo("eway-adapter-timeout"));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("an adapter that throws, or answers nothing, is reported as eway-adapter-failed")
    void failures() {
        EwayAdapters adapters = new EwayAdapters(List.of());
        EwayBillAdapter broken = adapter("BROKEN", request -> {
            throw new IllegalStateException("transport down");
        });
        assertThatThrownBy(() -> adapters.call(broken, REQUEST, Duration.ofSeconds(2)))
                .isInstanceOfSatisfying(EwayAdapters.CallFailed.class,
                        failed -> assertThat(failed.code()).isEqualTo("eway-adapter-failed"));
        EwayBillAdapter silent = adapter("SILENT", request -> null);
        assertThatThrownBy(() -> adapters.call(silent, REQUEST, Duration.ofSeconds(2)))
                .isInstanceOfSatisfying(EwayAdapters.CallFailed.class,
                        failed -> assertThat(failed.code()).isEqualTo("eway-adapter-failed"));
    }
}
