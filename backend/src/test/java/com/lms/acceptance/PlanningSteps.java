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
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.lms.masterdata.command.FleetCommandService;
import com.lms.masterdata.command.RegisterTerminalCommand;
import com.lms.masterdata.command.TerminalCommandService;
import com.lms.masterdata.command.VehicleRepository;
import com.lms.masterdata.command.domain.BusinessPartner;
import com.lms.masterdata.command.domain.Terminal;
import com.lms.masterdata.command.domain.Vehicle;
import com.lms.order.api.MaterialClass;
import com.lms.order.command.OrderCommandService;
import com.lms.order.command.SalesOrderRepository;
import com.lms.order.command.domain.SalesOrder;
import com.lms.planning.command.ConsignmentRepository;
import com.lms.planning.command.LoadUnitRepository;
import com.lms.planning.command.PlanningCommandService;
import com.lms.planning.command.domain.Consignment;
import com.lms.planning.command.domain.LoadUnit;
import com.lms.planning.query.PlanningQueryService;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.geo.LatLon;
import com.lms.shared.tenant.TenantContext;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step definitions for {@code planning.feature}.
 *
 * <p>Every step expression here is registered under exactly one Cucumber
 * keyword annotation. Given, When and Then are interchangeable at match time,
 * so the same text under two keywords registers twice and aborts glue
 * registration for the whole suite -- which shows up as every scenario being
 * "undefined", not as an error pointing at the duplicate.
 */
public class PlanningSteps {

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private FleetCommandService fleet;
    @Autowired
    private TerminalCommandService terminals;
    @Autowired
    private OrderCommandService orderCommands;
    @Autowired
    private PlanningCommandService planning;
    @Autowired
    private PlanningQueryService planningQueries;
    @Autowired
    private SalesOrderRepository orders;
    @Autowired
    private ConsignmentRepository consignments;
    @Autowired
    private LoadUnitRepository loads;
    @Autowired
    private VehicleRepository vehicles;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID lastPlannedOrderId;
    private List<UUID> generated = List.of();
    private RuntimeException failure;

    // ------------------------------------------------------------ master data

    @Given("a trading partner {string}")
    public void aTradingPartner(String code) {
        world.putRef(code, fleet.registerPartner(world.orgUnitId(), world.uniqueCode(code),
                code + " Trading", BusinessPartner.PartnerType.CUSTOMER, "TAX-" + code));
    }

    /**
     * A point-radius terminal rather than a polygon.
     *
     * <p>Cheaper to write in Gherkin, and the geometry itself is already
     * exhaustively covered by {@code masterdata.feature} and
     * {@code GeoUtilsTest}. Here a terminal is only ever an anchor for an order
     * line, so the interesting part is the reference, not the shape.
     */
    @Given("a depot {string} at {double},{double}")
    public void aDepot(String code, double lat, double lon) {
        world.putRef(code, terminals.register(new RegisterTerminalCommand.PointRadius(
                world.orgUnitId(), world.uniqueCode(code), code,
                Terminal.FunctionalCategory.CUSTOMER_SITE,
                new LatLon(lat, lon), 500, 2, null, null, 45, List.of())));
    }

    @Given("a lorry {string} with payload {int} kg and volume {int} m3")
    public void aLorry(String registration, int payloadKg, int volumeM3) {
        registerLorry(registration, payloadKg, volumeM3, false);
    }

    @Given("a hazmat-certified lorry {string} with payload {int} kg and volume {int} m3")
    public void aCertifiedLorry(String registration, int payloadKg, int volumeM3) {
        registerLorry(registration, payloadKg, volumeM3, true);
    }

    @Given("lorry {string} is taken off the road")
    public void lorryOffTheRoad(String registration) {
        inTransaction(() -> {
            Vehicle vehicle = vehicles.findById(world.ref("vehicle:" + registration)).orElseThrow();
            return vehicles.save(vehicle.withStatus(Vehicle.VehicleStatus.MAINTENANCE));
        });
    }

    // ----------------------------------------------------------------- orders

    @Given("an order {string} for {string} from {string}")
    public void anOrder(String orderNo, String customerCode, String originCode) {
        world.putRef("order:" + orderNo, orderCommands.raiseOrder(world.orgUnitId(), world.ref(customerCode),
                world.uniqueCode(orderNo), world.ref(originCode), null, null,
                SalesOrder.OrderSource.MANUAL));
    }

    @Given("order {string} has line {int} of {int} kg of {word} for {string} at {string}")
    public void orderHasLine(String orderNo, int lineNo, int weightKg, String materialClass,
                             String consigneeCode, String destinationCode) {
        addLine(orderNo, lineNo, weightKg, materialClass, null,
                null, null, null, consigneeCode, destinationCode);
    }

    @Given("order {string} has line {int} of {int} kg of {word} UN {string} for {string} at {string}")
    public void orderHasHazmatLine(String orderNo, int lineNo, int weightKg, String materialClass,
                                   String unCode, String consigneeCode, String destinationCode) {
        addLine(orderNo, lineNo, weightKg, materialClass, unCode,
                null, null, null, consigneeCode, destinationCode);
    }

    @Given("order {string} has line {int} of {int} kg of {word} measuring {double} by {double} "
            + "by {double} m for {string} at {string}")
    public void orderHasDimensionedLine(String orderNo, int lineNo, int weightKg, String materialClass,
                                        double lengthM, double widthM, double heightM,
                                        String consigneeCode, String destinationCode) {
        addLine(orderNo, lineNo, weightKg, materialClass, null,
                BigDecimal.valueOf(lengthM), BigDecimal.valueOf(widthM), BigDecimal.valueOf(heightM),
                consigneeCode, destinationCode);
    }

    @When("order {string} takes a further line {int} of {int} kg of {word} for {string} at {string}")
    public void orderTakesFurtherLine(String orderNo, int lineNo, int weightKg, String materialClass,
                                      String consigneeCode, String destinationCode) {
        addLine(orderNo, lineNo, weightKg, materialClass, null,
                null, null, null, consigneeCode, destinationCode);
    }

    @When("order {string} is validated")
    public void orderIsValidated(String orderNo) {
        attempt(() -> {
            orderCommands.validate(world.ref("order:" + orderNo));
            return null;
        });
    }

    // --------------------------------------------------------------- planning

    @When("consignments are generated for order {string}")
    public void generateConsignments(String orderNo) {
        lastPlannedOrderId = world.ref("order:" + orderNo);
        generated = List.of();
        attempt(() -> {
            generated = planning.generateConsignments(lastPlannedOrderId);
            return null;
        });
    }

    @When("a load {string} is opened at {string} on lorry {string}")
    public void openLoad(String loadNo, String originCode, String registration) {
        attempt(() -> {
            world.putRef("load:" + loadNo, planning.openLoad(world.orgUnitId(), world.uniqueCode(loadNo),
                    world.ref(originCode), world.ref("vehicle:" + registration)));
            return null;
        });
    }

    @When("the consignment for {string} at {string} is assigned to load {string}")
    public void assignConsignment(String consigneeCode, String destinationCode, String loadNo) {
        UUID consignmentId = consignmentFor(consigneeCode, destinationCode).id();
        attempt(() -> {
            planning.assignConsignment(world.ref("load:" + loadNo), consignmentId);
            return null;
        });
    }

    @When("load {string} is planned")
    public void planLoad(String loadNo) {
        attempt(() -> {
            planning.planLoad(world.ref("load:" + loadNo));
            return null;
        });
    }

    // ------------------------------------------------------------------- then

    @Then("order {string} is {string}")
    public void orderStatusIs(String orderNo, String expected) {
        SalesOrder order = inTransaction(() -> orders.findById(world.ref("order:" + orderNo)).orElseThrow());
        assertThat(order.status().name()).isEqualTo(expected);
    }

    @Then("validation is refused mentioning {string}")
    public void validationRefused(String fragment) {
        assertBusinessFailure("order-validation-failed", fragment);
    }

    @Then("the amendment is refused")
    public void amendmentRefused() {
        assertThat(failure).isInstanceOf(BusinessRuleViolationException.class);
        assertThat(((BusinessRuleViolationException) failure).code()).isEqualTo("order-not-editable");
    }

    @Then("{int} consignments are created")
    public void consignmentsCreated(int expected) {
        assertThat(failure)
                .as("generation should have succeeded but failed with: %s",
                        failure == null ? "" : failure.getMessage())
                .isNull();
        assertThat(generated).hasSize(expected);
    }

    @Then("order {string} has {int} consignment in total")
    public void orderHasConsignments(String orderNo, int expected) {
        List<Consignment> all = inTransaction(() ->
                consignments.findByOrder(TenantContext.requireTenantId(), world.ref("order:" + orderNo)));
        assertThat(all).hasSize(expected);
    }

    @Then("the consignment for {string} at {string} carries {int} kg")
    public void consignmentCarries(String consigneeCode, String destinationCode, int expectedKg) {
        assertThat(consignmentFor(consigneeCode, destinationCode).totalDeadWeightKg())
                .isEqualByComparingTo(BigDecimal.valueOf(expectedKg));
    }

    @Then("the consignment for {string} at {string} is chargeable at {int} kg")
    public void consignmentChargeable(String consigneeCode, String destinationCode, int expectedKg) {
        assertThat(consignmentFor(consigneeCode, destinationCode).chargeableWeightKg())
                .isEqualByComparingTo(BigDecimal.valueOf(expectedKg));
    }

    @Then("load {string} carries {int} kg")
    public void loadCarries(String loadNo, int expectedKg) {
        assertThat(load(loadNo).plannedWeightKg()).isEqualByComparingTo(BigDecimal.valueOf(expectedKg));
    }

    @Then("load {string} is {double} percent utilised by weight")
    public void loadUtilisation(String loadNo, double expected) {
        assertThat(load(loadNo).weightUtilisationPct())
                .isEqualByComparingTo(BigDecimal.valueOf(expected));
    }

    @Then("load {string} holds {int} consignments")
    public void loadHolds(String loadNo, int expected) {
        assertThat(inTransaction(() -> planningQueries.findConsignmentsOnLoad(world.ref("load:" + loadNo))))
                .hasSize(expected);
    }

    @Then("load {string} requires hazmat handling")
    public void loadRequiresHazmat(String loadNo) {
        assertThat(load(loadNo).requiresHazmat()).isTrue();
    }

    @Then("load {string} is {string}")
    public void loadStatusIs(String loadNo, String expected) {
        assertThat(load(loadNo).status().name()).isEqualTo(expected);
    }

    @Then("assignment is refused mentioning {string}")
    public void assignmentRefused(String fragment) {
        assertBusinessFailure(null, fragment);
    }

    @Then("opening the load is refused mentioning {string}")
    public void openingRefused(String fragment) {
        assertBusinessFailure(null, fragment);
    }

    @Then("planning the load is refused mentioning {string}")
    public void planningRefused(String fragment) {
        assertBusinessFailure("load-is-empty", fragment);
    }

    // ---------------------------------------------------------------- helpers

    private void registerLorry(String registration, int payloadKg, int volumeM3, boolean hazmatCertified) {
        // Payload is gross less tare, so the gross is quoted with a tare added
        // on top -- the capacity that matters for planning is what is left over
        // once the lorry has carried itself.
        int tare = 6000;
        world.putRef("vehicle:" + registration, fleet.registerVehicle(world.orgUnitId(), null, registration,
                "TRUCK", "RIGID", "2-AXLE",
                BigDecimal.valueOf(payloadKg + tare), BigDecimal.valueOf(tare),
                BigDecimal.valueOf(volumeM3), hazmatCertified, false));
    }

    private void addLine(String orderNo, int lineNo, int weightKg, String materialClass, String unCode,
                         BigDecimal lengthM, BigDecimal widthM, BigDecimal heightM,
                         String consigneeCode, String destinationCode) {
        attempt(() -> orderCommands.addLine(world.ref("order:" + orderNo), lineNo,
                "MAT-" + lineNo, materialClass + " goods",
                MaterialClass.valueOf(materialClass), unCode,
                BigDecimal.ONE, "EA", BigDecimal.valueOf(weightKg),
                lengthM, widthM, heightM, null,
                world.ref(consigneeCode), world.ref(destinationCode)));
    }

    private Consignment consignmentFor(String consigneeCode, String destinationCode) {
        UUID consigneeId = world.ref(consigneeCode);
        UUID destinationId = world.ref(destinationCode);
        return inTransaction(() -> consignments
                .findByOrder(TenantContext.requireTenantId(), lastPlannedOrderId).stream()
                .filter(c -> c.consigneePartnerId().equals(consigneeId))
                .filter(c -> c.destinationTerminalId().equals(destinationId))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No consignment for " + consigneeCode + " at " + destinationCode)));
    }

    private LoadUnit load(String loadNo) {
        return inTransaction(() -> loads.findById(world.ref("load:" + loadNo)).orElseThrow());
    }

    private void assertBusinessFailure(String expectedCode, String fragment) {
        assertThat(failure)
                .as("expected a refusal mentioning '%s' but the operation succeeded", fragment)
                .isInstanceOf(BusinessRuleViolationException.class);
        if (expectedCode != null) {
            assertThat(((BusinessRuleViolationException) failure).code()).isEqualTo(expectedCode);
        }
        assertThat(failure.getMessage()).contains(fragment);
    }

    private <T> T attempt(Supplier<T> action) {
        failure = null;
        try {
            return action.get();
        } catch (BusinessRuleViolationException | IllegalArgumentException e) {
            failure = e;
            return null;
        }
    }

    private <T> T inTransaction(Supplier<T> action) {
        // Scope is already bound by "the tenant scope is ..."; the transaction
        // is what carries it onto the connection, so a read outside one would
        // be correctly filtered to nothing by row-level security.
        TenantContext.requireTenantId();
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }
}
