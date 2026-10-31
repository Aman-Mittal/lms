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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignerTest {

    @Test
    @DisplayName("the signature is a versioned HMAC-SHA256 of the timestamp, a dot and the body")
    void knownVector() {
        // Worked out independently: printf '1700000000.{"a":1}' | openssl dgst -sha256 -hmac key
        assertThat(WebhookSigner.sign("key", 1_700_000_000L, "{\"a\":1}"))
                .isEqualTo("v1=a438e398bfafc57e4396bb7fc2304422f0f768e965d073ca313cb52e22e6ad03");
    }

    @Test
    @DisplayName("a different timestamp gives a different signature, so an old one cannot be replayed as new")
    void timestampIsSigned() {
        assertThat(WebhookSigner.sign("key", 1_700_000_001L, "{\"a\":1}"))
                .isEqualTo("v1=ac282d739e25be582bf7a9c2166c50870eacc71f9e4260fe2822885f6fcf09f7");
    }
}
