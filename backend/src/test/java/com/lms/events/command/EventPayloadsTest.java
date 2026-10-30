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
package com.lms.events.command;

import java.lang.reflect.Method;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class EventPayloadsTest {

    private final EventPayloads payloads = new EventPayloads(JsonMapper.builder().build());

    @Test
    @DisplayName("the tenant is the payload's top-level tenantId")
    void tenantFromPayload() {
        UUID tenant = UUID.randomUUID();
        assertThat(payloads.tenantOf("{\"tenantId\":\"" + tenant + "\",\"tripId\":\"x\"}")).contains(tenant);
    }

    @Test
    @DisplayName("no tenant, a malformed one, or no payload at all means no tenant")
    void noTenant() {
        assertThat(payloads.tenantOf("{\"tripId\":\"x\"}")).isEmpty();
        assertThat(payloads.tenantOf("{\"tenantId\":\"not-a-uuid\"}")).isEmpty();
        assertThat(payloads.tenantOf("{\"tenantId\":42}")).isEmpty();
        assertThat(payloads.tenantOf("not json")).isEmpty();
        assertThat(payloads.tenantOf("")).isEmpty();
        assertThat(payloads.tenantOf((String) null)).isEmpty();
    }

    @Test
    @DisplayName("a recorded error names the exception and is cut to a showable length")
    void errorDescription() {
        assertThat(ListenerFailureRecorder.describe(new IllegalStateException("boom")))
                .isEqualTo("IllegalStateException: boom");
        assertThat(ListenerFailureRecorder.describe(new IllegalStateException())).isEqualTo("IllegalStateException");
        assertThat(ListenerFailureRecorder.describe(new IllegalStateException("x".repeat(5000)))).hasSize(1000);
    }

    static class SomeListener {
        @TransactionalEventListener
        void on(String event) {
        }
    }

    @Test
    @DisplayName("a listener is named as the registry names it: its qualified method and parameter types")
    void listenerIdentifier() throws NoSuchMethodException {
        Method method = SomeListener.class.getDeclaredMethod("on", String.class);
        assertThat(ListenerFailureRecorder.listenerIdOf(method))
                .isEqualTo(SomeListener.class.getName() + ".on(java.lang.String)");
    }
}
