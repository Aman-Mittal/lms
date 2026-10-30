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

import java.util.UUID;

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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where a deployment does not expose the code (the default), the response that
 * issues it must not carry it: the code belongs to the consignee, and whoever
 * holds the delivery desk could otherwise confirm a delivery that never happened.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeliveryOtpHiddenApiTest {

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
        registry.add("lms.delivery.expose-otp", () -> "false");
    }

    @LocalServerPort
    private int port;
    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private TokenService tokens;
    @Autowired
    private TenantSeeds seeds;
    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("the response that issues a code does not carry it unless the deployment exposes it")
    void codeIsNotReturned() {
        ApiTestKit kit = new ApiTestKit(fixtures, tokens, port);
        Seeded seeded = seeds.seed(0.0);
        UUID consignment = seeded.id("consignment");
        jdbc.sql("UPDATE consignment SET status = 'IN_TRANSIT' WHERE id = :c").param("c", consignment).update();

        Outcome issued = kit.post(kit.tenantOf(seeded.tenantId(), seeded.orgUnitId(), seeded.orgPath()),
                "/api/v1/deliveries/" + consignment + "/otp", "{}",
                seeds.allPermissions().toArray(String[]::new));

        assertThat(issued.status()).as(issued.body()).isEqualTo(200);
        JsonNode body = kit.parse(issued);
        assertThat(body.has("expiresAt")).isTrue();
        assertThat(body.has("otp")).as("the code is not in the answer: %s", issued.body()).isFalse();
    }
}
