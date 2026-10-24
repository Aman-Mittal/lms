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
package com.lms.performance;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import com.lms.acceptance.TestFixtures;
import com.lms.identity.security.TokenService;
import com.lms.web.ApiTestKit;
import com.lms.web.ApiTestKit.Outcome;
import com.lms.web.ApiTestKit.Tenant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Write contention against a real database: many threads at the same row, or
 * at the same unique key, at the same moment.
 *
 * <p>What is asserted is behaviour under load rather than speed. Every round
 * has exactly one winner when the contested thing is a creation, at least one
 * when it is a state change that is a no-op the second time; every other
 * request is answered 409, never 500; nothing deadlocks or hangs (each round
 * is bounded, and a timeout fails the test). The throughput assertions are
 * relative, because a laptop and a CI runner differ by a factor of several:
 * the losers of a round must not be much slower than the winner -- they wait
 * for it, they do not queue behind each other -- and a contended request must
 * cost about what an uncontended one does, since a conflict is the cheaper
 * outcome, not the dearer one.
 *
 * <p>Tagged {@code performance}: excluded from the default build, run with
 * {@code ./mvnw verify -Pperformance}. The conflict mapping itself is covered
 * in the default build by {@code ConflictHandlingApiTest}; this is the same
 * property at higher concurrency and over more rounds.
 */
@Tag("performance")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WriteContentionPerformanceTest {

    private static final Logger log = LoggerFactory.getLogger(WriteContentionPerformanceTest.class);

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

    private static final int THREADS = 20;
    private static final int ROUNDS = 15;
    private static final Duration ROUND_LIMIT = Duration.ofSeconds(60);

    /** Losers may take this many times the winner's latency (median to median). */
    private static final double LOSER_TO_WINNER = 25.0;
    /** A contended request may cost this many times an uncontended one. */
    private static final double CONTENDED_TO_UNCONTENDED = 10.0;

    @LocalServerPort
    private int port;
    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private TokenService tokens;

    private ApiTestKit kit;
    private Tenant tenant;

    @BeforeAll
    void aTenantAndAWarmServer() {
        kit = new ApiTestKit(fixtures, tokens, port);
        tenant = kit.newTenant("contend");
        // Warm the JIT, the pool and the plan cache so round one is not the outlier.
        for (int i = 0; i < 10; i++) {
            create("WARM-" + i + "-" + salt());
        }
    }

    @Test
    @DisplayName("20 threads create the same partner code, 15 times: one 201 per round, all others 409, no 500, no hang")
    void sameUniqueKey() {
        long uncontendedNanos = uncontendedCreateNanos();
        List<Double> ratios = new ArrayList<>();
        List<Long> roundNanos = new ArrayList<>();
        int conflictsFromTheIndex = 0;

        for (int round = 0; round < ROUNDS; round++) {
            String code = "CONT-" + round + "-" + salt();
            List<Callable<Outcome>> calls = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                calls.add(() -> create(code));
            }
            long started = System.nanoTime();
            List<Outcome> outcomes = ApiTestKit.race(calls, ROUND_LIMIT);
            roundNanos.add(System.nanoTime() - started);

            assertThat(outcomes).as("round %d", round).extracting(Outcome::status)
                    .doesNotContain(500);
            List<Outcome> winners = outcomes.stream().filter(o -> o.status() == 201).toList();
            List<Outcome> losers = outcomes.stream().filter(o -> o.status() != 201).toList();
            assertThat(winners).as("round %d winners", round).hasSize(1);
            assertThat(losers).as("round %d losers", round).hasSize(THREADS - 1)
                    .allSatisfy(o -> {
                        assertThat(o.status()).isEqualTo(409);
                        assertThat(o.code()).isIn("partner-code-taken", "duplicate-resource");
                    });
            conflictsFromTheIndex += (int) losers.stream()
                    .filter(o -> "duplicate-resource".equals(o.code())).count();
            ratios.add(median(losers) / (double) Math.max(1, winners.get(0).nanos()));
        }

        double medianRatio = ratios.stream().sorted().toList().get(ratios.size() / 2);
        double contendedPerRequest = roundNanos.stream().mapToLong(Long::longValue).average()
                .orElseThrow() / THREADS;
        log.info("unique-key race: {} threads x {} rounds; {} of {} losers were settled by the "
                        + "unique index (the rest by the existence check); median loser/winner "
                        + "latency {}; contended {} us/request vs uncontended {} us/request",
                THREADS, ROUNDS, conflictsFromTheIndex, ROUNDS * (THREADS - 1),
                String.format("%.2f", medianRatio), (long) contendedPerRequest / 1000,
                uncontendedNanos / 1000);

        assertThat(medianRatio).as("losers wait for the winner, not for each other")
                .isLessThan(LOSER_TO_WINNER);
        assertThat(contendedPerRequest).as("a conflict is not dearer than a success")
                .isLessThan(uncontendedNanos * CONTENDED_TO_UNCONTENDED);
    }

    @Test
    @DisplayName("20 threads change one partner's status, 15 times: someone wins each round, all others 409, no 500, no hang")
    void sameAggregate() {
        UUID partner = createdId(create("AGG-" + salt()));
        assertThat(transition(partner, "PENDING_VERIFICATION").status()).isEqualTo(204);
        assertThat(transition(partner, "ACTIVE").status()).isEqualTo(204);

        List<Double> ratios = new ArrayList<>();
        int staleWrites = 0;
        boolean toSuspended = true;
        for (int round = 0; round < ROUNDS; round++) {
            String target = toSuspended ? "SUSPENDED" : "ACTIVE";
            List<Callable<Outcome>> calls = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                calls.add(() -> transition(partner, target));
            }
            List<Outcome> outcomes = ApiTestKit.race(calls, ROUND_LIMIT);

            assertThat(outcomes).as("round %d", round).extracting(Outcome::status)
                    .doesNotContain(500);
            List<Outcome> winners = outcomes.stream().filter(o -> o.status() == 204).toList();
            List<Outcome> losers = outcomes.stream().filter(o -> o.status() != 204).toList();
            // At least one: asking for the state the partner is already in is a
            // successful no-op, so a writer arriving after the winner is a second 204.
            assertThat(winners).as("round %d winners", round).isNotEmpty();
            assertThat(losers).allSatisfy(o -> {
                assertThat(o.status()).isEqualTo(409);
                assertThat(o.code()).isEqualTo("concurrent-modification");
            });
            staleWrites += losers.size();
            if (!losers.isEmpty()) {
                ratios.add(median(losers) / (double) Math.max(1, median(winners)));
            }
            toSuspended = !toSuspended;
        }

        log.info("same-aggregate race: {} threads x {} rounds; {} stale writes answered 409; "
                        + "median loser/winner latency {}",
                THREADS, ROUNDS, staleWrites,
                ratios.isEmpty() ? "n/a" : String.format("%.2f",
                        ratios.stream().sorted().toList().get(ratios.size() / 2)));

        if (!ratios.isEmpty()) {
            assertThat(ratios.stream().sorted().toList().get(ratios.size() / 2))
                    .as("losers are not slower than the winner by a large factor")
                    .isLessThan(LOSER_TO_WINNER);
        }
    }

    // ------------------------------------------------------------ internals

    private long uncontendedCreateNanos() {
        int n = 30;
        long started = System.nanoTime();
        for (int i = 0; i < n; i++) {
            assertThat(create("SOLO-" + i + "-" + salt()).status()).isEqualTo(201);
        }
        return (System.nanoTime() - started) / n;
    }

    private static long median(List<Outcome> outcomes) {
        List<Long> sorted = outcomes.stream().map(Outcome::nanos)
                .sorted(Comparator.naturalOrder()).toList();
        return sorted.get(sorted.size() / 2);
    }

    private Outcome create(String code) {
        return kit.post(tenant, "/api/v1/partners", """
                {"orgUnitId":"%s","code":"%s","legalName":"%s Ltd",
                 "partnerType":"CUSTOMER","taxId":"TAX-%s"}
                """.formatted(tenant.orgUnitId(), code, code, code), "PARTNER_CREATE");
    }

    private Outcome transition(UUID partner, String target) {
        return kit.post(tenant, "/api/v1/partners/" + partner + "/transitions",
                "{\"target\":\"" + target + "\"}", "PARTNER_UPDATE");
    }

    private UUID createdId(Outcome created) {
        assertThat(created.status()).isEqualTo(201);
        return UUID.fromString(kit.parse(created).get("id").asString());
    }

    private static String salt() {
        return UUID.randomUUID().toString().substring(0, 6);
    }
}
