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
import java.math.RoundingMode;
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
import com.lms.clientbilling.command.ClientCreditNoteService;
import com.lms.clientbilling.command.ClientStorageService;
import com.lms.clientbilling.command.domain.ClientContract;
import com.lms.clientbilling.command.domain.ServiceType;
import com.lms.clientbilling.query.ClientBillingQueryService;
import com.lms.clientbilling.query.ClientChargeView;
import com.lms.clientbilling.query.ClientCreditNoteView;
import com.lms.clientbilling.query.ClientInvoiceLineView;
import com.lms.clientbilling.query.ClientInvoiceView;
import com.lms.clientbilling.query.ClientMarginView;
import com.lms.clientbilling.query.ClientStatementEntryView;
import com.lms.clientbilling.query.ClientStorageView;
import com.lms.shared.query.Slice;
import com.lms.shared.tenant.TenantContext;
import com.lms.warehouse.command.WarehouseSetupCommandService;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
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
    private ClientCreditNoteService creditNotes;
    @Autowired
    private ClientStorageService storage;
    @Autowired
    private WarehouseSetupCommandService warehouseSetup;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private RuntimeException failure;
    private int usageCounter;
    private Slice<ClientInvoiceView> listed;
    private UUID lastCreditNote;
    private List<ClientStatementEntryView> statement;
    /** The invoice each client's first run answered with, to compare reruns against. */
    private final Map<String, UUID> firstInvoiceIds = new HashMap<>();

    @Before
    public void resetClientBillingState() {
        failure = null;
        usageCounter = 0;
        listed = null;
        lastCreditNote = null;
        statement = null;
    }

    // -------------------------------------------------------------- contracts

    /**
     * Rows are the fixed rates and tax percentages by name, the storage units
     * per pallet, plus any number of
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
                            decimal(row, "handling out per pallet"),
                            decimal(row, "transport tax percent"),
                            decimal(row, "warehouse tax percent"),
                            row.containsKey("storage units per pallet")
                                    ? Integer.valueOf(row.get("storage units per pallet")) : null),
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

    @When("the month before last is billed for {string}")
    public void billMonthBeforeLast(String clientCode) {
        bill(clientCode, dayOfLastMonth(1).minusMonths(1));
    }

    @Given("{string} stored {int} pallet-days on day {int} of the month before last")
    public void storedPalletDaysEarlier(String clientCode, int quantity, int day) {
        record(clientCode, ServiceType.STORAGE, null, quantity, dayOfLastMonth(day).minusMonths(1));
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
                new TestingAuthenticationToken(userId.toString(), null,
                        List.<GrantedAuthority>of(new SimpleGrantedAuthority("CLIENT_BILLING_READ"),
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

    // ------------------------------------------------------------------ tax

    @Then("the last-month invoice of {string} has a taxable amount of {bigdecimal}, tax of {bigdecimal} and a total of {bigdecimal}")
    public void invoiceTaxTotals(String clientCode, BigDecimal taxable, BigDecimal tax,
                                 BigDecimal total) {
        ClientInvoiceView invoice = invoiceOf(clientCode);
        assertThat(invoice.taxableAmount()).isEqualByComparingTo(taxable);
        assertThat(invoice.taxAmount()).isEqualByComparingTo(tax);
        assertThat(invoice.totalAmount()).isEqualByComparingTo(total);
    }

    @Then("every line of the last-month invoice of {string} carries its own tax, rounded half-up")
    public void everyLineTaxedOnItsOwn(String clientCode) {
        List<ClientInvoiceLineView> lines = linesOf(clientCode);
        BigDecimal tax = BigDecimal.ZERO;
        for (ClientInvoiceLineView line : lines) {
            BigDecimal expected = line.amount().multiply(line.taxRate()).movePointLeft(2)
                    .setScale(2, RoundingMode.HALF_UP);
            assertThat(line.taxAmount()).as("tax on line %d", line.lineNo())
                    .isEqualByComparingTo(expected);
            assertThat(line.lineTotal()).isEqualByComparingTo(line.amount().add(expected));
            tax = tax.add(expected);
        }
        assertThat(invoiceOf(clientCode).taxAmount()).isEqualByComparingTo(tax);
    }

    // --------------------------------------------------------- credit notes

    @When("a credit note for {bigdecimal} is issued against the last-month invoice of {string} because {string}")
    public void creditAmount(BigDecimal amount, String clientCode, String reason) {
        issueCredit(clientCode, reason, amount, List.of());
    }

    @When("a credit note for line {int} in full is issued against the last-month invoice of {string} because {string}")
    public void creditWholeLine(int lineNo, String clientCode, String reason) {
        issueCredit(clientCode, reason, null,
                List.of(new ClientCreditNoteService.LineCredit(lineNo, null)));
    }

    @Then("the credit note credits {bigdecimal} plus {bigdecimal} tax")
    public void creditNoteCredits(BigDecimal taxable, BigDecimal tax) {
        assertThat(failure).as("the credit note should have been issued").isNull();
        ClientCreditNoteView note = inTransaction(() -> queries.findCreditNote(lastCreditNote))
                .orElseThrow(() -> new AssertionError("No credit note"));
        assertThat(note.taxableAmount()).isEqualByComparingTo(taxable);
        assertThat(note.taxAmount()).isEqualByComparingTo(tax);
        assertThat(note.totalAmount()).isEqualByComparingTo(taxable.add(tax));
        BigDecimal linesTaxable = note.lines().stream()
                .map(ClientCreditNoteView.Line::taxableAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(linesTaxable).isEqualByComparingTo(taxable);
    }

    @Then("the last-month invoice of {string} shows {bigdecimal} credited")
    public void invoiceCredited(String clientCode, BigDecimal credited) {
        assertThat(invoiceOf(clientCode).creditedAmount()).isEqualByComparingTo(credited);
    }

    @Then("the credit note is in the audit trail with its reason")
    public void creditNoteAudited() {
        String after = inTransaction(() -> jdbc.sql("""
                        SELECT after_state::text FROM audit_log
                         WHERE action = 'CLIENT_CREDIT_NOTE_ISSUED' AND resource_id = :id
                        """)
                .param("id", lastCreditNote)
                .query(String.class)
                .single());
        ClientCreditNoteView note = inTransaction(() -> queries.findCreditNote(lastCreditNote))
                .orElseThrow();
        assertThat(after).contains(note.reason());
    }

    @Then("the margin on {string} for last month is a receivable of {bigdecimal} after {bigdecimal} credited")
    public void marginAfterCredit(String clientCode, BigDecimal receivable, BigDecimal credited) {
        UUID client = world.ref(clientCode);
        ClientMarginView view = inTransaction(() -> queries.margins(dayOfLastMonth(1), null, null)
                .items()).stream()
                .filter(m -> m.clientPartnerId().equals(client))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No margin row for " + clientCode));
        assertThat(view.receivable()).isEqualByComparingTo(receivable);
        assertThat(view.credited()).isEqualByComparingTo(credited);
        assertThat(view.margin()).isEqualByComparingTo(receivable.subtract(view.carrierPayable()));
    }

    // ------------------------------------------------------------ statement

    @When("the statement of {string} is read {int} entries a page")
    public void readStatement(String clientCode, int pageSize) {
        failure = null;
        statement = new ArrayList<>();
        try {
            String cursor = null;
            do {
                String from = cursor;
                Slice<ClientStatementEntryView> page = inTransaction(() ->
                        queries.statementOf(world.ref(clientCode), from, pageSize));
                assertThat(page.items().size()).isLessThanOrEqualTo(pageSize);
                statement.addAll(page.items());
                cursor = page.nextCursor();
            } while (cursor != null);
        } catch (RuntimeException e) {
            failure = e;
        }
    }

    @Then("the statement reads:")
    public void statementReads(List<Map<String, String>> expected) {
        assertThat(failure).as("the statement should have been read").isNull();
        assertThat(statement).hasSize(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            Map<String, String> row = expected.get(i);
            ClientStatementEntryView entry = statement.get(i);
            assertThat(entry.entryType()).as("entry %d", i + 1).isEqualTo(row.get("type"));
            assertThat(entry.debit()).as("debit of entry %d", i + 1)
                    .isEqualByComparingTo(row.get("debit"));
            assertThat(entry.credit()).as("credit of entry %d", i + 1)
                    .isEqualByComparingTo(row.get("credit"));
            assertThat(entry.balance()).as("balance after entry %d", i + 1)
                    .isEqualByComparingTo(row.get("balance"));
        }
    }

    // ------------------------------------------------- storage from the ledger

    @Given("stock item {string} belongs to client {string}")
    public void stockItemOfClient(String sku, String clientCode) {
        world.putRef("sku:" + sku, warehouseSetup.createSku(world.uniqueCode(sku),
                sku + " for " + clientCode, world.ref(clientCode), "EA"));
    }

    @Given("{int} units of {string} came into {string} on day {int} of last month")
    public void unitsCameIn(int units, String sku, String warehouse, int day) {
        ledgerMovement("RECEIPT", "DOCK", units, sku, warehouse, null, dayOfLastMonth(day));
    }

    @Given("{int} units of {string} came into {string} on day {int} of the month before last")
    public void unitsCameInEarlier(int units, String sku, String warehouse, int day) {
        ledgerMovement("RECEIPT", "DOCK", units, sku, warehouse, null,
                dayOfLastMonth(day).minusMonths(1));
    }

    // The shapes V27's quarantine flows write, dated last month: damaged goods
    // enter at QUARANTINE; a release moves them to a bin as two ADJUST
    // movements; a write-off is one ADJUST out of quarantine.
    @Given("{int} damaged units of {string} were quarantined at {string} on day {int} of last month")
    public void damagedQuarantined(int units, String sku, String warehouse, int day) {
        ledgerMovement("RECEIPT", "QUARANTINE", units, sku, warehouse, null,
                dayOfLastMonth(day));
    }

    @Given("{int} quarantined units of {string} at {string} were released to bin {string} on day {int} of last month")
    public void quarantineReleased(int units, String sku, String warehouse, String bin,
                                   int day) {
        ledgerMovement("ADJUST", "QUARANTINE", -units, sku, warehouse, null, dayOfLastMonth(day));
        ledgerMovement("ADJUST", "BIN", units, sku, warehouse, world.ref("bin:" + bin),
                dayOfLastMonth(day));
    }

    @Given("{int} quarantined units of {string} at {string} were written off on day {int} of last month")
    public void quarantineWrittenOff(int units, String sku, String warehouse, int day) {
        ledgerMovement("ADJUST", "QUARANTINE", -units, sku, warehouse, null,
                dayOfLastMonth(day));
    }

    @Then("the warehouse ledger shows {int} units held for {string} on day {int} of last month")
    public void ledgerShowsUnitsHeld(int units, String clientCode, int day) {
        assertThat(failure).as("the computation should have succeeded").isNull();
        ClientStorageView view = inTransaction(() ->
                queries.storageOf(world.ref(clientCode), dayOfLastMonth(1)));
        assertThat(view.days()).filteredOn(d -> d.heldOn().equals(dayOfLastMonth(day)))
                .singleElement().satisfies(d -> assertThat(d.unitsHeld()).isEqualTo(units));
    }

    @Given("{int} units of {string} left {string} on day {int} of last month")
    public void unitsLeft(int units, String sku, String warehouse, int day) {
        ledgerMovement("DISPATCH", "DOCK", -units, sku, warehouse, null, dayOfLastMonth(day));
    }

    @When("storage for last month is computed from the warehouse ledger for {string}")
    @When("storage for last month is computed from the warehouse ledger for {string} again")
    public void computeStorage(String clientCode) {
        failure = null;
        try {
            storage.computeMonth(world.ref(clientCode), dayOfLastMonth(1));
        } catch (RuntimeException e) {
            failure = e;
        }
    }

    @Then("the ledger storage of {string} for last month is {long} pallet-days from {long} unit-days")
    public void ledgerStorageIs(String clientCode, long palletDays, long unitDays) {
        assertThat(failure).as("the computation should have succeeded").isNull();
        ClientStorageView view = inTransaction(() ->
                queries.storageOf(world.ref(clientCode), dayOfLastMonth(1)));
        assertThat(view.computed()).isTrue();
        assertThat(view.days()).hasSize(dayOfLastMonth(1).lengthOfMonth());
        assertThat(view.palletDays()).isEqualTo(palletDays);
        assertThat(view.unitDays()).isEqualTo(unitDays);
        List<ClientChargeView> storageCharges = inTransaction(() -> queries.chargesOf(
                world.ref(clientCode), dayOfLastMonth(1), null, null).items()).stream()
                .filter(charge -> charge.serviceType().equals("STORAGE"))
                .toList();
        assertThat(storageCharges).singleElement().satisfies(charge ->
                assertThat(charge.quantity()).isEqualByComparingTo(BigDecimal.valueOf(palletDays)));
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

    private void issueCredit(String clientCode, String reason, BigDecimal amount,
                             List<ClientCreditNoteService.LineCredit> lines) {
        failure = null;
        try {
            lastCreditNote = creditNotes.issue(world.ref("client-invoice:" + clientCode), reason,
                    amount, lines);
        } catch (RuntimeException e) {
            failure = e;
        }
    }

    /**
     * A movement written straight to the ledger, dated in the past -- the
     * services stamp movements with the current time, and storage is about
     * what was held last month.
     */
    private void ledgerMovement(String type, String locationType, int quantity, String sku,
                                String warehouse, UUID locationId, LocalDate day) {
        inTransaction(() -> jdbc.sql("""
                        INSERT INTO stock_movement (id, tenant_id, warehouse_id, movement_type,
                                                    location_type, location_id, sku_id, lot_no,
                                                    quantity, actor, occurred_at, version)
                        VALUES (gen_random_uuid(), :tenantId, :warehouseId, :type,
                                :locationType, coalesce(:locationId, :warehouseId), :skuId, '',
                                :quantity, 'clientbilling.feature', :at, 0)
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("warehouseId", world.ref("warehouse:" + warehouse))
                .param("type", type)
                .param("locationType", locationType)
                .param("locationId", locationId)
                .param("skuId", world.ref("sku:" + sku))
                .param("quantity", quantity)
                .param("at", day.atTime(10, 0).atOffset(ZoneOffset.UTC))
                .update());
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
