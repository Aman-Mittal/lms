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
package com.lms.acceptance;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.lms.controltower.command.ExceptionCommandService;
import com.lms.controltower.command.domain.ExceptionCategory;
import com.lms.controltower.query.ControlTowerQueryService;
import com.lms.controltower.query.ExceptionSummaryView;
import com.lms.controltower.query.ExceptionView;
import com.lms.finance.command.SettlementHolds;
import com.lms.finance.query.FinanceQueryService;
import com.lms.finance.query.FreightBillView;
import com.lms.shared.query.Slice;
import com.lms.shared.tenant.TenantContext;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Step definitions for {@code controltower.feature}. */
public class ControlTowerSteps {

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private ExceptionCommandService exceptions;
    @Autowired
    private ControlTowerQueryService controlTower;
    @Autowired
    private SettlementHolds holds;
    @Autowired
    private FinanceQueryService finance;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private RuntimeException failure;

    // ---------------------------------------------------------------- raising

    @When("{string} is reported on trip {string} as ticket {string}")
    public void reported(String category, String tripNo, String ticket) {
        raise(category, tripNo, ticket, null, null);
    }

    @When("{string} is reported on trip {string} as ticket {string} at {double},{double}")
    public void reportedAt(String category, String tripNo, String ticket, double lat, double lon) {
        raise(category, tripNo, ticket, BigDecimal.valueOf(lat), BigDecimal.valueOf(lon));
    }

    @When("{string} is reported on trip {string} with only a latitude")
    public void reportedWithHalfAPosition(String category, String tripNo) {
        raise(category, tripNo, "half", new BigDecimal("19.1"), null);
    }

    // -------------------------------------------------------------- lifecycle

    @When("ticket {string} is assigned to {string}")
    public void assigned(String ticket, String email) {
        UUID userId = userNamed(email);
        attempt(() -> exceptions.assign(ticketId(ticket), userId, "Yours"));
    }

    @When("ticket {string} is taken under investigation")
    public void investigated(String ticket) {
        attempt(() -> exceptions.startInvestigation(ticketId(ticket), null));
    }

    @When("ticket {string} is resolved by {string} because {string}")
    public void resolved(String ticket, String who, String how) {
        signInAs(who);
        attempt(() -> exceptions.resolve(ticketId(ticket), how));
    }

    @When("ticket {string} is closed by {string}")
    public void closedBy(String ticket, String who) {
        signInAs(who);
        attempt(() -> exceptions.close(ticketId(ticket), null));
    }

    @When("ticket {string} is closed")
    public void closed(String ticket) {
        attempt(() -> exceptions.close(ticketId(ticket), null));
    }

    @When("ticket {string} is sent back by {string} because {string}")
    public void sentBack(String ticket, String who, String why) {
        signInAs(who);
        attempt(() -> exceptions.reopen(ticketId(ticket), why));
    }

    // -------------------------------------------------------------- detectors

    /**
     * Replays the detection the way the outbox would after a restart: same
     * trip, same instant, same point.
     */
    @When("the route deviation on trip {string} is delivered again")
    public void deviationRedelivered(String tripNo) {
        ExceptionView ticket = onlyTicketOn(tripNo);
        exceptions.raiseForRouteDeviation(tripId(tripNo), new BigDecimal("150000"),
                new BigDecimal("25000"), ticket.lat(), ticket.lon(), ticket.positionAt());
    }

    // ------------------------------------------------------------- settlement

    @When("finance hears that a hold on trip {string} was released before hearing it was placed")
    public void releaseOvertakesPlacement(String tripNo) {
        UUID exceptionId = UUID.randomUUID();
        UUID tripId = tripId(tripNo);
        Instant placedAt = Instant.now().minus(1, ChronoUnit.HOURS);
        holds.release(exceptionId, tripId, "EX-OVERTAKEN", placedAt, Instant.now());
        holds.place(exceptionId, tripId, "EX-OVERTAKEN", placedAt);
    }

    // ------------------------------------------------------------- assertions

    @Then("ticket {string} is {string} with severity {string}")
    public void ticketIs(String ticket, String status, String severity) {
        ExceptionView view = view(ticket);
        assertThat(view.status()).isEqualTo(status);
        assertThat(view.severity()).isEqualTo(severity);
    }

    @Then("ticket {string} suspends settlement")
    public void suspends(String ticket) {
        assertThat(view(ticket).suspendsSettlement()).isTrue();
    }

    @Then("ticket {string} does not suspend settlement")
    public void doesNotSuspend(String ticket) {
        assertThat(view(ticket).suspendsSettlement()).isFalse();
    }

    @Then("ticket {string} was located at {double},{double} from {string}")
    public void locatedAt(String ticket, double lat, double lon, String source) {
        assertLocated(view(ticket), lat, lon, source);
    }

    @Then("the position on ticket {string} is about {int} hours old")
    public void positionAge(String ticket, int hours) {
        Duration age = Duration.between(view(ticket).positionAt(), Instant.now());
        assertThat(age.toMinutes()).isCloseTo(hours * 60L, within(5L));
    }

    @Then("ticket {string} has no position")
    public void noPosition(String ticket) {
        ExceptionView view = view(ticket);
        assertThat(view.positionSource()).isEqualTo("NONE");
        assertThat(view.lat()).isNull();
        assertThat(view.lon()).isNull();
    }

    @Then("ticket {string} was closed by {string}")
    public void closedByWho(String ticket, String who) {
        assertThat(view(ticket).closedBy()).isEqualTo(who);
    }

    @Then("ticket {string} has {int} history entries")
    public void historyEntries(String ticket, int count) {
        assertThat(inTransaction(() -> controlTower.historyOf(ticketId(ticket)))).hasSize(count);
    }

    @Then("trip {string} has {int} exception ticket")
    public void ticketsOnTrip(String tripNo, int count) {
        assertThat(ticketsOn(tripNo)).hasSize(count);
    }

    @Then("the ticket on trip {string} is a {string} of severity {string}")
    public void ticketOnTripIs(String tripNo, String category, String severity) {
        ExceptionView ticket = onlyTicketOn(tripNo);
        assertThat(ticket.category()).isEqualTo(category);
        assertThat(ticket.severity()).isEqualTo(severity);
        assertThat(ticket.raisedBy()).isEqualTo("system");
    }

    @Then("the ticket on trip {string} was located at {double},{double} from {string}")
    public void ticketOnTripLocated(String tripNo, double lat, double lon, String source) {
        assertLocated(onlyTicketOn(tripNo), lat, lon, source);
    }

    @Then("the control tower refuses mentioning {string}")
    public void refusedMentioning(String fragment) {
        assertThat(failure).as("the control tower should have refused").isNotNull();
        assertThat(failure.getMessage()).contains(fragment);
    }

    @Then("the control tower refuses it as unauthorised")
    public void refusedAsUnauthorised() {
        assertThat(failure).isInstanceOf(AccessDeniedException.class);
    }

    @Then("bill {string} is held by ticket {string}")
    public void heldBy(String billNo, String ticket) {
        assertThat(bill(billNo).settlementHeldBy()).isEqualTo(view(ticket).ticketNo());
    }

    @Then("bill {string} is not held")
    public void notHeld(String billNo) {
        assertThat(bill(billNo).settlementHeldBy()).isNull();
    }

    @Then("trip {string} has no settlement hold")
    public void noHold(String tripNo) {
        assertThat(inTransaction(() -> holds.holdingTickets(tripId(tripNo))))
                .isEmpty();
    }

    @Then("the board shows {int} P1, {int} P2 and {int} P3 open, {int} unassigned "
            + "and {int} holding settlement")
    public void board(int p1, int p2, int p3, int unassigned, int holding) {
        ExceptionSummaryView summary = inTransaction(controlTower::summary);
        assertThat(summary.openP1()).isEqualTo(p1);
        assertThat(summary.openP2()).isEqualTo(p2);
        assertThat(summary.openP3()).isEqualTo(p3);
        assertThat(summary.unassigned()).isEqualTo(unassigned);
        assertThat(summary.settlementHolds()).isEqualTo(holding);
    }

    /**
     * Pages one at a time, so the cursor is exercised rather than trusted: a
     * cursor that repeated or skipped a row would show up as a wrong count or
     * a duplicate here.
     */
    @Then("the board lists {int} tickets, newest first")
    public void boardLists(int count) {
        List<ExceptionView> seen = new java.util.ArrayList<>();
        String cursor = null;
        do {
            String after = cursor;
            Slice<ExceptionView> page = inTransaction(() -> controlTower.list(null, null, after, 1));
            seen.addAll(page.items());
            cursor = page.nextCursor();
        } while (cursor != null);

        assertThat(seen).hasSize(count);
        assertThat(seen.stream().map(ExceptionView::id).collect(Collectors.toSet())).hasSize(count);
        for (int i = 1; i < seen.size(); i++) {
            assertThat(seen.get(i - 1).raisedAt()).isAfterOrEqualTo(seen.get(i).raisedAt());
        }
    }

    // --------------------------------------------------------------- helpers

    private void raise(String category, String tripNo, String ticket, BigDecimal lat,
                       BigDecimal lon) {
        failure = null;
        try {
            world.putRef("ticket:" + ticket, exceptions.raise(tripId(tripNo),
                    ExceptionCategory.valueOf(category), category + " on " + tripNo, lat, lon));
        } catch (RuntimeException e) {
            failure = e;
        }
    }

    private void attempt(Runnable action) {
        failure = null;
        try {
            action.run();
        } catch (RuntimeException e) {
            failure = e;
        }
    }

    /**
     * Signs in under a particular name with every permission.
     *
     * <p>The name is the point. Closing a critical ticket is refused to whoever
     * resolved it, and that rule can only be exercised by scenarios in which
     * two different people act.
     */
    private static void signInAs(String who) {
        Set<SimpleGrantedAuthority> authorities = AuthorityHooks.ALL_PERMISSIONS.stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toSet());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(who, "n/a", authorities));
    }

    private UUID userNamed(String email) {
        String key = "user:" + email;
        try {
            return world.ref(key);
        } catch (RuntimeException unknown) {
            UUID id = fixtures.createUser(TenantContext.requireTenantId(), world.orgUnitId(),
                    TenantContext.requireOrgPath(), world.uniqueCode(email), "not-used-here");
            world.putRef(key, id);
            return id;
        }
    }

    private static void assertLocated(ExceptionView view, double lat, double lon, String source) {
        assertThat(view.positionSource()).isEqualTo(source);
        assertThat(view.lat().doubleValue()).isCloseTo(lat, within(0.00001));
        assertThat(view.lon().doubleValue()).isCloseTo(lon, within(0.00001));
    }

    /**
     * The trip, whichever step family raised it.
     *
     * <p>The execution and telematics steps register trips by name; the
     * finance steps keep theirs private. Falling back to the scenario-unique
     * trip number lets a ticket be raised against either without one family
     * having to know about the other.
     */
    private UUID tripId(String tripNo) {
        try {
            return world.ref("trip:" + tripNo);
        } catch (IllegalStateException unnamed) {
            return inTransaction(() -> jdbc.sql("SELECT id FROM trip WHERE trip_no = :tripNo")
                    .param("tripNo", world.uniqueCode(tripNo))
                    .query(UUID.class)
                    .single());
        }
    }

    private UUID ticketId(String ticket) {
        return world.ref("ticket:" + ticket);
    }

    private ExceptionView view(String ticket) {
        return inTransaction(() -> controlTower.findById(ticketId(ticket))
                .orElseThrow(() -> new AssertionError("No ticket " + ticket)));
    }

    private List<ExceptionView> ticketsOn(String tripNo) {
        UUID tripId = tripId(tripNo);
        return inTransaction(() -> controlTower.list(null, null, null, 200).items().stream()
                .filter(ticket -> tripId.equals(ticket.tripId()))
                .toList());
    }

    private ExceptionView onlyTicketOn(String tripNo) {
        List<ExceptionView> tickets = ticketsOn(tripNo);
        assertThat(tickets).as("tickets on trip %s", tripNo).hasSize(1);
        return tickets.get(0);
    }

    private FreightBillView bill(String billNo) {
        return inTransaction(() -> finance.findById(world.ref("bill:" + billNo))
                .orElseThrow(() -> new AssertionError("No bill " + billNo)));
    }

    private <T> T inTransaction(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }
}
