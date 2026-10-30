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
package com.lms.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.lms.acceptance.TenantSeeds;
import com.lms.acceptance.TenantSeeds.Seeded;
import com.lms.acceptance.TestFixtures;
import com.lms.identity.security.TokenService;
import com.lms.web.ApiTestKit.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The public route is limited per client address. With a limit of three, the
 * fourth request from one address in the window is refused with 429 and a
 * Retry-After, while another address is unaffected.
 *
 * <p>Client addresses are set with X-Forwarded-For, which the embedded server
 * honours only from a trusted proxy -- here the loopback the test connects
 * from, exactly as a private-range proxy is trusted in a deployment.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrackingRateLimitApiTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.7-alpine")
            .withDatabaseName("lms").withUsername("lms").withPassword("lms");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("lms.tracking.rate-limit.requests", () -> "3");
        registry.add("lms.tracking.rate-limit.window-seconds", () -> "60");
    }

    @LocalServerPort
    private int port;
    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private TokenService tokens;
    @Autowired
    private TenantSeeds seeds;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(String token, String clientAddress) throws Exception {
        return http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/public/tracking/" + token))
                .header("X-Forwarded-For", clientAddress).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("the fourth request in a window is refused with 429, and another address is not affected")
    void limitedPerClientAddress() throws Exception {
        ApiTestKit kit = new ApiTestKit(fixtures, tokens, port);
        Seeded seeded = seeds.seed(0.0);
        Outcome issued = kit.post(kit.tenantOf(seeded.tenantId(), seeded.orgUnitId(), seeded.orgPath()),
                "/api/v1/consignments/" + seeded.id("consignment") + "/tracking-links", "{}",
                seeds.allPermissions().toArray(String[]::new));
        assertThat(issued.status()).as(issued.body()).isEqualTo(201);
        String token = kit.parse(issued).get("token").asString();

        for (int i = 1; i <= 3; i++) {
            assertThat(get(token, "203.0.113.10").statusCode()).as("request %d", i).isEqualTo(200);
        }
        HttpResponse<String> limited = get(token, "203.0.113.10");
        assertThat(limited.statusCode()).as(limited.body()).isEqualTo(429);
        assertThat(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow()))
                .isBetween(1L, 60L);
        assertThat(limited.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(kit.parse(new Outcome(429, limited.body(), 0)).get("code").asString()).isEqualTo("rate-limited");

        assertThat(get(token, "203.0.113.11").statusCode()).as("another client").isEqualTo(200);
        assertThat(get("not-a-token", "203.0.113.10").statusCode())
                .as("a wrong token counts against the same client").isEqualTo(429);
    }
}
