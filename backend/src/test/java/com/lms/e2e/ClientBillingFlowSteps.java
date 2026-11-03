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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.lms.client.api.ClientBillingApi;
import com.lms.client.model.ClientContractServiceRate;
import com.lms.client.model.ClientCreditNote;
import com.lms.client.model.ClientCreditNoteLineRequest;
import com.lms.client.model.ClientCreditNoteRequest;
import com.lms.client.model.ClientInvoice;
import com.lms.client.model.ClientInvoiceLine;
import com.lms.client.model.ClientPaymentRequest;
import com.lms.client.model.ClientBillingRunRequest;
import com.lms.client.model.ClientServiceType;
import com.lms.client.model.ClientStatementEntry;
import com.lms.client.model.PublishClientContractRequest;
import com.lms.client.model.RegisterPartnerRequest;
import com.lms.client.model.ServiceUsageRequest;
import feign.FeignException;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Value;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Third-party logistics client billing over HTTP: a published client contract,
 * warehousing usage recorded for last month, the monthly billing run, and the
 * invoice that results.
 *
 * <p>Each scenario registers its own client, so the demo tenant's other
 * clients never appear on the invoice being checked. Usage is keyed by a
 * salted source key, as a warehouse system feeding the endpoint would.
 */
public class ClientBillingFlowSteps {

    @Value("${local.server.port}")
    private int port;

    private HttpWorld world;
    private UUID client;
    private LocalDate lastMonth;
    private UUID invoiceId;
    private int invoiceTotal;
    private UUID creditNoteId;

    @Given("the demo administrator is signed in at the client-billing desk")
    public void signIn() {
        world = new HttpWorld(port);
        world.signInAsDemoAdministrator();
        lastMonth = LocalDate.now(ZoneOffset.UTC).minusMonths(1).withDayOfMonth(1);
    }

    @Given("a client holds a contract charging {int} per pallet-day of storage, {int} per pallet in, {int} per pallet out and {int} per label, with {int} percent tax on warehousing")
    public void contract(int storage, int in, int out, int label, int taxPct) {
        client = world.partner("CLIENT", RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER);
        world.api(ClientBillingApi.class).publishClientContract(new PublishClientContractRequest()
                .orgUnitId(world.orgUnitId())
                .code(world.code("CC"))
                .clientPartnerId(client)
                .effectiveFrom(LocalDate.now(ZoneOffset.UTC).minusDays(90))
                .transportRatePerKg(new BigDecimal("5"))
                .transportMinimumCharge(BigDecimal.ZERO)
                .storageRatePerPalletDay(BigDecimal.valueOf(storage))
                .handlingInRatePerPallet(BigDecimal.valueOf(in))
                .handlingOutRatePerPallet(BigDecimal.valueOf(out))
                .transportTaxRatePct(BigDecimal.ZERO)
                .warehouseTaxRatePct(BigDecimal.valueOf(taxPct))
                .valueAddedServices(List.of(new ClientContractServiceRate()
                        .serviceCode("LABEL").unit("UNIT").rate(BigDecimal.valueOf(label)))), null);
    }

    @When("the warehouse reports {int} pallets in, {int} pallet-days stored, {int} pallets out and {int} labels for last month")
    public void usage(int in, int stored, int out, int labels) {
        record(ClientServiceType.HANDLING_IN, null, in, 2);
        record(ClientServiceType.STORAGE, null, stored, 5);
        record(ClientServiceType.HANDLING_OUT, null, out, 20);
        record(ClientServiceType.VALUE_ADDED, "LABEL", labels, 21);
    }

    @When("the warehouse resends its report of the {int} pallets in")
    public void resend(int in) {
        // The same source key as the first report: the endpoint is idempotent.
        record(ClientServiceType.HANDLING_IN, null, in, 2);
    }

    @When("last month is billed for the client")
    public void bill() {
        invoiceId = world.api(ClientBillingApi.class).runClientBilling(client,
                new ClientBillingRunRequest().period(lastMonth), null).getId();
        invoiceTotal = world.api(ClientBillingApi.class).getClientInvoice(invoiceId)
                .getTotalAmount().intValue();
    }

    @When("last month is billed for the client again")
    public void billAgain() {
        UUID again = world.api(ClientBillingApi.class).runClientBilling(client,
                new ClientBillingRunRequest().period(lastMonth), null).getId();
        assertThat(again).as("the same month yields the same invoice").isEqualTo(invoiceId);
    }

    @Then("the client has {int} invoice for last month totalling {int} over {int} lines")
    public void invoice(int count, int total, int lines) {
        List<ClientInvoice> invoices = world.api(ClientBillingApi.class)
                .listClientInvoices(client, null, 50).getItems();
        assertThat(invoices).hasSize(count);
        ClientInvoice invoice = invoices.get(0);
        assertThat(invoice.getId()).isEqualTo(invoiceId);
        assertThat(invoice.getPeriodStart()).isEqualTo(lastMonth);
        assertThat(invoice.getStatus()).isEqualTo(ClientInvoice.StatusEnum.ISSUED);
        assertThat(invoice.getTotalAmount()).isEqualByComparingTo(BigDecimal.valueOf(total));
        assertThat(invoice.getLineCount()).isEqualTo(lines);
    }

    @Then("the invoice has a {string} line of {int} and its lines add up to the taxable amount")
    public void line(String type, int amount) {
        List<ClientInvoiceLine> lines = world.api(ClientBillingApi.class)
                .getClientInvoiceLines(invoiceId);
        assertThat(lines).filteredOn(l -> type.equals(l.getServiceType().getValue()))
                .singleElement()
                .satisfies(l -> assertThat(l.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(amount)));
        BigDecimal sum = lines.stream().map(ClientInvoiceLine::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(
                world.api(ClientBillingApi.class).getClientInvoice(invoiceId).getTaxableAmount());
    }

    @Then("the invoice carries the {string} line of {int} too")
    public void anotherLine(String type, int amount) {
        line(type, amount);
    }

    @Then("the invoice shows {int} of tax on {int} taxable, and its {string} line carries {int} of tax at {int} percent")
    public void tax(int tax, int taxable, String type, int lineTax, int ratePct) {
        ClientInvoice invoice = world.api(ClientBillingApi.class).getClientInvoice(invoiceId);
        assertThat(invoice.getTaxAmount()).isEqualByComparingTo(BigDecimal.valueOf(tax));
        assertThat(invoice.getTaxableAmount()).isEqualByComparingTo(BigDecimal.valueOf(taxable));
        assertThat(invoice.getTotalAmount()).isEqualByComparingTo(BigDecimal.valueOf(taxable + tax));
        assertThat(invoiceLine(type)).satisfies(l -> {
            assertThat(l.getTaxAmount()).isEqualByComparingTo(BigDecimal.valueOf(lineTax));
            assertThat(l.getTaxRate()).isEqualByComparingTo(BigDecimal.valueOf(ratePct));
        });
    }

    @When("a credit note of {int} is issued against the {string} line for {string}")
    public void creditNote(int amount, String type, String reason) {
        creditNoteId = world.api(ClientBillingApi.class).issueClientCreditNote(invoiceId,
                new ClientCreditNoteRequest().reason(reason)
                        .lines(List.of(new ClientCreditNoteLineRequest()
                                .lineNo(invoiceLine(type).getLineNo())
                                .amount(BigDecimal.valueOf(amount)))), null).getId();
    }

    @Then("the credit note reverses {int} of tax for a total of {int} and the invoice shows {int} credited")
    public void creditNoteTotals(int tax, int total, int credited) {
        ClientCreditNote note = world.api(ClientBillingApi.class).getClientCreditNote(creditNoteId);
        assertThat(note.getInvoiceId()).isEqualTo(invoiceId);
        assertThat(note.getTaxAmount()).isEqualByComparingTo(BigDecimal.valueOf(tax));
        assertThat(note.getTotalAmount()).isEqualByComparingTo(BigDecimal.valueOf(total));
        assertThat(world.api(ClientBillingApi.class).getClientInvoice(invoiceId).getCreditedAmount())
                .isEqualByComparingTo(BigDecimal.valueOf(credited));
    }

    @Then("the credit note is refused when it asks for more than the {string} line charged")
    public void creditNoteTooLarge(String type) {
        assertThatThrownBy(() -> creditNote(1_000_000, type, "Greedy"))
                .isInstanceOfSatisfying(FeignException.class,
                        e -> assertThat(e.status()).isBetween(400, 409));
    }

    @Then("the client statement lists the invoice then the credit note and ends at a balance of {int}")
    public void statement(int balance) {
        List<ClientStatementEntry> entries = world.api(ClientBillingApi.class)
                .getClientStatement(client, null, 50).getItems();
        assertThat(entries).extracting(ClientStatementEntry::getEntryType)
                .containsExactly(ClientStatementEntry.EntryTypeEnum.INVOICE,
                        ClientStatementEntry.EntryTypeEnum.CREDIT_NOTE);
        ClientStatementEntry invoice = entries.get(0);
        ClientStatementEntry credit = entries.get(1);
        assertThat(invoice.getDebit()).isEqualByComparingTo(BigDecimal.valueOf(invoiceTotal));
        assertThat(invoice.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(invoiceTotal));
        assertThat(credit.getCredit()).isPositive();
        assertThat(credit.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(balance));
    }

    @When("the client pays {int} referenced {string} and {int} referenced {string}")
    public void payments(int first, String firstRef, int second, String secondRef) {
        pay(first, firstRef);
        pay(second, secondRef);
    }

    @Then("the client statement lists the invoice then two payments and ends at a balance of {int}")
    public void statementWithPayments(int balance) {
        List<ClientStatementEntry> entries = world.api(ClientBillingApi.class)
                .getClientStatement(client, null, 50).getItems();
        assertThat(entries).extracting(ClientStatementEntry::getEntryType)
                .containsExactly(ClientStatementEntry.EntryTypeEnum.INVOICE,
                        ClientStatementEntry.EntryTypeEnum.PAYMENT,
                        ClientStatementEntry.EntryTypeEnum.PAYMENT);
        assertThat(entries.get(1).getBalance())
                .isEqualByComparingTo(BigDecimal.valueOf(invoiceTotal - 5000));
        assertThat(entries.get(2).getBalance()).isEqualByComparingTo(BigDecimal.valueOf(balance));
    }

    @Then("the client's ageing shows {int} outstanding on {int} open invoice, all in the {string} bucket")
    public void ageingShows(int outstanding, int openInvoices, String bucket) {
        var view = world.api(ClientBillingApi.class).getClientAgeing(client, (LocalDate) null);
        assertThat(view.getOutstanding()).isEqualByComparingTo(BigDecimal.valueOf(outstanding));
        assertThat(view.getInvoices()).hasSize(openInvoices);
        if (openInvoices > 0) {
            assertThat(view.getInvoices().get(0).getBucket().getValue()).isEqualTo(bucket);
            assertThat(view.getInvoices().get(0).getPaid())
                    .isEqualByComparingTo(BigDecimal.valueOf(invoiceTotal - outstanding));
        }
    }

    @When("the client pays {int} referenced {string}")
    public void onePayment(int amount, String reference) {
        pay(amount, reference);
    }

    @Then("the remittance {string} is refused when it is entered again")
    public void duplicateRemittance(String reference) {
        assertThatThrownBy(() -> pay(1, reference))
                .isInstanceOfSatisfying(FeignException.class,
                        e -> assertThat(e.status()).isEqualTo(409));
    }

    @Then("a payment from a client that was never invoiced is refused")
    public void paymentWithoutInvoice() {
        UUID stranger = world.partner("STRANGER", RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER);
        assertThatThrownBy(() -> world.api(ClientBillingApi.class).recordClientPayment(stranger,
                new ClientPaymentRequest().amount(BigDecimal.TEN)
                        .receivedOn(LocalDate.now(ZoneOffset.UTC))
                        .reference(world.code("NEFT")), null))
                .isInstanceOfSatisfying(FeignException.class,
                        e -> assertThat(e.status()).isEqualTo(409));
    }

    // ----------------------------------------------------------- helpers

    private void pay(int amount, String reference) {
        world.api(ClientBillingApi.class).recordClientPayment(client, new ClientPaymentRequest()
                .amount(BigDecimal.valueOf(amount))
                .receivedOn(LocalDate.now(ZoneOffset.UTC))
                .reference(world.code(reference)), null);
    }

    private ClientInvoiceLine invoiceLine(String type) {
        return world.api(ClientBillingApi.class).getClientInvoiceLines(invoiceId).stream()
                .filter(l -> type.equals(l.getServiceType().getValue()))
                .findFirst().orElseThrow(() -> new AssertionError("no " + type + " line"));
    }


    private void record(ClientServiceType type, String code, int quantity, int dayOfMonth) {
        world.api(ClientBillingApi.class).recordClientServiceUsage(client, new ServiceUsageRequest()
                .serviceType(type)
                .serviceCode(code)
                .quantity(BigDecimal.valueOf(quantity))
                .occurredOn(lastMonth.withDayOfMonth(dayOfMonth))
                .reference("WMS " + type)
                .sourceKey(world.code(type.getValue() + "-" + dayOfMonth)), null);
    }
}
