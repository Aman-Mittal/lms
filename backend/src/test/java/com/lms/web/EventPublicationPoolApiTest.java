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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.lms.acceptance.TestFixtures;
import com.lms.identity.security.TokenService;
import com.lms.masterdata.command.FleetCommandService;
import com.lms.masterdata.command.RegisterTerminalCommand;
import com.lms.masterdata.command.TerminalCommandService;
import com.lms.masterdata.command.domain.BusinessPartner;
import com.lms.masterdata.command.domain.Terminal;
import com.lms.order.events.OrderValidated;
import com.lms.shared.geo.LatLon;
import com.lms.shared.tenant.TenantContext;
import com.lms.web.ApiTestKit.Outcome;
import com.lms.web.ApiTestKit.Tenant;
import com.lms.webhooks.command.WebhookEventListener;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A request that publishes an event to a registry-tracked listener needs one
 * pooled connection at a time, never two.
 *
 * <p>The publication registry marks a publication processing and completed in
 * transactions of its own, around the after-commit listener. Run while the
 * request still held its committed connection, every such request needed a
 * second one at commit, so as many requests as the pool has connections,
 * committing together, each held one and waited for another until the pool
 * timeout. Validating an order publishes {@link OrderValidated}, which
 * {@link WebhookEventListener} tracks, so it is the most direct way in.
 *
 * <p>The pool is the free tier's five connections and twice as many requests
 * are released at once. Each must succeed, well inside the pool's ten-second
 * timeout, and every publication must be completed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventPublicationPoolApiTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.7-alpine")
            .withDatabaseName("lms").withUsername("lms").withPassword("lms");

    static {
        POSTGRES.start();
    }

    private static final int POOL = 5;
    private static final int REQUESTS = POOL * 2;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("DB_POOL_MAX", () -> String.valueOf(POOL));
        // Nothing in the background competing for the pool while the race runs.
        registry.add("lms.events.retry.enabled", () -> "false");
    }

    private static final String[] PERMISSIONS = {"ORDER_CREATE", "ORDER_READ", "ORDER_UPDATE"};
    private static final List<String> SETUP = List.of("PARTNER_CREATE", "PARTNER_UPDATE", "TERMINAL_CREATE");

    @LocalServerPort
    private int port;
    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private TokenService tokens;
    @Autowired
    private FleetCommandService fleet;
    @Autowired
    private TerminalCommandService terminals;
    @Autowired
    private JdbcClient jdbc;

    private ApiTestKit kit;
    private Tenant tenant;
    private UUID customer;
    private UUID consignee;
    private UUID origin;
    private UUID destination;

    @BeforeAll
    void aNetwork() {
        kit = new ApiTestKit(fixtures, tokens, port);
        tenant = kit.newTenant("pool");
        asSetup(() -> {
            String salt = UUID.randomUUID().toString().substring(0, 8);
            customer = partner("CUST-" + salt);
            consignee = partner("CONS-" + salt);
            origin = depot("ORG-" + salt, 19.10, 72.90);
            destination = depot("DST-" + salt, 28.40, 77.00);
            return null;
        });
    }

    @Test
    @DisplayName("as many concurrent event-publishing requests as twice the pool all succeed promptly")
    void concurrentValidationsShareAFivePool() {
        List<UUID> orders = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            orders.add(draftWithOneLine(i));
        }

        List<Callable<Outcome>> calls = new ArrayList<>();
        for (UUID order : orders) {
            calls.add(() -> kit.post(tenant, "/api/v1/orders/" + order + "/validate", "{}", PERMISSIONS));
        }
        List<Outcome> outcomes = ApiTestKit.race(calls, Duration.ofSeconds(60));

        for (Outcome outcome : outcomes) {
            assertThat(outcome.status()).as(outcome.body()).isEqualTo(204);
            assertThat(Duration.ofNanos(outcome.nanos()))
                    .as("well inside the pool's ten-second timeout")
                    .isLessThan(Duration.ofSeconds(5));
        }
        for (UUID order : orders) {
            Integer open = jdbc.sql("""
                            SELECT count(*) FROM event_publication
                             WHERE event_type = :type AND completion_date IS NULL
                               AND serialized_event LIKE :order
                            """)
                    .param("type", OrderValidated.class.getName())
                    .param("order", "%" + order + "%")
                    .query(Integer.class).single();
            Integer recorded = jdbc.sql("""
                            SELECT count(*) FROM event_publication
                             WHERE event_type = :type AND serialized_event LIKE :order
                            """)
                    .param("type", OrderValidated.class.getName())
                    .param("order", "%" + order + "%")
                    .query(Integer.class).single();
            assertThat(recorded).as("the publication was recorded for order %s", order).isPositive();
            assertThat(open).as("the publication for order %s was completed", order).isZero();
        }
    }

    private UUID draftWithOneLine(int index) {
        Outcome raised = kit.post(tenant, "/api/v1/orders", """
                {"orgUnitId":"%s","customerPartnerId":"%s","orderNo":"POOL-%d-%s","originTerminalId":"%s"}
                """.formatted(tenant.orgUnitId(), customer, index, UUID.randomUUID().toString().substring(0, 8),
                origin), PERMISSIONS);
        assertThat(raised.status()).as(raised.body()).isEqualTo(201);
        UUID id = UUID.fromString(kit.parse(raised).get("id").asString());
        Outcome line = kit.post(tenant, "/api/v1/orders/" + id + "/lines", """
                {"lineNo":1,"materialCode":"MAT-1","materialDescription":"General cargo",
                 "materialClass":"GENERAL","quantity":1,"uom":"EA","deadWeightKg":1000,
                 "consigneePartnerId":"%s","destinationTerminalId":"%s"}
                """.formatted(consignee, destination), PERMISSIONS);
        assertThat(line.status()).as(line.body()).isEqualTo(201);
        return id;
    }

    private UUID partner(String code) {
        return fleet.registerPartner(tenant.orgUnitId(), code, code, BusinessPartner.PartnerType.CUSTOMER,
                "TAX-" + code);
    }

    private UUID depot(String code, double lat, double lon) {
        return terminals.register(new RegisterTerminalCommand.PointRadius(tenant.orgUnitId(), code, code,
                Terminal.FunctionalCategory.CUSTOMER_SITE, new LatLon(lat, lon), 500, 2, null,
                null, 45, List.of()));
    }

    private <T> T asSetup(Supplier<T> action) {
        Set<SimpleGrantedAuthority> authorities = SETUP.stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toSet());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("setup", "n/a", authorities));
        try {
            return TenantContext.callWith(tenant.tenantId(), tenant.orgPath(), action);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
