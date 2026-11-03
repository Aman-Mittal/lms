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
package com.lms.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

class CorsConfigurationTest {

    private static final String CONSOLE = "https://console.example.org";

    private final CorsConfiguration cors = new SecurityConfig(CONSOLE + ",http://localhost:4200")
            .corsConfigurationSource()
            .getCorsConfiguration(new MockHttpServletRequest("PUT", "/api/v1/orders/1/lines"));

    @Test
    @DisplayName("a console edit may send the version it read and an idempotency key")
    void editHeadersAreAllowed() {
        assertThat(cors.checkOrigin(CONSOLE)).isEqualTo(CONSOLE);
        assertThat(cors.checkHttpMethod(HttpMethod.PUT)).isNotNull();
        assertThat(cors.checkHeaders(List.of("authorization", "content-type", "if-match", "idempotency-key")))
                .containsExactlyInAnyOrder("authorization", "content-type", "if-match", "idempotency-key");
    }

    @Test
    @DisplayName("a console can read the version an order was served at")
    void etagIsExposed() {
        assertThat(cors.getExposedHeaders()).contains("ETag");
    }

    @Test
    @DisplayName("an origin that was not configured is still refused")
    void otherOriginsAreRefused() {
        assertThat(cors.checkOrigin("https://elsewhere.example.org")).isNull();
    }
}
