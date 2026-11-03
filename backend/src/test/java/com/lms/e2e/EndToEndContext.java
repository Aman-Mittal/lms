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
package com.lms.e2e;

import com.lms.LmsApplication;
import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The running application the end-to-end scenarios talk to.
 *
 * <p>Started exactly as a demo deployment is: the demo seed switched on, so
 * the first thing a scenario does is sign in over HTTP with the seeded
 * administrator -- the same first step anybody trying the Render deployment
 * takes.
 */
@CucumberContextConfiguration
@SpringBootTest(classes = LmsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class EndToEndContext {

    static final String DEMO_PASSWORD = "e2e demo password, not a real one";

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
        registry.add("lms.demo.enabled", () -> "true");
        registry.add("lms.demo.admin-password", () -> DEMO_PASSWORD);
        // A demo deployment hands the delivery code back, which is what lets a scenario play the consignee.
        registry.add("lms.delivery.expose-otp", () -> "true");
        // Customers' reads of a tracking link come from one address; the limit has its own test.
        registry.add("lms.tracking.rate-limit.requests", () -> "100000");
        // The dead-letter scenarios fail a probe event on purpose and park it in one pass:
        // the probe listener exists here only, and the retrier is driven by the steps.
        registry.add("lms.test.probe-listener", () -> "true");
        registry.add("lms.events.retry.enabled", () -> "false");
        registry.add("lms.events.retry.max-attempts", () -> "1");
        // Webhook scenarios post to a receiver on this machine, over plain http, and
        // wait for the platform's own timer: it ticks fast here and backs off briefly.
        registry.add("lms.webhooks.allow-http", () -> "true");
        registry.add("lms.webhooks.allow-private-targets", () -> "true");
        registry.add("lms.webhooks.dispatcher.initial-delay-ms", () -> "1000");
        registry.add("lms.webhooks.dispatcher.interval-ms", () -> "500");
        registry.add("lms.webhooks.initial-backoff", () -> "PT1S");
        registry.add("lms.webhooks.max-backoff", () -> "PT2S");
    }
}
