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
package com.lms.masterdata;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.lms.acceptance.SpineFixtures;
import com.lms.acceptance.TestFixtures;
import com.lms.execution.command.TripCommandService;
import com.lms.masterdata.command.FleetCommandService;
import com.lms.masterdata.command.RegisterTerminalCommand;
import com.lms.masterdata.command.TerminalCommandService;
import com.lms.masterdata.command.TerminalReferenceCheck;
import com.lms.masterdata.command.domain.Terminal;
import com.lms.performance.TripScaffold;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.geo.LatLon;
import com.lms.shared.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The database is what actually stops a terminal being deleted from under its
 * trips; the service's check is the friendly front for it.
 *
 * <p>Here the front is switched off -- {@link TerminalReferenceCheck} is
 * replaced by one that finds nothing -- so that what is under test is the
 * backstop: a real RESTRICT foreign key in real Postgres, refusing the row,
 * and the service turning that refusal into the same 409 {@code terminal-in-use}
 * instead of a 500 (the case of a reference created between the check and the
 * delete).
 */
@SpringBootTest
class TerminalDeleteBackstopIntegrationTest {

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

    /** Finds nothing, whatever refers to the terminal: the service's check is bypassed. */
    @MockitoBean
    private TerminalReferenceCheck blindCheck;

    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private SpineFixtures spine;
    @Autowired
    private FleetCommandService fleet;
    @Autowired
    private TerminalCommandService terminals;
    @Autowired
    private TripCommandService trips;
    @Autowired
    private JdbcClient jdbc;

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a terminal a trip starts from is refused by the database's own foreign key")
    void theForeignKeyRefusesTheDelete() {
        TripScaffold.Built built = TripScaffold.build(fixtures, spine, fleet, terminals, trips, 5.0);
        UUID origin = originOf(built);

        assertThatThrownBy(() -> jdbc.sql("DELETE FROM terminal WHERE id = :id")
                .param("id", origin).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23503"));
        assertThat(exists(origin)).isTrue();
    }

    @Test
    @DisplayName("with the service's check bypassed, the foreign key still turns the delete into 409 terminal-in-use")
    void theServiceAnswersTheBackstopWith409() {
        TripScaffold.Built built = TripScaffold.build(fixtures, spine, fleet, terminals, trips, 6.0);
        UUID origin = originOf(built);

        assertThatThrownBy(() -> asAdministrator(built, () -> {
            terminals.delete(origin);
            return null;
        }))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo("terminal-in-use"));

        assertThat(exists(origin)).as("the terminal survived").isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'TERMINAL_DELETED' "
                + "AND resource_id = :id").param("id", origin).query(Long.class).single())
                .as("a refused delete is not recorded as a deletion").isZero();
    }

    @Test
    @DisplayName("a terminal nothing refers to is deleted whether or not the check is there")
    void anUnreferencedTerminalGoes() {
        TripScaffold.Built built = TripScaffold.build(fixtures, spine, fleet, terminals, trips, 7.0);

        UUID spare = asAdministrator(built, () -> terminals.register(
                new RegisterTerminalCommand.PointRadius(built.orgUnitId(), "SPARE-" + built.tripId(),
                        "Spare", Terminal.FunctionalCategory.CUSTOMER_SITE, new LatLon(76.2, 10.0),
                        300, 1, null, null, 30, List.of())));
        asAdministrator(built, () -> {
            terminals.delete(spare);
            return null;
        });

        assertThat(exists(spare)).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'TERMINAL_DELETED' "
                + "AND resource_id = :id").param("id", spare).query(Long.class).single()).isOne();
    }

    // ---------------------------------------------------------------- helpers

    private UUID originOf(TripScaffold.Built built) {
        return jdbc.sql("SELECT origin_terminal_id FROM trip WHERE id = :id")
                .param("id", built.tripId()).query(UUID.class).single();
    }

    private boolean exists(UUID terminalId) {
        return jdbc.sql("SELECT count(*) FROM terminal WHERE id = :id")
                .param("id", terminalId).query(Long.class).single() == 1;
    }

    private static String sqlState(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private <T> T asAdministrator(TripScaffold.Built built, Supplier<T> action) {
        Set<SimpleGrantedAuthority> authorities = List.of("TERMINAL_CREATE", "TERMINAL_UPDATE",
                        "TERMINAL_DELETE").stream()
                .map(SimpleGrantedAuthority::new).collect(Collectors.toSet());
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("backstop", null, authorities));
        return TenantContext.callWith(built.tenantId(), built.orgPath(), action);
    }
}
