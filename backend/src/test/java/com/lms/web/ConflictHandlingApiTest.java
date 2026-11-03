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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import com.lms.acceptance.TestFixtures;
import com.lms.identity.security.TokenService;
import com.lms.web.ApiTestKit.Outcome;
import com.lms.web.ApiTestKit.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a lost race looks like on the wire.
 *
 * <p>Two writers racing for the same row, or the same business key, is routine
 * in a system whose clients retry: the loser did nothing wrong and must be told
 * to re-read and try again -- a 409 with a stable code -- never a 500 that
 * pages somebody. The domain checks "is the number taken" before inserting,
 * which narrows the window and cannot close it; the database constraint and
 * the {@code @Version} column are what actually settle the race, and this is
 * the test that the HTTP edge turns their failures into answers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConflictHandlingApiTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.7-alpine")
            .withDatabaseName("lms").withUsername("lms").withPassword("lms");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final int WRITERS = 12;
    private static final int ROUNDS = 8;
    private static final Duration LIMIT = Duration.ofSeconds(60);

    @LocalServerPort
    private int port;
    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private TokenService tokens;

    private ApiTestKit kit;
    private Tenant tenant;

    @BeforeEach
    void aTenant() {
        kit = new ApiTestKit(fixtures, tokens, port);
        tenant = kit.newTenant("acme");
    }

    @Test
    @DisplayName("the same partner code created concurrently: one 201, every other writer a 409, never a 500")
    void concurrentDuplicateCreate() {
        for (int round = 0; round < ROUNDS; round++) {
            String code = "RACE-" + round + "-" + UUID.randomUUID().toString().substring(0, 6);
            List<Callable<Outcome>> calls = new ArrayList<>();
            for (int i = 0; i < WRITERS; i++) {
                calls.add(() -> kit.post(tenant, "/api/v1/partners", partnerBody(code),
                        "PARTNER_CREATE"));
            }
            List<Outcome> outcomes = ApiTestKit.race(calls, LIMIT);

            assertThat(outcomes).as("round %d statuses", round)
                    .extracting(Outcome::status).doesNotContain(500);
            assertThat(outcomes).filteredOn(o -> o.status() == 201).hasSize(1);
            assertThat(outcomes).filteredOn(o -> o.status() != 201)
                    .allSatisfy(o -> {
                        assertThat(o.status()).isEqualTo(409);
                        assertThat(o.code()).isIn("partner-code-taken", "duplicate-resource");
                        assertNoInternals(o);
                    });
        }
    }

    @Test
    @DisplayName("concurrent state transitions of one partner: at least one 204 per round, every loser a 409, never a 500")
    void concurrentTransitions() {
        String code = "TRN-" + UUID.randomUUID().toString().substring(0, 6);
        UUID partner = createPartner(code);
        assertThat(transition(partner, "PENDING_VERIFICATION").status()).isEqualTo(204);
        assertThat(transition(partner, "ACTIVE").status()).isEqualTo(204);

        boolean toSuspended = true;
        for (int round = 0; round < ROUNDS; round++) {
            String target = toSuspended ? "SUSPENDED" : "ACTIVE";
            List<Callable<Outcome>> calls = new ArrayList<>();
            for (int i = 0; i < WRITERS; i++) {
                calls.add(() -> transition(partner, target));
            }
            List<Outcome> outcomes = ApiTestKit.race(calls, LIMIT);

            assertThat(outcomes).as("round %d statuses", round)
                    .extracting(Outcome::status).doesNotContain(500);
            // Not "exactly one": asking for the state a partner is already in is a
            // deliberate no-op that succeeds, so a writer arriving after the winner
            // committed is a second 204. What must hold is that somebody won and
            // that nobody got anything but a success or a conflict.
            assertThat(outcomes).filteredOn(o -> o.status() == 204).isNotEmpty();
            assertThat(outcomes).filteredOn(o -> o.status() != 204)
                    .allSatisfy(o -> {
                        assertThat(o.status()).isEqualTo(409);
                        assertThat(o.code()).isIn("concurrent-modification",
                                "partner-illegal-transition");
                        assertNoInternals(o);
                    });
            toSuspended = !toSuspended;
        }
    }

    /** A conflict is for the caller to act on; it must not describe the schema. */
    private static void assertNoInternals(Outcome conflict) {
        assertThat(conflict.body()).doesNotContain("INSERT", "UPDATE", "constraint",
                "business_partner", "SQL", "org.springframework");
    }

    private Outcome transition(UUID partner, String target) {
        return kit.post(tenant, "/api/v1/partners/" + partner + "/transitions",
                "{\"target\":\"" + target + "\"}", "PARTNER_UPDATE");
    }

    private UUID createPartner(String code) {
        Outcome created = kit.post(tenant, "/api/v1/partners", partnerBody(code), "PARTNER_CREATE");
        assertThat(created.status()).isEqualTo(201);
        return UUID.fromString(kit.parse(created).get("id").asString());
    }

    private String partnerBody(String code) {
        return """
                {"orgUnitId":"%s","code":"%s","legalName":"%s Ltd",
                 "partnerType":"CUSTOMER","taxId":"TAX-%s"}
                """.formatted(tenant.orgUnitId(), code, code, code);
    }
}
