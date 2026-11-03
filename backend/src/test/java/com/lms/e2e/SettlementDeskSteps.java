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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.client.api.SettlementApi;
import com.lms.client.model.AddSlabRequest;
import com.lms.client.model.AuthResult;
import com.lms.client.model.FreightBill;
import com.lms.client.model.FreightBillLine;
import com.lms.client.model.FuelSurchargeRequest;
import com.lms.client.model.MatchRequest;
import com.lms.client.model.MatchResponse;
import com.lms.client.model.PublishTariffRequest;
import com.lms.client.model.RegisterPartnerRequest;
import com.lms.client.model.RegisterPointRadiusTerminalRequest;
import com.lms.client.model.ResolveDisputeRequest;
import feign.FeignException;
import io.cucumber.java.After;
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
 * Freight-bill disputes and the fuel surcharge over HTTP: a vendor's inflated
 * invoice is disputed, resolved in the vendor's favour on the record and so
 * settles; a recorded fuel surcharge changes the next rating and nothing
 * already billed.
 *
 * <p>The trips run in a month of their own, some months back, so the fuel
 * figure this feature records can never reach another scenario's bills, and
 * it is set back to nothing afterwards whatever the outcome. Latitude band 47
 * degrees north.
 */
public class SettlementDeskSteps {

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
    private UUID vendor;
    private UUID customer;
    private UUID consignee;
    private final Map<String, UUID[]> lanes = new HashMap<>();
    private final Map<String, String> tripNos = new HashMap<>();
    private final Map<String, UUID> bills = new HashMap<>();
    private LocalDate tripMonth;
    private UUID fuelRecord;
    private int trips;

    private SettlementApi settlement() {
        return world.api(SettlementApi.class);
    }

    /** The month the fuel figure was recorded for is put back to no surcharge, pass or fail. */
    @After("@e2e_money_fuel")
    public void clearFuelSurcharge() {
        if (world != null && tripMonth != null && fuelRecord != null) {
            try {
                settlement().recordFuelSurcharge(new FuelSurchargeRequest().month(tripMonth)
                        .surchargePct(BigDecimal.ZERO), null);
            } catch (FeignException e) {
                // Nothing more can be done from here; the month is a private one.
            }
        }
    }

    // ------------------------------------------------------------------ set up

    @Given("the settlement desk is signed in as the demo administrator")
    public void signIn() {
        world = new HttpWorld(port);
        world.signInAsDemoAdministrator();
        support = new MoneyStockSupport(world, port, jdbc, passwordEncoder, transactionManager);
    }

    @Given("the settlement desk has an active vendor, a customer and a consignee")
    public void parties() {
        vendor = world.activeVendor("MSV");
        customer = world.partner("MSC", RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER);
        consignee = world.partner("MSN", RegisterPartnerRequest.PartnerTypeEnum.CONSIGNEE);
    }

    @Given("the settlement desk has lane {string} from {double},{double} to {double},{double} where the vendor charges {int} per kg")
    public void lane(String name, double originLat, double originLon, double destLat,
                     double destLon, int perKg) {
        UUID origin = world.terminal(RegisterPointRadiusTerminalRequest.CategoryEnum.CUSTOMER_SITE,
                "MS-" + name + "-FROM", originLat, originLon);
        UUID destination = world.terminal(
                RegisterPointRadiusTerminalRequest.CategoryEnum.CUSTOMER_SITE,
                "MS-" + name + "-TO", destLat, destLon);
        lanes.put(name, new UUID[] {origin, destination});
        UUID tariffId = settlement().publishTariff(new PublishTariffRequest()
                .orgUnitId(world.orgUnitId())
                .code(world.code("MSCARD-" + name))
                .vendorPartnerId(vendor)
                .originTerminalId(origin)
                .destinationTerminalId(destination)
                .vehicleType(HttpWorld.VEHICLE_TYPE)
                .currency("INR")
                .effectiveFrom(LocalDate.now(ZoneOffset.UTC).minusDays(300))
                .detentionFreeHours(new BigDecimal("2"))
                .detentionHourlyRate(BigDecimal.ZERO)
                .additionalDropFee(BigDecimal.ZERO)
                .minimumCharge(BigDecimal.ZERO), null).getId();
        settlement().addTariffSlab(tariffId, new AddSlabRequest()
                .minWeightKg(BigDecimal.ZERO)
                .rateBasis(AddSlabRequest.RateBasisEnum.PER_KG)
                .rateAmount(BigDecimal.valueOf(perKg)), null);
    }

    @Given("the settlement desk's {string} trip carrying {int} kg ran on lane {string} {int} months ago on day {int} and was delivered")
    @When("the settlement desk's {string} trip carrying {int} kg then ran on lane {string} {int} months ago on day {int} and was delivered")
    public void trip(String tag, int kilos, String lane, int monthsAgo, int day) {
        trips++;
        UUID[] ends = lanes.get(lane);
        UUID vehicle = world.lorry("MS" + trips, 20000);
        UUID driver = world.driver("MS" + trips);
        UUID tripId = world.assignedTrip("MS-" + tag, customer, consignee, ends[0], ends[1],
                vendor, vehicle, driver, kilos);
        OffsetDateTime dispatchedAt = OffsetDateTime.now(ZoneOffset.UTC).minusMonths(monthsAgo)
                .withDayOfMonth(day).withHour(10).withMinute(0).withSecond(0).withNano(0);
        world.dispatch(tripId, ends[0], kilos, dispatchedAt);
        world.deliver(tripId, dispatchedAt.plusHours(6));
        tripMonth = dispatchedAt.toLocalDate().withDayOfMonth(1);
        tripNos.put(tag, "TR-MS-" + tag + "-" + world.salt);
    }

    /** The bill a trip's completion raised: the first bill from just below its number. */
    private FreightBill bill(String tag) {
        String billNo = "FB-" + tripNos.get(tag);
        String justBelow = billNo.substring(0, billNo.length() - 1);
        FreightBill found = HttpWorld.eventually("the freight bill of trip " + tag, () ->
                settlement().listFreightBills(null, justBelow, 5).getItems().stream()
                        .filter(b -> billNo.equals(b.getBillNo())).findFirst()
                        .orElseThrow(() -> new AssertionError("no bill " + billNo)));
        bills.put(tag, found.getId());
        return settlement().getFreightBill(found.getId());
    }

    // ------------------------------------------------------------------ bills

    @Then("the settlement desk finds a draft freight bill of {int} for the {string} trip with no fuel surcharge, made of one base freight line")
    public void plainBill(int amount, String tag) {
        FreightBill bill = bill(tag);
        assertThat(bill.getStatus()).isEqualTo(FreightBill.StatusEnum.DRAFT);
        assertThat(bill.getComputedAmount()).isEqualByComparingTo(BigDecimal.valueOf(amount));
        assertThat(bill.getBaseFreight()).isEqualByComparingTo(BigDecimal.valueOf(amount));
        assertThat(bill.getFuelSurchargeAmount()).isEqualByComparingTo("0");
        List<FreightBillLine> lines = settlement().getFreightBillLines(bill.getId());
        assertThat(lines).singleElement().satisfies(line -> {
            assertThat(line.getLineNo()).isEqualTo(1);
            assertThat(line.getChargeType()).isEqualTo(FreightBillLine.ChargeTypeEnum.BASE_FREIGHT);
            assertThat(line.getUnit()).isEqualTo("kg");
            assertThat(line.getQuantity()).isEqualByComparingTo("5000");
            assertThat(line.getRate()).isEqualByComparingTo("4");
            assertThat(line.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(amount));
        });
    }

    @Then("the settlement desk finds a draft freight bill of {int} for the {string} trip including {int} of fuel surcharge at {int} percent of the linehaul")
    public void fuelBill(int amount, String tag, int fuel, int pct) {
        FreightBill bill = bill(tag);
        assertThat(bill.getStatus()).isEqualTo(FreightBill.StatusEnum.DRAFT);
        assertThat(bill.getComputedAmount()).isEqualByComparingTo(BigDecimal.valueOf(amount));
        assertThat(bill.getBaseFreight()).isEqualByComparingTo("20000");
        assertThat(bill.getFuelSurchargeAmount()).isEqualByComparingTo(BigDecimal.valueOf(fuel));
        List<FreightBillLine> lines = settlement().getFreightBillLines(bill.getId());
        assertThat(lines).extracting(FreightBillLine::getChargeType)
                .containsExactly(FreightBillLine.ChargeTypeEnum.BASE_FREIGHT,
                        FreightBillLine.ChargeTypeEnum.FUEL_SURCHARGE);
        assertThat(lines.get(1)).satisfies(line -> {
            assertThat(line.getLineNo()).isEqualTo(2);
            assertThat(line.getRate()).isEqualByComparingTo(BigDecimal.valueOf(pct));
            assertThat(line.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(fuel));
            assertThat(line.getNarrative()).contains("Fuel surcharge");
        });
        assertThat(lines.stream().map(FreightBillLine::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
                .as("the lines add up to the bill").isEqualByComparingTo(bill.getComputedAmount());
    }

    @Then("the bill of the {string} trip still carries its original {int} and no fuel surcharge")
    public void billUnchanged(String tag, int amount) {
        FreightBill bill = settlement().getFreightBill(bills.get(tag));
        assertThat(bill.getComputedAmount()).isEqualByComparingTo(BigDecimal.valueOf(amount));
        assertThat(bill.getFuelSurchargeAmount()).isEqualByComparingTo("0");
        assertThat(settlement().getFreightBillLines(bills.get(tag))).hasSize(1);
    }

    @Then("asking for the lines of a bill that does not exist lists none")
    public void unknownLines() {
        assertThat(settlement().getFreightBillLines(UUID.randomUUID())).isEmpty();
    }

    // -------------------------------------------------------------- the fuel

    @When("the settlement desk records a fuel surcharge of {int} percent for the month of those trips")
    public void recordFuel(int pct) {
        fuelRecord = settlement().recordFuelSurcharge(new FuelSurchargeRequest().month(tripMonth)
                .surchargePct(BigDecimal.valueOf(pct)), null).getId();
        assertThat(fuelRecord).isNotNull();
    }

    @Then("recording the fuel surcharge for that month again at {int} percent corrects the same record")
    public void recordFuelAgain(int pct) {
        UUID again = settlement().recordFuelSurcharge(new FuelSurchargeRequest()
                .month(tripMonth.plusDays(9)).surchargePct(BigDecimal.valueOf(pct)), null).getId();
        assertThat(again).isEqualTo(fuelRecord);
    }

    @When("the administrator of another tenant records a fuel surcharge of {int} percent for that month in its own books")
    public void otherTenantFuel(int pct) {
        AuthResult other = support.otherTenantAdministrator();
        UUID id = support.as(other, SettlementApi.class).recordFuelSurcharge(
                new FuelSurchargeRequest().month(tripMonth)
                        .surchargePct(BigDecimal.valueOf(pct)), null).getId();
        assertThat(id).isNotEqualTo(fuelRecord);
    }

    @Then("recording a fuel surcharge without a month is refused with 400 and code {string}")
    public void fuelWithoutMonth(String code) {
        assertProblem(() -> settlement().recordFuelSurcharge(
                new FuelSurchargeRequest().surchargePct(BigDecimal.TEN), null), 400, code);
    }

    @Then("recording a fuel surcharge without a percentage is refused with 400 and code {string}")
    public void fuelWithoutPercentage(String code) {
        assertProblem(() -> settlement().recordFuelSurcharge(
                new FuelSurchargeRequest().month(LocalDate.now(ZoneOffset.UTC).minusYears(5)), null),
                400, code);
    }

    @Then("a user who may read freight bills but not manage tariffs is refused recording a fuel surcharge as forbidden")
    public void fuelForbidden() {
        AuthResult reader = support.userWith("fuelreader", "INVOICE_READ");
        assertProblem(() -> support.as(reader, SettlementApi.class).recordFuelSurcharge(
                new FuelSurchargeRequest().month(LocalDate.now(ZoneOffset.UTC).minusYears(5))
                        .surchargePct(BigDecimal.ONE), null), 403, "access-denied");
    }

    // ---------------------------------------------------------------- disputes

    @When("the vendor's invoice of {int} for the {string} trip is matched against its bill")
    public void match(int claimed, String tag) {
        MatchResponse response = settlement().matchFreightBill(bills.get(tag),
                new MatchRequest().claimedAmount(BigDecimal.valueOf(claimed)), null);
        assertThat(response.getStatus()).isEqualTo(MatchResponse.StatusEnum.DISPUTED);
    }

    @Then("the bill of the {string} trip is disputed with the claim of {int} and a variance of {int}, explained by the match")
    public void disputed(String tag, int claimed, int variance) {
        FreightBill bill = settlement().getFreightBill(bills.get(tag));
        assertThat(bill.getStatus()).isEqualTo(FreightBill.StatusEnum.DISPUTED);
        assertThat(bill.getClaimedAmount()).isEqualByComparingTo(BigDecimal.valueOf(claimed));
        assertThat(bill.getVarianceAmount()).isEqualByComparingTo(BigDecimal.valueOf(variance));
        assertThat(bill.getVariancePct()).isEqualByComparingTo("25");
        assertThat(bill.getResolution()).as("the match's explanation, not yet a resolution")
                .contains("Claimed 25000 against computed 20000").contains("beyond the allowance");
        assertThat(bill.getMatchedAt()).isNotNull();
        assertThat(settlement().listFreightBills("DISPUTED", billNoJustBelow(tag), 5).getItems())
                .extracting(FreightBill::getId).contains(bills.get(tag));
    }

    @When("the settlement desk resolves the dispute of the {string} trip's bill because {string}")
    public void resolve(String tag, String reason) {
        settlement().resolveDispute(bills.get(tag), new ResolveDisputeRequest().reason(reason),
                null);
    }

    @Then("the bill of the {string} trip is approved for payment, keeps the claim of {int} and records the reason {string}")
    public void resolved(String tag, int claimed, String reason) {
        FreightBill bill = settlement().getFreightBill(bills.get(tag));
        assertThat(bill.getStatus()).isEqualTo(FreightBill.StatusEnum.APPROVED_FOR_PAYMENT);
        assertThat(bill.getClaimedAmount()).isEqualByComparingTo(BigDecimal.valueOf(claimed));
        assertThat(bill.getResolution()).isEqualTo(reason);
        assertThat(bill.getMatchedAt()).isNotNull();
        assertThat(settlement().listFreightBills("APPROVED_FOR_PAYMENT", billNoJustBelow(tag), 5)
                .getItems()).extracting(FreightBill::getId).contains(bills.get(tag));
        assertThat(settlement().listFreightBills("DISPUTED", billNoJustBelow(tag), 5).getItems())
                .extracting(FreightBill::getId).doesNotContain(bills.get(tag));
    }

    private String billNoJustBelow(String tag) {
        String billNo = "FB-" + tripNos.get(tag);
        return billNo.substring(0, billNo.length() - 1);
    }

    @Then("resolving the bill of the {string} trip because {string} is refused with {int} and code {string}")
    public void resolveRefused(String tag, String reason, int status, String code) {
        assertProblem(() -> settlement().resolveDispute(bills.get(tag),
                new ResolveDisputeRequest().reason(reason), null), status, code);
    }

    @Then("resolving a bill that does not exist is refused as not found")
    public void resolveUnknown() {
        assertProblem(() -> settlement().resolveDispute(UUID.randomUUID(),
                new ResolveDisputeRequest().reason("No such bill"), null), 404, "not-found");
    }

    @Then("a user who may read freight bills but not approve them is refused resolving the dispute of the {string} trip's bill as forbidden")
    public void resolveForbidden(String tag) {
        AuthResult reader = support.userWith("billreader", "INVOICE_READ");
        SettlementApi api = support.as(reader, SettlementApi.class);
        assertProblem(() -> api.resolveDispute(bills.get(tag),
                new ResolveDisputeRequest().reason("Not mine to settle"), null),
                403, "access-denied");
        assertThat(api.getFreightBillLines(bills.get(tag))).as("the same user can read").isNotEmpty();
    }

    @Then("the administrator of another tenant finds no lines for the bill of the {string} trip, cannot read it and cannot resolve it")
    public void otherTenant(String tag) {
        AuthResult other = support.otherTenantAdministrator();
        SettlementApi api = support.as(other, SettlementApi.class);
        assertThat(api.getFreightBillLines(bills.get(tag))).isEmpty();
        assertProblem(() -> api.getFreightBill(bills.get(tag)), 404, "not-found");
        assertProblem(() -> api.resolveDispute(bills.get(tag),
                new ResolveDisputeRequest().reason("Another tenant's bill"), null),
                404, "not-found");
    }
}
