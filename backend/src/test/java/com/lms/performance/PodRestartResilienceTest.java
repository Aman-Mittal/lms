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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.lms.LmsApplication;
import com.lms.acceptance.SpineFixtures;
import com.lms.acceptance.TestFixtures;
import com.lms.controltower.command.ExceptionCommandService;
import com.lms.controltower.command.domain.ExceptionCategory;
import com.lms.execution.command.TripCommandService;
import com.lms.masterdata.command.FleetCommandService;
import com.lms.masterdata.command.TerminalCommandService;
import com.lms.shared.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What survives the pod going away.
 *
 * <p>On Render's free tier the process is stopped after fifteen idle minutes
 * and started again by the next request, so a restart is not an incident here
 * -- it is the normal day. Two guarantees are only meaningful if they hold
 * across one, and neither can be tested inside a single application context:
 *
 * <ul>
 *   <li><b>The outbox finishes what a dead process started.</b> A ticket
 *       committed just before the pod died, whose settlement hold had not yet
 *       been placed, must get its hold when the next pod starts -- otherwise a
 *       stolen load is paid for because of when a container happened to stop.
 *   <li><b>An idempotency key outlives the process that saw it.</b> A client
 *       whose request was cut off by the restart retries against the new pod
 *       and must get the original answer, not a second record.
 * </ul>
 *
 * <p>Each test stops one real application and starts another against the same
 * database, exactly as a redeploy or a wake from sleep does.
 */
@Tag("performance")
class PodRestartResilienceTest {

    private static final Logger log = LoggerFactory.getLogger(PodRestartResilienceTest.class);
    private static final String DEMO_PASSWORD = "restart test demo password";

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.7-alpine")
            .withDatabaseName("lms").withUsername("lms").withPassword("lms");

    static {
        POSTGRES.start();
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();

    private ConfigurableApplicationContext pod;

    @AfterEach
    void stopPod() {
        if (pod != null) {
            pod.close();
        }
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a settlement hold the dead pod never placed is placed by the next one")
    void outboxCompletesAfterRestart() throws InterruptedException {
        pod = start("first");
        TripScaffold.Built tenant = TripScaffold.build(pod.getBean(TestFixtures.class),
                pod.getBean(SpineFixtures.class), pod.getBean(FleetCommandService.class),
                pod.getBean(TerminalCommandService.class), pod.getBean(TripCommandService.class),
                UUID.randomUUID().getLeastSignificantBits() % 50 / 10.0);

        UUID ticketId = as(tenant, List.of("EXCEPTION_RAISE"), () ->
                pod.getBean(ExceptionCommandService.class).raise(tenant.tripId(),
                        ExceptionCategory.THEFT, "Seal broken", null, null));
        JdbcClient db = pod.getBean(JdbcClient.class);
        assertThat(openHolds(db, ticketId)).as("the hold is placed while the pod is alive").isEqualTo(1);

        // The crash: the ticket is committed, but the listener that places the
        // hold never ran to completion. Undo its effect and mark its outbox
        // entry unfinished -- the state a SIGKILL between commit and listener
        // leaves behind.
        db.sql("DELETE FROM settlement_hold WHERE exception_id = :id").param("id", ticketId).update();
        int reopened = db.sql("""
                        UPDATE event_publication
                           SET completion_date = NULL, status = 'PUBLISHED'
                         WHERE listener_id LIKE '%SettlementHoldListener%'
                           AND serialized_event LIKE '%' || :id || '%'
                        """)
                .param("id", ticketId.toString())
                .update();
        assertThat(reopened).as("the hold's outbox entry was found").isEqualTo(1);
        pod.close();

        long started = System.nanoTime();
        pod = start("second");
        log.info("Second pod ready in {} ms", (System.nanoTime() - started) / 1_000_000);
        JdbcClient after = pod.getBean(JdbcClient.class);

        long deadline = System.currentTimeMillis() + 30_000;
        while (openHolds(after, ticketId) == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(250);
        }
        assertThat(openHolds(after, ticketId))
                .as("the restarted pod republishes the unfinished event and places the hold")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a request retried against a new pod gets the original answer, not a second record")
    void idempotencyKeySurvivesRestart() throws IOException, InterruptedException {
        pod = start("first");
        String salt = UUID.randomUUID().toString().substring(0, 8);
        String key = "restart-" + salt;

        JsonNode session = signIn();
        String body = json.writeValueAsString(Map.of(
                "orgUnitId", session.get("orgUnitId").asString(),
                "code", "RESTART-" + salt,
                "legalName", "Restart " + salt + " Pvt Ltd",
                "partnerType", "CUSTOMER",
                "taxId", "TAX-" + salt));

        HttpResponse<String> first = post("/api/v1/partners", body, key, session);
        assertThat(first.statusCode()).isEqualTo(201);
        pod.close();

        pod = start("second");
        // A new process has new ephemeral signing keys, so the old token is
        // rightly worthless; the client signs in again and retries.
        JsonNode again = signIn();
        HttpResponse<String> retry = post("/api/v1/partners", body, key, again);

        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(retry.headers().firstValue("Idempotent-Replay")).hasValue("true");
        assertThat(json.readTree(retry.body()).get("id").asString())
                .isEqualTo(json.readTree(first.body()).get("id").asString());

        Integer partners = pod.getBean(JdbcClient.class)
                .sql("SELECT count(*) FROM business_partner WHERE code = :code")
                .param("code", "RESTART-" + salt).query(Integer.class).single();
        assertThat(partners).as("one partner, however many times it was asked for").isEqualTo(1);
    }

    // ------------------------------------------------------------- helpers

    private ConfigurableApplicationContext start(String name) {
        log.info("Starting the {} pod", name);
        // Command-line arguments, not builder properties: those are only
        // defaults, and application.yaml's own datasource default would win
        // over them -- a pod pointed at localhost instead of the container.
        return new SpringApplicationBuilder(LmsApplication.class).run(
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--server.port=0",
                "--lms.demo.enabled=true",
                "--lms.demo.admin-password=" + DEMO_PASSWORD);
    }

    private int port() {
        return Integer.parseInt(pod.getEnvironment().getRequiredProperty("local.server.port"));
    }

    private JsonNode signIn() throws IOException, InterruptedException {
        String login = json.writeValueAsString(Map.of(
                "tenantCode", "demo", "email", "admin@demo.local", "password", DEMO_PASSWORD));
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port() + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(login)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private HttpResponse<String> post(String path, String body, String key, JsonNode session)
            throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port() + path))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + session.get("accessToken").asString())
                        .header("Idempotency-Key", key)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Read as the container's superuser: the check must not depend on a tenant scope. */
    private static int openHolds(JdbcClient db, UUID ticketId) {
        return db.sql("""
                        SELECT count(*) FROM settlement_hold
                         WHERE exception_id = :id AND released_at IS NULL
                        """)
                .param("id", ticketId).query(Integer.class).single();
    }

    private static <T> T as(TripScaffold.Built tenant, List<String> permissions,
                            java.util.function.Supplier<T> action) {
        Set<SimpleGrantedAuthority> authorities = permissions.stream()
                .map(SimpleGrantedAuthority::new).collect(Collectors.toSet());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("restart", null, authorities));
        try {
            return TenantContext.callWith(tenant.tenantId(), tenant.orgPath(), action);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
