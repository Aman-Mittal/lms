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
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.masterdata.api.FleetCompliancePort;
import com.lms.masterdata.command.DriverRepository;
import com.lms.masterdata.command.FleetCommandService;
import com.lms.masterdata.command.VehicleRepository;
import com.lms.masterdata.command.domain.BusinessPartner;
import com.lms.masterdata.command.domain.ComplianceDocument;
import com.lms.masterdata.command.domain.Vehicle;
import com.lms.masterdata.query.ComplianceDocumentView;
import com.lms.masterdata.query.MasterDataQueryService;
import com.lms.masterdata.query.VehicleView;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.tenant.TenantContext;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Step definitions for {@code fleet.feature}. */
public class FleetSteps {

    @Autowired
    private FleetCommandService fleet;
    @Autowired
    private FleetCompliancePort compliance;
    @Autowired
    private VehicleRepository vehicles;
    @Autowired
    private DriverRepository drivers;
    @Autowired
    private MasterDataQueryService masterData;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private final Map<String, UUID> vehicleIds = new HashMap<>();
    private final Map<String, UUID> driverIds = new HashMap<>();
    private final Map<String, UUID> partnerIds = new HashMap<>();

    private FleetCompliancePort.ComplianceVerdict verdict;
    private RuntimeException failure;

    // ---------------------------------------------------------------- given

    @Given("a vehicle {string} with gross {int} kg and tare {int} kg and volume {int} m3")
    public void aVehicle(String registration, int gross, int tare, int volume) {
        registerVehicle(registration, gross, tare, volume, null);
    }

    @When("a vehicle {string} with gross {int} kg and tare {int} kg and volume {int} m3 is registered")
    public void registerVehicleAttempt(String registration, int gross, int tare, int volume) {
        registerVehicle(registration, gross, tare, volume, null);
    }

    @When("a vehicle {string} owned by {string} is registered")
    public void registerVehicleOwnedBy(String registration, String partnerCode) {
        registerVehicle(registration, 16000, 6000, 40, partnerIds.get(partnerCode));
    }

    @Given("vehicle {string} has an {string} document expiring in {int} days")
    @Given("vehicle {string} has a {string} document expiring in {int} days")
    public void vehicleDocumentExpiring(String registration, String type, int inDays) {
        fleet.attachDocument(ComplianceDocument.OwnerType.VEHICLE, vehicleIds.get(registration),
                type, type + "-001", "RTO", LocalDate.now().minusYears(1),
                LocalDate.now().plusDays(inDays));
    }

    @Given("vehicle {string} has a {string} document that never expires")
    public void vehicleDocumentNoExpiry(String registration, String type) {
        fleet.attachDocument(ComplianceDocument.OwnerType.VEHICLE, vehicleIds.get(registration),
                type, type + "-001", "RTO", LocalDate.now().minusYears(1), null);
    }

    @Given("vehicle {string} is put into MAINTENANCE")
    public void vehicleIntoMaintenance(String registration) {
        inTransaction(() -> {
            Vehicle vehicle = vehicles.findById(vehicleIds.get(registration)).orElseThrow();
            vehicles.save(vehicle.withStatus(Vehicle.VehicleStatus.MAINTENANCE));
            return null;
        });
    }

    @Given("a driver {string} with licence {string} expiring in {int} days")
    public void aDriver(String name, String licence, int inDays) {
        driverIds.put(name, fleet.registerDriver(worldOrgUnit(), null, name, "+910000000000",
                licence, "HEAVY", "RTO", LocalDate.now().plusDays(inDays), false));
    }

    @Given("driver {string} has driven {int} minutes today")
    public void driverHours(String name, int minutes) {
        inTransaction(() -> {
            var driver = drivers.findById(driverIds.get(name)).orElseThrow();
            drivers.save(driver.withHoursOfService(minutes));
            return null;
        });
    }

    @Given("a {word} partner {string} named {string}")
    public void aPartner(String type, String code, String legalName) {
        partnerIds.put(code, fleet.registerPartner(worldOrgUnit(), code, legalName,
                BusinessPartner.PartnerType.valueOf(type), "TAX-" + code));
    }

    // ----------------------------------------------------------------- when

    // One keyword annotation per expression -- Given/When/Then are
    // interchangeable at match time, so repeating the same text under a second
    // keyword registers a duplicate and aborts glue registration entirely.
    @When("partner {string} moves to {string}")
    public void partnerMovesTo(String code, String target) {
        failure = null;
        try {
            fleet.transitionPartner(partnerIds.get(code), BusinessPartner.PartnerStatus.valueOf(target));
        } catch (BusinessRuleViolationException e) {
            failure = e;
        }
    }

    @When("dispatch readiness is checked for vehicle {string} today")
    public void checkReadinessToday(String registration) {
        verdict = compliance.checkDispatchReadiness(vehicleIds.get(registration), null, LocalDate.now());
    }

    @When("dispatch readiness is checked for vehicle {string} in {int} days")
    public void checkReadinessFuture(String registration, int inDays) {
        verdict = compliance.checkDispatchReadiness(
                vehicleIds.get(registration), null, LocalDate.now().plusDays(inDays));
    }

    @When("dispatch readiness is checked for vehicle {string} and driver {string} today")
    public void checkReadinessWithDriver(String registration, String driverName) {
        verdict = compliance.checkDispatchReadiness(
                vehicleIds.get(registration), driverIds.get(driverName), LocalDate.now());
    }

    // ----------------------------------------------------------------- then

    @Then("the vehicle is cleared to dispatch")
    public void clearedToDispatch() {
        assertThat(verdict.dispatchable())
                .as("expected a clear verdict but was blocked by: %s", verdict.summary())
                .isTrue();
    }

    @Then("dispatch is blocked")
    public void dispatchBlocked() {
        assertThat(verdict.dispatchable()).isFalse();
        assertThat(verdict.blockingReasons()).isNotEmpty();
    }

    @Then("a blocking reason mentions {string}")
    public void reasonMentions(String fragment) {
        assertThat(verdict.summary()).containsIgnoringCase(fragment);
    }

    @Then("{int} blocking reasons are reported")
    public void reasonCount(int expected) {
        assertThat(verdict.blockingReasons()).hasSize(expected);
    }

    @Then("vehicle {string} has a payload capacity of {int} kg")
    public void payloadCapacity(String registration, int expected) {
        Vehicle vehicle = inTransaction(() -> vehicles.findById(vehicleIds.get(registration)).orElseThrow());
        assertThat(vehicle.payloadCapacityKg()).isEqualByComparingTo(BigDecimal.valueOf(expected));
    }

    @Then("vehicle registration is refused as invalid")
    public void vehicleRefusedInvalid() {
        assertThat(failure).isInstanceOf(IllegalArgumentException.class);
    }

    @Then("vehicle registration is refused because the partner cannot transact")
    public void vehicleRefusedPartner() {
        assertThat(failure).isInstanceOf(BusinessRuleViolationException.class);
        assertThat(((BusinessRuleViolationException) failure).code()).isEqualTo("partner-not-transactable");
    }

    @Then("partner {string} is in status {string}")
    public void partnerStatus(String code, String expected) {
        assertThat(loadPartner(code).status().name()).isEqualTo(expected);
    }

    @Then("partner {string} can transact")
    public void partnerCanTransact(String code) {
        assertThat(loadPartner(code).canTransact()).isTrue();
    }

    @Then("partner {string} cannot transact")
    public void partnerCannotTransact(String code) {
        assertThat(loadPartner(code).canTransact()).isFalse();
    }

    // ------------------------------------------------------------ read side

    @Then("the fleet listing shows {int} vehicles")
    public void fleetListingCount(int expected) {
        assertThat(inTransaction(() -> masterData.listVehicles(null, null, null)).items())
                .as("vehicles visible to this tenant")
                .hasSize(expected);
    }

    @Then("the fleet listing shows {string} with a payload capacity of {int} kg")
    public void fleetListingPayload(String registration, int expected) {
        // Computed in SQL rather than per row in Java. It is the number
        // planning loads against, and every client would otherwise have to
        // subtract the two weights the same way and hope.
        assertThat(vehicleRow(registration).payloadCapacityKg())
                .isEqualByComparingTo(BigDecimal.valueOf(expected));
    }

    @Then("the fleet listing shows {string} expiring on the earliest of its certificates")
    public void fleetListingEarliestExpiry(String registration) {
        VehicleView row = vehicleRow(registration);
        List<ComplianceDocumentView> documents = inTransaction(() ->
                masterData.documentsFor("VEHICLE", vehicleIds.get(registration)));

        LocalDate earliest = documents.stream()
                .map(ComplianceDocumentView::expiresOn)
                .filter(java.util.Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElseThrow();

        assertThat(row.earliestDocumentExpiry())
                .as("the soonest lapse is what a dispatcher has to act on")
                .isEqualTo(earliest);
    }

    @Then("the fleet listing shows {string} with {int} expired certificate(s)")
    public void fleetListingExpiredCount(String registration, int expected) {
        assertThat(vehicleRow(registration).expiredDocumentCount()).isEqualTo(expected);
    }

    @Then("{int} certificate(s) expire within {int} days")
    public void expiringWithin(int expected, int days) {
        assertThat(inTransaction(() -> masterData.documentsExpiringWithin(days)))
                .as("certificates lapsing within %d days", days)
                .hasSize(expected);
    }

    @Then("the expiry list reports {string} as {int} days overdue")
    public void expiryListOverdue(String documentType, int daysOverdue) {
        ComplianceDocumentView document = inTransaction(() ->
                masterData.documentsExpiringWithin(365)).stream()
                .filter(d -> d.documentType().equals(documentType))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + documentType + " on the expiry list"));

        // Negative days rather than clamped at zero: "expired 40 days ago" and
        // "expires today" are different situations, and flattening both to
        // nought loses the one that needs escalating.
        assertThat(document.daysToExpiry()).isEqualTo(-daysOverdue);
        assertThat(document.expired()).isTrue();
    }

    @Then("the partner listing shows {string} as {string}")
    public void partnerListingStatus(String code, String status) {
        assertThat(inTransaction(() -> masterData.listPartners(null, null, null, null)).items())
                .filteredOn(partner -> partner.code().equals(code))
                .singleElement()
                .extracting(com.lms.masterdata.query.PartnerView::status)
                .isEqualTo(status);
    }

    @Then("the partner listing filtered to {string} shows {int} partner(s)")
    public void partnerListingFiltered(String partnerType, int expected) {
        assertThat(inTransaction(() ->
                masterData.listPartners(partnerType, null, null, null)).items())
                .hasSize(expected);
    }

    private VehicleView vehicleRow(String registration) {
        return inTransaction(() -> masterData.findVehicle(vehicleIds.get(registration)))
                .orElseThrow(() -> new AssertionError("No vehicle " + registration));
    }

    @Then("the transition is refused")
    public void transitionRefused() {
        assertThat(failure).isInstanceOf(BusinessRuleViolationException.class);
        assertThat(((BusinessRuleViolationException) failure).code())
                .isEqualTo("partner-illegal-transition");
    }

    // -------------------------------------------------------------- helpers

    private void registerVehicle(String registration, int gross, int tare, int volume, UUID ownerId) {
        failure = null;
        try {
            vehicleIds.put(registration, fleet.registerVehicle(worldOrgUnit(), ownerId, registration,
                    "TRUCK", "RIGID", "2-AXLE",
                    BigDecimal.valueOf(gross), BigDecimal.valueOf(tare), BigDecimal.valueOf(volume),
                    false, false));
        } catch (BusinessRuleViolationException | IllegalArgumentException e) {
            failure = e;
        }
    }

    private BusinessPartner loadPartner(String code) {
        // Read through a transaction: the tenant scope reaches the connection
        // when a transaction starts, so an unscoped read would see nothing.
        return inTransaction(() -> partnerRepository.findById(partnerIds.get(code)).orElseThrow());
    }

    @Autowired
    private com.lms.masterdata.command.BusinessPartnerRepository partnerRepository;

    private UUID worldOrgUnit() {
        return world.orgUnitId();
    }

    @Autowired
    private ScenarioWorld world;

    private <T> T inTransaction(java.util.function.Supplier<T> action) {
        TenantContext.requireTenantId();
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }
}
