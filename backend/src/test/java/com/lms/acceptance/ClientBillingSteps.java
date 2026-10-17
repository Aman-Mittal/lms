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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import com.lms.clientbilling.command.ClientBillingRunService;
import com.lms.clientbilling.command.ClientChargeService;
import com.lms.clientbilling.command.ClientContractService;
import com.lms.clientbilling.command.domain.ClientContract;
import com.lms.clientbilling.command.domain.ServiceType;
import com.lms.clientbilling.query.ClientBillingQueryService;
import com.lms.clientbilling.query.ClientChargeView;
import com.lms.clientbilling.query.ClientInvoiceLineView;
import com.lms.clientbilling.query.ClientInvoiceView;
import com.lms.clientbilling.query.ClientMarginView;
import com.lms.shared.query.Slice;
import com.lms.shared.tenant.TenantContext;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
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

/** Step definitions for {@code clientbilling.feature}. */
public class ClientBillingSteps {

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private ClientContractService contracts;
    @Autowired
    private ClientChargeService charges;
    @Autowired
    private ClientBillingRunService runs;
    @Autowired
    private ClientBillingQueryService queries;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private RuntimeException failure;
    private int usageCounter;
    private Slice<ClientInvoiceView> listed;
    /** The invoice each client's first run answered with, to compare reruns against. */
    private final Map<String, UUID> firstInvoiceIds = new HashMap<>();

    @Before
    public void resetClientBillingState() {
        failure = null;
        usageCounter = 0;
        listed = null;
    }

    // -------------------------------------------------------------- contracts

    /**
     * Rows are the five fixed rates by name, plus any number of
     * {@code service <CODE> per <UNIT>} rows for value-added services.
     */
    @Given("client {string} holds contract {string} effective {int} days ago:")
    public void clientHoldsContract(String clientCode, String contractCode, int daysAgo,
                                    DataTable terms) {
        Map<String, String> row = terms.asMap();
        List<ClientContractService.ServiceRateTerm> services = new ArrayList<>();
        row.forEach((key, value) -> {
            String[] words = key.trim().split("\\s+");
            if (words.length == 4 && words[0].equals("service") && words[2].equals("per")) {
                services.add(new ClientContractService.ServiceRateTerm(words[1], words[3],
                        new BigDecimal(value)));
            }
        });
        failure = null;
        try {
            UUID id = contracts.publishContract(world.orgUnitId(), world.uniqueCode(contractCode),
                    world.ref(clientCode), "INR",
                    LocalDate.now(ZoneOffset.UTC).minusDays(daysAgo),
                    new ClientContract.Rates(
                            decimal(row, "transport per kg"),
                            decimal(row, "transport minimum"),
                            decimal(row, "storage per pallet-day"),
                            decimal(row, "handling in per pallet"),
                            decimal(row, "handling out per pallet")),
                    services);
            world.putRef("client-contract:" + contractCode, id);
        } catch (RuntimeException e) {
            failure = e;
        }
    }

    @When("the storage rate on client contract {string} is edited directly")
    public void editContractDirectly(String contractCode) {
        failure = null;
        try {
            inTransaction(() -> jdbc.sql("""
                            UPDATE client_contract SET storage_rate_per_pallet_day = 1
                             WHERE id = :id
                            """)
                    .param("id", world.ref("client-contract:" + contractCode))
                    .update());
        } catch (RuntimeException e) {
            failure = e;
        }
    }

    // ---------------------------------------------------------------- usage

    @Given("{string} stored {int} pallet-days on day {int} of last month")
    public void storedPalletDays(String clientCode, int quantity, int day) {
        record(clientCode, ServiceType.STORAGE, null, quantity, dayOfLastMonth(day));
    }

    @Given("{string} received {int} pallets into the warehouse on day {int} of last month")
    public void receivedPallets(String clientCode, int quantity, int day) {
        record(clientCode, ServiceType.HANDLING_IN, null, quantity, dayOfLastMonth(day));
    }

    @Given("{string} shipped {int} pallets out of the warehouse on day {int} of last month")
    public void shippedPallets(String clientCode, int quantity, int day) {
        record(clientCode, ServiceType.HANDLING_OUT, null, quantity, dayOfLastMonth(day));
    }

    @Given("{string} had {int} units of value-added service {string} on day {int} of last month")
    public void valueAdded(String clientCode, int quantity, String serviceCode, int day) {
        record(clientCode, ServiceType.VALUE_ADDED, serviceCode, quantity, dayOfLastMonth(day));
    }

    @When("the warehouse resends usage {string} for {string}")
    public void resendUsage(String sourceKey, String clientCode) {
        recordWithKey(clientCode, ServiceType.STORAGE, null, 10, dayOfLastMonth(3), sourceKey);
    }

    @Given("the warehouse reports usage {string} of {int} pallet-days for {string} on day {int} of last month")
    public void reportUsage(String sourceKey, int quantity, String clientCode, int day) {
        recordWithKey(clientCode, ServiceType.STORAGE, null, quantity, dayOfLastMonth(day),
                sourceKey);
    }

    // -------------------------------------------------------------- the run

    @When("last month is billed for {string}")
    @When("last month is billed for {string} again")
    public void billLastMonth(String clientCode) {
        bill(clientCode, dayOfLastMonth(1));
    }

    @When("this month is billed for {string}")
    public void billThisMonth(String clientCode) {
        bill(clientCode, LocalDate.now(ZoneOffset.UTC));
    }

    @When("the month {int} days ago is billed for {string}")
    public void billMonthDaysAgo(int daysAgo, String clientCode) {
        bill(clientCode, LocalDate.now(ZoneOffset.UTC).minusDays(daysAgo));
    }

    // ------------------------------------------------------------ transport

    @When("the completion of trip {string} is delivered to client billing again")
    public void redeliverCompletion(String tripNo) {
        Map<String, Object> trip = tripRow(tripNo);
        charges.captureTransport((UUID) trip.get("id"), (String) trip.get("trip_no"),
                (UUID) trip.get("load_id"), Instant.now(), Instant.now());
    }

    @Then("{string} was charged transport on {int} kg for trip {string}")
    public void chargedTransport(String clientCode, int kg, String tripNo) {
        List<ClientChargeView> transport = transportChargesFor(clientCode, tripNo);
        assertThat(transport).as("transport charges for %s on %s", clientCode, tripNo).hasSize(1);
        assertThat(transport.get(0).quantity()).isEqualByComparingTo(BigDecimal.valueOf(kg));
    }

    @Then("{string} has {int} transport charge for trip {string}")
    public void transportChargeCount(String clientCode, int expected, String tripNo) {
        assertThat(transportChargesFor(clientCode, tripNo)).hasSize(expected);
    }

    @Then("the margin on {string} for the month {int} days ago is {int} on a receivable of {int} and a carrier payable of {int}")
    public void marginIs(String clientCode, int daysAgo, int margin, int receivable,
                         int payable) {
        UUID client = world.ref(clientCode);
        List<ClientMarginView> margins = inTransaction(() -> queries.margins(
                LocalDate.now(ZoneOffset.UTC).minusDays(daysAgo), null, null).items());
        ClientMarginView view = margins.stream()
                .filter(m -> m.clientPartnerId().equals(client))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No margin row for " + clientCode));
        assertThat(view.receivable()).isEqualByComparingTo(BigDecimal.valueOf(receivable));
        assertThat(view.carrierPayable()).isEqualByComparingTo(BigDecimal.valueOf(payable));
        assertThat(view.margin()).isEqualByComparingTo(BigDecimal.valueOf(margin));
        assertThat(view.unpricedMovements()).isZero();
    }

    // ------------------------------------------------------------ isolation

    /**
     * A real user, bound to one client and signed in with read access only --
     * which is all a client portal role would carry.
     */
    @Given("the signed-in user is a portal user of client {string}")
    public void portalUserOf(String clientCode) {
        UUID userId = fixtures.createUser(TenantContext.requireTenantId(), world.orgUnitId(),
                TenantContext.requireOrgPath(), world.uniqueCode("portal") + "@client.test",
                "not-used-here");
        contracts.grantPortalAccess(world.ref(clientCode), userId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), "n/a",
                        List.of(new SimpleGrantedAuthority("CLIENT_BILLING_READ"),
                                new SimpleGrantedAuthority("CLIENT_MARGIN_READ"))));
    }

    @When("the invoices of {string} are listed")
    public void listInvoices(String clientCode) {
        failure = null;
        listed = null;
        try {
            listed = inTransaction(() -> queries.invoicesOf(world.ref(clientCode), null, null));
        } catch (RuntimeException e) {
            failure = e;
        }
    }

    @When("the client margins for last month are asked for")
    public void askForMargins() {
        failure = null;
        try {
            inTransaction(() -> queries.margins(dayOfLastMonth(1), null, null));
        } catch (RuntimeException e) {
            failure = e;
        }
    }

    @Then("{int} client invoice is listed")
    @Then("{int} client invoices are listed")
    public void invoicesListed(int expected) {
        assertThat(failure).as("listing should have succeeded").isNull();
        assertThat(listed.items()).hasSize(expected);
    }

    @Then("the last-month invoice of {string} cannot be found by the signed-in user")
    public void invoiceNotFound(String clientCode) {
        UUID invoiceId = world.ref("client-invoice:" + clientCode);
        assertThat(inTransaction(() -> queries.findInvoice(invoiceId))).isEmpty();
        assertThat(inTransaction(() -> queries.linesOf(invoiceId))).isEmpty();
    }

    @Then("the client billing request is refused as unauthorised")
    public void refusedUnauthorised() {
        assertThat(failure).isInstanceOf(AccessDeniedException.class);
    }

    @Then("client billing refuses mentioning {string}")
    public void refusedMentioning(String fragment) {
        assertThat(failure).as("the operation should have been refused").isNotNull();
        StringBuilder chain = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            chain.append(t.getMessage()).append(' ');
        }
        assertThat(chain.toString()).contains(fragment);
    }

    // ------------------------------------------------------------ invoices

    @Then("{string} has {int} invoice for last month")
    @Then("{string} has {int} invoices for last month")
    public void invoiceCount(String clientCode, int expected) {
        long count = inTransaction(() -> jdbc.sql("""
                        SELECT count(*) FROM client_invoice
                         WHERE client_partner_id = :client AND period_start = :period
                        """)
                .param("client", world.ref(clientCode))
                .param("period", dayOfLastMonth(1))
                .query(Long.class)
                .single());
        assertThat(count).isEqualTo(expected);
    }

    @Then("the last-month invoice of {string} totals {int} over {int} lines")
    public void invoiceTotals(String clientCode, int total, int lineCount) {
        ClientInvoiceView invoice = invoiceOf(clientCode);
        assertThat(invoice.totalAmount()).isEqualByComparingTo(BigDecimal.valueOf(total));
        assertThat(invoice.lineCount()).isEqualTo(lineCount);
        assertThat(linesOf(clientCode)).hasSize(lineCount);
    }

    @Then("the last-month invoice of {string} has a {string} line of {int}")
    public void invoiceHasLine(String clientCode, String serviceType, int amount) {
        List<ClientInvoiceLineView> matching = linesOf(clientCode).stream()
                .filter(line -> line.serviceType().equals(serviceType))
                .toList();
        assertThat(matching).as("%s lines", serviceType).hasSize(1);
        assertThat(matching.get(0).amount()).isEqualByComparingTo(BigDecimal.valueOf(amount));
    }

    @Then("the last-month invoice of {string} adds up")
    public void invoiceAddsUp(String clientCode) {
        BigDecimal sum = linesOf(clientCode).stream()
                .map(ClientInvoiceLineView::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(invoiceOf(clientCode).totalAmount()).isEqualByComparingTo(sum);
    }

    @Then("the rerun answered with the same invoice for {string}")
    public void sameInvoice(String clientCode) {
        assertThat(failure).as("the rerun should have succeeded").isNull();
        assertThat(world.ref("client-invoice:" + clientCode))
                .isEqualTo(firstInvoiceIds.get(clientCode));
    }

    @Then("{string} has {int} charge waiting in this month's period, marked late")
    public void lateChargeWaiting(String clientCode, int expected) {
        List<ClientChargeView> waiting = inTransaction(() -> queries.chargesOf(
                world.ref(clientCode), LocalDate.now(ZoneOffset.UTC), null, null).items());
        assertThat(waiting).hasSize(expected);
        assertThat(waiting).allMatch(ClientChargeView::late);
        assertThat(waiting).allMatch(charge -> charge.invoiceId() == null);
    }

    @Then("{string} has {int} charge for last month")
    @Then("{string} has {int} charges for last month")
    public void chargesForLastMonth(String clientCode, int expected) {
        assertThat(inTransaction(() -> queries.chargesOf(world.ref(clientCode),
                dayOfLastMonth(1), null, null).items())).hasSize(expected);
    }

    // ------------------------------------------------------------- internals

    private void bill(String clientCode, LocalDate anyDay) {
        failure = null;
        try {
            UUID invoiceId = runs.run(world.ref(clientCode), anyDay);
            world.putRef("client-invoice:" + clientCode, invoiceId);
            firstInvoiceIds.putIfAbsent(clientCode, invoiceId);
        } catch (RuntimeException e) {
            failure = e;
        }
    }

    private void record(String clientCode, ServiceType type, String serviceCode, int quantity,
                        LocalDate on) {
        recordWithKey(clientCode, type, serviceCode, quantity, on,
                "usage-" + (++usageCounter));
    }

    private void recordWithKey(String clientCode, ServiceType type, String serviceCode,
                               int quantity, LocalDate on, String sourceKey) {
        failure = null;
        try {
            charges.recordServiceUsage(world.ref(clientCode), type, serviceCode,
                    BigDecimal.valueOf(quantity), on, "WMS " + sourceKey,
                    world.uniqueCode(sourceKey));
        } catch (RuntimeException e) {
            failure = e;
        }
    }

    private ClientInvoiceView invoiceOf(String clientCode) {
        UUID invoiceId = world.ref("client-invoice:" + clientCode);
        return inTransaction(() -> queries.findInvoice(invoiceId))
                .orElseThrow(() -> new AssertionError("No invoice for " + clientCode));
    }

    private List<ClientInvoiceLineView> linesOf(String clientCode) {
        UUID invoiceId = world.ref("client-invoice:" + clientCode);
        return inTransaction(() -> queries.linesOf(invoiceId));
    }

    private List<ClientChargeView> transportChargesFor(String clientCode, String tripNo) {
        UUID tripId = (UUID) tripRow(tripNo).get("id");
        return inTransaction(() -> jdbc.sql("""
                        SELECT id, client_partner_id, service_type, service_code, quantity, unit,
                               occurred_on, billing_period, false AS late, reference, source_key,
                               trip_id, invoice_id
                          FROM client_charge
                         WHERE client_partner_id = :client AND trip_id = :tripId
                        """)
                .param("client", world.ref(clientCode))
                .param("tripId", tripId)
                .query(ClientChargeView.class)
                .list());
    }

    private Map<String, Object> tripRow(String tripNo) {
        return inTransaction(() -> jdbc.sql("SELECT id, trip_no, load_id FROM trip WHERE trip_no = :no")
                .param("no", world.uniqueCode(tripNo))
                .query()
                .singleRow());
    }

    private static LocalDate dayOfLastMonth(int day) {
        return LocalDate.now(ZoneOffset.UTC).minusMonths(1).withDayOfMonth(day);
    }

    private static BigDecimal decimal(Map<String, String> row, String key) {
        String value = row.get(key);
        return value == null ? BigDecimal.ZERO : new BigDecimal(value);
    }

    private <T> T inTransaction(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }
}
