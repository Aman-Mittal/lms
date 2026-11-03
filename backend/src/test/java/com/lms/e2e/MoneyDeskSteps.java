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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.client.api.ClientBillingApi;
import com.lms.client.api.WarehouseApi;
import com.lms.client.model.AuthResult;
import com.lms.client.model.ClientBillingRunRequest;
import com.lms.client.model.ClientCharge;
import com.lms.client.model.ClientContract;
import com.lms.client.model.ClientContractServiceRate;
import com.lms.client.model.ClientCreditNoteLineRequest;
import com.lms.client.model.ClientCreditNoteRequest;
import com.lms.client.model.ClientInvoice;
import com.lms.client.model.ClientInvoiceLine;
import com.lms.client.model.ClientMargin;
import com.lms.client.model.ClientMarginSlice;
import com.lms.client.model.ClientPortalUserRequest;
import com.lms.client.model.ClientServiceType;
import com.lms.client.model.ClientStatementEntry;
import com.lms.client.model.ClientStorage;
import com.lms.client.model.ClientStorageComputationRequest;
import com.lms.client.model.CreateSkuRequest;
import com.lms.client.model.PublishClientContractRequest;
import com.lms.client.model.RegisterPartnerRequest;
import com.lms.client.model.RegisterPointRadiusTerminalRequest;
import com.lms.client.model.ServiceUsageRequest;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

import static com.lms.e2e.MoneyStockSupport.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Third-party-logistics billing reads over HTTP: a client's contract versions
 * and filed charges, storage computed from the warehouse ledger, the client
 * portal that confines a user to one client's records, and the margin view
 * that portal users may never see.
 *
 * <p>Each scenario registers its own clients, so the demo tenant's other
 * clients never appear in what is asserted about them. Latitude band 46
 * degrees north.
 */
public class MoneyDeskSteps {

    @Value("${local.server.port}")
    private int port;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private HttpWorld world;
    private MoneyStockSupport support;
    private LocalDate lastMonth;
    private final Map<String, UUID> clients = new HashMap<>();
    private final Map<String, UUID> invoices = new HashMap<>();
    private final Map<String, BigDecimal> taxable = new HashMap<>();
    private final Map<String, BigDecimal> totals = new HashMap<>();
    private final Map<String, UUID> contracts = new HashMap<>();
    private UUID warehouse;
    private UUID stockedItem;
    private ClientStorage computed;
    private AuthResult portal;

    private ClientBillingApi billing() {
        return world.api(ClientBillingApi.class);
    }

    private UUID client(String name) {
        return clients.get(name);
    }

    // ------------------------------------------------------------------ set up

    @Given("the billing desk is signed in as the demo administrator")
    public void signIn() {
        world = new HttpWorld(port);
        world.signInAsDemoAdministrator();
        support = new MoneyStockSupport(world, port, jdbc, passwordEncoder, transactionManager);
        lastMonth = LocalDate.now(ZoneOffset.UTC).minusMonths(1).withDayOfMonth(1);
    }

    @Given("the billing desk has client {string} under a contract charging {int} per pallet-day, {int} per pallet in, {int} per pallet out and {int} per label, with {int} percent tax on warehousing")
    public void client(String name, int storage, int in, int out, int label, int taxPct) {
        UUID id = world.partner("MC-" + name, RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER);
        clients.put(name, id);
        contracts.put(name, billing().publishClientContract(contract(id, name + "-1", storage, in,
                out, label, taxPct, LocalDate.now(ZoneOffset.UTC).minusDays(120)), null).getId());
    }

    private PublishClientContractRequest contract(UUID clientId, String tag, int storage, int in,
                                                  int out, int label, int taxPct,
                                                  LocalDate from) {
        return new PublishClientContractRequest()
                .orgUnitId(world.orgUnitId())
                .code(world.code("MCC-" + tag))
                .clientPartnerId(clientId)
                .effectiveFrom(from)
                .transportRatePerKg(new BigDecimal("5"))
                .transportMinimumCharge(BigDecimal.ZERO)
                .storageRatePerPalletDay(BigDecimal.valueOf(storage))
                .handlingInRatePerPallet(BigDecimal.valueOf(in))
                .handlingOutRatePerPallet(BigDecimal.valueOf(out))
                .transportTaxRatePct(BigDecimal.ZERO)
                .warehouseTaxRatePct(BigDecimal.valueOf(taxPct))
                .storageUnitsPerPallet(10)
                .valueAddedServices(List.of(new ClientContractServiceRate()
                        .serviceCode("LABEL").unit("UNIT").rate(BigDecimal.valueOf(label))));
    }

    @Given("the billing desk has client {string} with no contract at all")
    public void clientWithoutContract(String name) {
        clients.put(name, world.partner("MC-" + name,
                RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER));
    }

    @When("the billing desk records for client {string} {int} pallets in, {int} pallet-days stored, {int} pallets out and {int} labels last month")
    public void usage(String name, int in, int stored, int out, int labels) {
        record(name, ClientServiceType.HANDLING_IN, null, in, 2);
        record(name, ClientServiceType.STORAGE, null, stored, 5);
        record(name, ClientServiceType.HANDLING_OUT, null, out, 20);
        record(name, ClientServiceType.VALUE_ADDED, "LABEL", labels, 21);
    }

    @When("the billing desk bills last month for client {string}")
    public void bill(String name) {
        UUID invoiceId = billing().runClientBilling(client(name),
                new ClientBillingRunRequest().period(lastMonth), null).getId();
        ClientInvoice invoice = billing().getClientInvoice(invoiceId);
        invoices.put(name, invoiceId);
        taxable.put(name, invoice.getTaxableAmount());
        totals.put(name, invoice.getTotalAmount());
    }

    private void record(String name, ClientServiceType type, String code, int quantity, int day) {
        billing().recordClientServiceUsage(client(name), new ServiceUsageRequest()
                .serviceType(type).serviceCode(code).quantity(BigDecimal.valueOf(quantity))
                .occurredOn(lastMonth.withDayOfMonth(day)).reference("WMS " + type)
                .sourceKey(world.salt + "-" + name + "-" + type.getValue() + "-" + day), null);
    }

    // -------------------------------------------------------------- charges

    @Then("the billing desk lists {int} charges of last month for client {string} with the quantities reported, none of them invoiced")
    public void chargesBeforeBilling(int count, String name) {
        List<ClientCharge> listed = billing()
                .listClientCharges(lastMonth.withDayOfMonth(15), client(name), null, 50).getItems();
        assertThat(listed).hasSize(count);
        assertCharges(listed, name);
        assertThat(listed).allSatisfy(c -> assertThat(c.getInvoiceId()).isNull());
    }

    @Then("the billing desk lists the same {int} charges for client {string} now carrying the invoice")
    public void chargesAfterBilling(int count, String name) {
        List<ClientCharge> listed = billing()
                .listClientCharges(lastMonth, client(name), null, 50).getItems();
        assertThat(listed).hasSize(count);
        assertCharges(listed, name);
        assertThat(listed).allSatisfy(c -> assertThat(c.getInvoiceId())
                .isEqualTo(invoices.get(name)));
    }

    private void assertCharges(List<ClientCharge> listed, String name) {
        assertThat(listed).allSatisfy(c -> {
            assertThat(c.getClientPartnerId()).isEqualTo(client(name));
            assertThat(c.getBillingPeriod()).isEqualTo(lastMonth);
            assertThat(c.getLate()).isFalse();
            assertThat(c.getTripId()).isNull();
            assertThat(c.getSourceKey()).startsWith("USAGE:" + world.salt + "-" + name + "-");
            assertThat(c.getUnit()).isNotBlank();
        });
        assertThat(listed).extracting(ClientCharge::getServiceType)
                .containsExactlyInAnyOrder(ClientServiceType.HANDLING_IN, ClientServiceType.STORAGE,
                        ClientServiceType.HANDLING_OUT, ClientServiceType.VALUE_ADDED);
        ClientCharge labels = listed.stream()
                .filter(c -> c.getServiceType() == ClientServiceType.VALUE_ADDED).findFirst().get();
        assertThat(labels.getServiceCode()).isEqualTo("LABEL");
        assertThat(labels.getOccurredOn()).isEqualTo(lastMonth.withDayOfMonth(21));
        assertThat(labels.getReference()).isEqualTo("WMS VALUE_ADDED");
    }

    @Then("the billing desk pages client {string}'s charges of last month two at a time and sees each of the {int} once")
    public void chargePages(String name, int count) {
        var page1 = billing().listClientCharges(lastMonth, client(name), null, 2);
        assertThat(page1.getItems()).hasSize(2);
        assertThat(page1.getHasMore()).isTrue();
        assertThat(page1.getNextCursor()).isNotBlank();
        var page2 = billing().listClientCharges(lastMonth, client(name), page1.getNextCursor(), 2);
        assertThat(page2.getItems()).hasSize(count - 2);
        assertThat(page2.getHasMore()).isFalse();
        List<UUID> seen = new ArrayList<>();
        page1.getItems().forEach(c -> seen.add(c.getId()));
        page2.getItems().forEach(c -> seen.add(c.getId()));
        assertThat(seen).hasSize(count).doesNotHaveDuplicates();
    }

    @Then("the billing desk lists no charges of last month for client {string}")
    public void noCharges(String name) {
        assertThat(billing().listClientCharges(lastMonth, client(name), null, 50).getItems())
                .isEmpty();
    }

    @When("the billing desk reports {int} more pallets in for client {string} dated last month after the month was billed")
    public void lateUsage(int pallets, String name) {
        billing().recordClientServiceUsage(client(name), new ServiceUsageRequest()
                .serviceType(ClientServiceType.HANDLING_IN).quantity(BigDecimal.valueOf(pallets))
                .occurredOn(lastMonth.withDayOfMonth(27)).reference("WMS late")
                .sourceKey(world.code(name + "-LATE")), null);
    }

    @Then("the billing desk lists that late charge for client {string} under the current month, marked late and dated last month")
    public void lateCharge(String name) {
        LocalDate thisMonth = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
        assertThat(billing().listClientCharges(thisMonth, client(name), null, 50).getItems())
                .singleElement().satisfies(c -> {
                    assertThat(c.getLate()).isTrue();
                    assertThat(c.getBillingPeriod()).isEqualTo(thisMonth);
                    assertThat(c.getOccurredOn()).isEqualTo(lastMonth.withDayOfMonth(27));
                    assertThat(c.getServiceType()).isEqualTo(ClientServiceType.HANDLING_IN);
                    assertThat(c.getQuantity()).isEqualByComparingTo("7");
                    assertThat(c.getInvoiceId()).isNull();
                });
        assertThat(billing().listClientCharges(lastMonth, client(name), null, 50).getItems())
                .as("last month still holds only what it held").hasSize(4);
    }

    // ------------------------------------------------------------ contracts

    @Then("the billing desk lists client {string}'s one contract with its rates, tax and the label service, still open")
    public void oneContract(String name) {
        List<ClientContract> listed = billing().listClientContracts(client(name));
        assertThat(listed).singleElement().satisfies(c -> {
            assertThat(c.getId()).isEqualTo(contracts.get(name));
            assertThat(c.getCode()).isEqualTo(world.code("MCC-" + name + "-1"));
            assertThat(c.getClientPartnerId()).isEqualTo(client(name));
            assertThat(c.getEffectiveFrom()).isEqualTo(LocalDate.now(ZoneOffset.UTC).minusDays(120));
            assertThat(c.getEffectiveTo()).isNull();
            assertThat(c.getStorageRatePerPalletDay()).isEqualByComparingTo("12");
            assertThat(c.getHandlingInRatePerPallet()).isEqualByComparingTo("25");
            assertThat(c.getHandlingOutRatePerPallet()).isEqualByComparingTo("30");
            assertThat(c.getWarehouseTaxRatePct()).isEqualByComparingTo("18");
            assertThat(c.getTransportTaxRatePct()).isEqualByComparingTo("0");
            assertThat(c.getTransportRatePerKg()).isEqualByComparingTo("5");
            assertThat(c.getStorageUnitsPerPallet()).isEqualTo(10);
            assertThat(c.getValueAddedServices()).singleElement().satisfies(s -> {
                assertThat(s.getServiceCode()).isEqualTo("LABEL");
                assertThat(s.getUnit()).isEqualTo("UNIT");
                assertThat(s.getRate()).isEqualByComparingTo("2");
            });
        });
    }

    @When("the billing desk publishes a successor contract for client {string} from {int} days ago charging {int} per pallet-day")
    public void successor(String name, int daysAgo, int storage) {
        contracts.put(name + "-2", billing().publishClientContract(contract(client(name),
                name + "-2", storage, 25, 30, 2, 18,
                LocalDate.now(ZoneOffset.UTC).minusDays(daysAgo)), null).getId());
    }

    @Then("the billing desk lists client {string}'s two contracts newest first, the earlier closed the day before the successor starts")
    public void twoContracts(String name) {
        List<ClientContract> listed = billing().listClientContracts(client(name));
        assertThat(listed).extracting(ClientContract::getId)
                .containsExactly(contracts.get(name + "-2"), contracts.get(name));
        assertThat(listed.get(0).getEffectiveTo()).isNull();
        assertThat(listed.get(0).getStorageRatePerPalletDay()).isEqualByComparingTo("15");
        assertThat(listed.get(1).getEffectiveTo())
                .isEqualTo(listed.get(0).getEffectiveFrom().minusDays(1));
        assertThat(listed.get(1).getStorageRatePerPalletDay()).isEqualByComparingTo("12");
    }

    @Then("the billing desk lists no contracts for a client that does not exist")
    public void noContracts() {
        assertThat(billing().listClientContracts(UUID.randomUUID())).isEmpty();
    }

    // -------------------------------------------------------------- storage

    @Given("the billing desk has a warehouse holding {int} units of a stock item owned by client {string} from day {int} to day {int} of last month")
    public void ledgerStock(int units, String name, int fromDay, int toDay) {
        warehouse = world.terminal(RegisterPointRadiusTerminalRequest.CategoryEnum.WAREHOUSE,
                "MC-WH", 46.10, 11.10);
        stockedItem = sku(name, "OWN");
        hold(stockedItem, units, fromDay, toDay);
    }

    @Given("the billing desk also holds {int} units of another stock item owned by client {string} over the same days")
    public void otherClientStock(int units, String name) {
        hold(sku(name, "OTHER"), units, 1, 10);
    }

    private UUID sku(String name, String tag) {
        return world.api(WarehouseApi.class).createSku(new CreateSkuRequest()
                .code(world.salt + "-MC-" + tag).description("Stock of client " + name)
                .ownerPartnerId(client(name)).uom("EA"), null).getId();
    }

    /** The ledger fact no HTTP call can produce: stock that arrived and left during last month. */
    private void hold(UUID item, int units, int fromDay, int toDay) {
        OffsetDateTime in = lastMonth.withDayOfMonth(fromDay).atTime(10, 0).atOffset(ZoneOffset.UTC);
        OffsetDateTime out = lastMonth.withDayOfMonth(toDay).plusDays(1).atTime(10, 0)
                .atOffset(ZoneOffset.UTC);
        support.ledgerMovement(warehouse, item, units, in);
        support.ledgerMovement(warehouse, item, -units, out);
    }

    @Then("last month's storage for client {string} has not been computed yet")
    public void notComputed(String name) {
        ClientStorage storage = billing().getClientStorage(lastMonth.plusDays(5), client(name));
        assertThat(storage.getComputed()).isFalse();
        assertThat(storage.getClientPartnerId()).isEqualTo(client(name));
        assertThat(storage.getPeriodStart()).isEqualTo(lastMonth);
    }

    @When("the billing desk computes last month's storage for client {string}")
    public void compute(String name) {
        computed = billing().computeClientStorage(client(name),
                new ClientStorageComputationRequest().period(lastMonth.plusDays(3)), null);
    }

    @Then("the computation shows {int} unit-days and {int} pallet-days over every day of last month, with {int} pallets on the first day")
    public void computation(int unitDays, int palletDays, int firstDayPallets) {
        assertComputation(computed, unitDays, palletDays, firstDayPallets);
    }

    private void assertComputation(ClientStorage storage, int unitDays, int palletDays,
                                   int firstDayPallets) {
        assertThat(storage.getComputed()).isTrue();
        assertThat(storage.getPeriodStart()).isEqualTo(lastMonth);
        assertThat(storage.getUnitDays()).isEqualTo(unitDays);
        assertThat(storage.getPalletDays()).isEqualTo(palletDays);
        assertThat(storage.getDays()).hasSize(lastMonth.lengthOfMonth());
        assertThat(storage.getDays().get(0).getHeldOn()).isEqualTo(lastMonth);
        assertThat(storage.getDays().get(0).getPallets()).isEqualTo(firstDayPallets);
        assertThat(storage.getDays().get(0).getUnitsPerPallet()).isEqualTo(10);
        assertThat(storage.getDays().get(0).getChargeId()).isNotNull();
        assertThat(storage.getDays().get(10).getUnitsHeld()).isZero();
        assertThat(storage.getDays().get(10).getChargeId()).isNull();
    }

    @Then("computing it again answers with the same computation and client {string} still has one storage charge of {int} pallet-days")
    public void computeAgain(String name, int palletDays) {
        ClientStorage again = billing().computeClientStorage(client(name),
                new ClientStorageComputationRequest().period(lastMonth), null);
        assertThat(again.getPalletDays()).isEqualTo(palletDays);
        assertThat(again.getUnitDays()).isEqualTo(computed.getUnitDays());
        assertThat(again.getDays().get(0).getChargeId())
                .isEqualTo(computed.getDays().get(0).getChargeId());
        assertThat(billing().listClientCharges(lastMonth, client(name), null, 50).getItems())
                .filteredOn(c -> c.getServiceType() == ClientServiceType.STORAGE)
                .singleElement().satisfies(c -> {
                    assertThat(c.getQuantity()).isEqualByComparingTo(BigDecimal.valueOf(palletDays));
                    assertThat(c.getId()).isEqualTo(computed.getDays().get(0).getChargeId());
                });
    }

    @Then("reading client {string}'s storage for last month returns the computation again with {int} unit-days")
    public void readStorage(String name, int unitDays) {
        ClientStorage read = billing().getClientStorage(lastMonth.plusDays(20), client(name));
        assertThat(read.getComputed()).isTrue();
        assertThat(read.getUnitDays()).isEqualTo(unitDays);
        assertThat(read.getPalletDays()).isEqualTo(computed.getPalletDays());
        assertThat(read.getDays()).hasSize(lastMonth.lengthOfMonth());
    }

    @Then("storage for the current month is refused for client {string} because the month has not ended")
    public void currentMonthRefused(String name) {
        assertProblem(() -> billing().computeClientStorage(client(name),
                new ClientStorageComputationRequest().period(LocalDate.now(ZoneOffset.UTC)), null),
                409, "client-period-not-ended");
    }

    @Then("storage for client {string} is refused because it holds no contract")
    public void noContractRefused(String name) {
        assertProblem(() -> billing().computeClientStorage(client(name),
                new ClientStorageComputationRequest().period(lastMonth), null),
                409, "client-without-contract");
    }

    @Then("storage recorded by hand for client {string} in a month computed from the ledger is refused")
    public void handStorageRefused(String name) {
        assertProblem(() -> billing().recordClientServiceUsage(client(name),
                new ServiceUsageRequest().serviceType(ClientServiceType.STORAGE)
                        .quantity(BigDecimal.valueOf(5)).occurredOn(lastMonth.withDayOfMonth(4))
                        .reference("by hand").sourceKey(world.code(name + "-BYHAND")), null),
                409, "client-storage-from-ledger");
    }

    @Then("computing storage from the ledger for client {string} is refused because storage was already recorded by hand")
    public void computeAfterHandRefused(String name) {
        assertProblem(() -> billing().computeClientStorage(client(name),
                new ClientStorageComputationRequest().period(lastMonth), null),
                409, "client-storage-recorded-by-hand");
    }

    @Then("the invoice for last month of client {string} carries a storage line of {int} on {int} pallet-days and totals {string} with tax")
    public void storageInvoice(String name, int amount, int palletDays, String total) {
        bill(name);
        List<ClientInvoiceLine> lines = billing().getClientInvoiceLines(invoices.get(name));
        assertThat(lines).singleElement().satisfies(l -> {
            assertThat(l.getServiceType()).isEqualTo(ClientServiceType.STORAGE);
            assertThat(l.getQuantity()).isEqualByComparingTo(BigDecimal.valueOf(palletDays));
            assertThat(l.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(amount));
            assertThat(l.getChargeId()).isEqualTo(computed.getDays().get(0).getChargeId());
        });
        assertThat(totals.get(name)).isEqualByComparingTo(total);
    }

    // --------------------------------------------------------------- portal

    @Given("a portal user holding the billing and margin read permissions is provisioned but not yet bound to any client")
    public void portalUser() {
        portal = support.userWith("portal", "CLIENT_BILLING_READ", "CLIENT_MARGIN_READ");
    }

    @Then("before it is bound the portal user can read the invoices of both client {string} and client {string}")
    public void unbound(String a, String b) {
        ClientBillingApi api = support.as(portal, ClientBillingApi.class);
        assertThat(api.listClientInvoices(client(a), null, 10).getItems())
                .extracting(ClientInvoice::getId).containsExactly(invoices.get(a));
        assertThat(api.listClientInvoices(client(b), null, 10).getItems())
                .extracting(ClientInvoice::getId).containsExactly(invoices.get(b));
    }

    @When("the billing desk binds the portal user to client {string}")
    public void bind(String name) {
        billing().grantClientPortalAccess(client(name),
                new ClientPortalUserRequest().userId(MoneyStockSupport.idOf(portal)), null);
    }

    @Then("the portal user sees client {string}'s invoice, statement, charges, contract and storage but is refused client {string}'s with access denied")
    public void portalView(String own, String foreign) {
        ClientBillingApi api = support.as(portal, ClientBillingApi.class);
        assertThat(api.listClientInvoices(client(own), null, 10).getItems())
                .singleElement().satisfies(i -> {
                    assertThat(i.getId()).isEqualTo(invoices.get(own));
                    assertThat(i.getTotalAmount()).isEqualByComparingTo(totals.get(own));
                });
        assertThat(api.getClientInvoice(invoices.get(own)).getClientPartnerId())
                .isEqualTo(client(own));
        assertThat(api.getClientInvoiceLines(invoices.get(own))).hasSize(4);
        assertThat(api.getClientStatement(client(own), null, 10).getItems())
                .singleElement().satisfies(e -> {
                    assertThat(e.getEntryType()).isEqualTo(ClientStatementEntry.EntryTypeEnum.INVOICE);
                    assertThat(e.getDocumentId()).isEqualTo(invoices.get(own));
                });
        assertThat(api.listClientCharges(lastMonth, client(own), null, 10).getItems()).hasSize(4);
        assertThat(api.listClientContracts(client(own))).hasSize(1);
        assertThat(api.getClientStorage(lastMonth, client(own)).getComputed()).isFalse();

        assertProblem(() -> api.listClientInvoices(client(foreign), null, 10), 403, "access-denied");
        assertProblem(() -> api.getClientStatement(client(foreign), null, 10), 403, "access-denied");
        assertProblem(() -> api.listClientCharges(lastMonth, client(foreign), null, 10),
                403, "access-denied");
        assertProblem(() -> api.listClientContracts(client(foreign)), 403, "access-denied");
        assertProblem(() -> api.getClientStorage(lastMonth, client(foreign)), 403, "access-denied");
        assertProblem(() -> api.getClientInvoice(invoices.get(foreign)), 404, "not-found");
        assertThat(api.getClientInvoiceLines(invoices.get(foreign)))
                .as("another client's lines are not shown").isEmpty();
    }

    @Then("the portal user is refused the margin view as access denied although it holds the margin permission")
    public void portalNoMargins() {
        ClientBillingApi api = support.as(portal, ClientBillingApi.class);
        assertProblem(() -> api.listClientMargins(lastMonth, null, 10), 403, "access-denied");
        assertThat(billing().listClientMargins(lastMonth, null, 10).getItems())
                .as("staff still read it").isNotEmpty();
    }

    @Then("binding a user to a partner that is not a customer is refused with 409 and code {string}")
    public void bindVendor(String code) {
        UUID vendor = world.partner("MC-VENDOR", RegisterPartnerRequest.PartnerTypeEnum.VENDOR);
        assertProblem(() -> billing().grantClientPortalAccess(vendor,
                new ClientPortalUserRequest().userId(MoneyStockSupport.idOf(portal)), null),
                409, code);
    }

    @Then("binding a user without naming one is refused with 400 and code {string}")
    public void bindNobody(String code) {
        assertProblem(() -> billing().grantClientPortalAccess(client("A"),
                new ClientPortalUserRequest(), null), 400, code);
    }

    @Then("a billing user who may only read is refused binding a portal user as forbidden")
    public void readerCannotBind() {
        AuthResult reader = support.userWith("reader", "CLIENT_BILLING_READ");
        assertProblem(() -> support.as(reader, ClientBillingApi.class).grantClientPortalAccess(
                client("A"), new ClientPortalUserRequest()
                        .userId(MoneyStockSupport.idOf(portal)), null), 403, "access-denied");
    }

    // -------------------------------------------------------------- margins

    @Then("the billing desk lists margins for last month showing the whole invoice as receivable and no carrier cost for clients {string} and {string}")
    public void margins(String a, String b) {
        List<ClientMargin> all = allMargins(200);
        for (String name : List.of(a, b)) {
            assertThat(all).filteredOn(m -> m.getClientPartnerId().equals(client(name)))
                    .singleElement().satisfies(m -> {
                        assertThat(m.getInvoiceId()).isEqualTo(invoices.get(name));
                        assertThat(m.getPeriodStart()).isEqualTo(lastMonth);
                        assertThat(m.getClientCode()).isEqualTo(world.code("MC-" + name));
                        assertThat(m.getClientName()).isNotBlank();
                        assertThat(m.getInvoiceNo()).isNotBlank();
                        assertThat(m.getCurrency()).isNotBlank();
                        assertThat(m.getReceivable()).isEqualByComparingTo(taxable.get(name));
                        assertThat(m.getWarehouseReceivable()).isEqualByComparingTo(taxable.get(name));
                        assertThat(m.getTransportReceivable()).isEqualByComparingTo("0");
                        assertThat(m.getCredited()).isEqualByComparingTo("0");
                        assertThat(m.getCarrierPayable()).isEqualByComparingTo("0");
                        assertThat(m.getMargin()).isEqualByComparingTo(taxable.get(name));
                        assertThat(m.getMarginPct()).isEqualByComparingTo("100");
                        assertThat(m.getMovements()).isZero();
                        assertThat(m.getUnpricedMovements()).isZero();
                    });
        }
    }

    @Then("the billing desk pages the margins of last month one at a time and reaches both client {string} and client {string}")
    public void marginPages(String a, String b) {
        ClientMarginSlice page1 = billing().listClientMargins(lastMonth.plusDays(9), null, 1);
        assertThat(page1.getItems()).hasSize(1);
        assertThat(page1.getHasMore()).isTrue();
        assertThat(page1.getNextCursor()).isNotBlank();
        ClientMarginSlice page2 = billing().listClientMargins(lastMonth.plusDays(9),
                page1.getNextCursor(), 1);
        assertThat(page2.getItems()).hasSize(1);
        assertThat(page2.getItems().get(0).getClientPartnerId())
                .isNotEqualTo(page1.getItems().get(0).getClientPartnerId());
        assertThat(allMargins(1)).extracting(ClientMargin::getClientPartnerId)
                .contains(client(a), client(b)).doesNotHaveDuplicates();
    }

    private List<ClientMargin> allMargins(int pageSize) {
        List<ClientMargin> all = new ArrayList<>();
        String cursor = null;
        for (int guard = 0; guard < 1000; guard++) {
            ClientMarginSlice page = billing().listClientMargins(lastMonth, cursor, pageSize);
            all.addAll(page.getItems());
            if (!Boolean.TRUE.equals(page.getHasMore())) {
                return all;
            }
            cursor = page.getNextCursor();
        }
        throw new AssertionError("the margins never ended");
    }

    @When("the billing desk credits {int} against client {string}'s {string} line for {string}")
    public void credit(int amount, String name, String type, String reason) {
        int lineNo = billing().getClientInvoiceLines(invoices.get(name)).stream()
                .filter(l -> l.getServiceType().getValue().equals(type)).findFirst().get()
                .getLineNo();
        billing().issueClientCreditNote(invoices.get(name), new ClientCreditNoteRequest()
                .reason(reason).lines(List.of(new ClientCreditNoteLineRequest().lineNo(lineNo)
                        .amount(BigDecimal.valueOf(amount)))), null);
    }

    @Then("the margin of client {string} shows {int} credited and a receivable reduced by it")
    public void creditedMargin(String name, int credited) {
        assertThat(allMargins(200)).filteredOn(m -> m.getClientPartnerId().equals(client(name)))
                .singleElement().satisfies(m -> {
                    assertThat(m.getCredited()).isEqualByComparingTo(BigDecimal.valueOf(credited));
                    assertThat(m.getReceivable())
                            .isEqualByComparingTo(taxable.get(name).subtract(BigDecimal.valueOf(credited)));
                    assertThat(m.getMargin()).isEqualByComparingTo(m.getReceivable());
                });
    }

    // ------------------------------------------------------ who may do what

    @Then("a billing user holding no billing permission is refused contracts, charges, storage, its computation, margins and portal binding as forbidden")
    public void noBillingPermission() {
        AuthResult clerk = support.userWith("nobilling", "WAREHOUSE_READ");
        ClientBillingApi api = support.as(clerk, ClientBillingApi.class);
        UUID someone = client("A");
        assertProblem(() -> api.listClientContracts(someone), 403, "access-denied");
        assertProblem(() -> api.listClientCharges(lastMonth, someone, null, 10), 403, "access-denied");
        assertProblem(() -> api.getClientStorage(lastMonth, someone), 403, "access-denied");
        assertProblem(() -> api.computeClientStorage(someone,
                new ClientStorageComputationRequest().period(lastMonth), null), 403, "access-denied");
        assertProblem(() -> api.listClientMargins(lastMonth, null, 10), 403, "access-denied");
        assertProblem(() -> api.grantClientPortalAccess(someone,
                new ClientPortalUserRequest().userId(clerk.getUserId()), null), 403, "access-denied");
    }

    @Then("a billing user who may read billing but not margins is refused the margin view as forbidden")
    public void noMarginPermission() {
        AuthResult clerk = support.userWith("nomargin", "CLIENT_BILLING_READ");
        assertProblem(() -> support.as(clerk, ClientBillingApi.class)
                .listClientMargins(lastMonth, null, 10), 403, "access-denied");
    }

    @Then("the administrator of another tenant sees no contracts, charges, storage or margins of client {string}")
    public void otherTenant(String name) {
        AuthResult other = support.otherTenantAdministrator();
        ClientBillingApi api = support.as(other, ClientBillingApi.class);
        assertThat(api.listClientContracts(client(name))).isEmpty();
        assertThat(api.listClientCharges(lastMonth, client(name), null, 10).getItems()).isEmpty();
        assertThat(api.getClientStorage(lastMonth, client(name)).getComputed()).isFalse();
        assertThat(api.listClientMargins(lastMonth, null, 10).getItems()).isEmpty();
        assertProblem(() -> api.getClientInvoice(invoices.get(name)), 404, "not-found");
        assertProblem(() -> api.grantClientPortalAccess(client(name),
                new ClientPortalUserRequest().userId(other.getUserId()), null),
                409, "client-not-a-customer");
    }
}
