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
import java.util.UUID;

import com.lms.masterdata.command.MasterDataMaintenanceService;
import com.lms.masterdata.command.domain.BusinessPartner;
import com.lms.masterdata.query.DriverView;
import com.lms.masterdata.query.MasterDataQueryService;
import com.lms.masterdata.query.PartnerView;
import com.lms.masterdata.query.VehicleView;
import com.lms.shared.tenant.TenantContext;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Step definitions for {@code master_data_crud.feature} and the inactive-asset scenarios of the execution feature. */
public class MasterDataCrudSteps {

    @Autowired
    private MasterDataMaintenanceService maintenance;
    @Autowired
    private MasterDataQueryService masterData;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ScenarioWorld world;

    private RuntimeException failure;

    // -------------------------------------------------------------- vehicles

    @When("lorry {string} is deactivated because {string}")
    public void deactivateLorry(String registration, String reason) {
        VehicleView vehicle = vehicle(registration);
        inTransaction(() -> {
            maintenance.deactivateVehicle(vehicle.id(), null, reason);
            return null;
        });
    }

    @When("lorry {string} is reactivated because {string}")
    public void reactivateLorry(String registration, String reason) {
        VehicleView vehicle = vehicle(registration);
        inTransaction(() -> {
            maintenance.reactivateVehicle(vehicle.id(), null, reason);
            return null;
        });
    }

    @When("lorry {string} is edited to {int} kg gross")
    public void editLorry(String registration, int gross) {
        attempt(() -> updateVehicle(vehicle(registration), vehicle(registration).version(), gross));
    }

    @When("lorry {string} is edited to {int} kg gross from the version before the last change")
    public void editLorryStale(String registration, int gross) {
        attempt(() -> updateVehicle(vehicle(registration), vehicle(registration).version() - 1, gross));
    }

    @When("lorry {string} is deactivated without a reason")
    public void deactivateWithoutReason(String registration) {
        VehicleView vehicle = vehicle(registration);
        attempt(() -> maintenance.deactivateVehicle(vehicle.id(), null, "  "));
    }

    @Then("lorry {string} is {string} and its gross weight is {int} kg")
    public void lorryIs(String registration, String status, int gross) {
        VehicleView vehicle = vehicle(registration);
        assertThat(vehicle.status()).isEqualTo(status);
        assertThat(vehicle.grossWeightKg()).isEqualByComparingTo(BigDecimal.valueOf(gross));
    }

    @Then("the default vehicle list no longer shows {string} but the full list does")
    public void listsByStatus(String registration) {
        assertThat(inTransaction(() -> masterData.listVehicles(null, null, 200, registration)).items())
                .as("the default list is the active vehicles").isEmpty();
        assertThat(inTransaction(() -> masterData.listVehicles("ALL", null, 200, registration)).items())
                .hasSize(1);
        assertThat(inTransaction(() -> masterData.listVehicles("INACTIVE", null, 200, registration)).items())
                .hasSize(1);
    }

    @Then("the vehicle audit trail has a {string} entry for {string}")
    public void vehicleAudit(String action, String registration) {
        UUID id = vehicle(registration).id();
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = :a AND resource_id = :id")
                .param("a", action).param("id", id).query(Long.class).single()).isOne();
    }

    // --------------------------------------------------------------- drivers

    @When("driver {string} is deactivated because {string}")
    public void deactivateDriver(String licence, String reason) {
        DriverView driver = driver(licence);
        inTransaction(() -> {
            maintenance.deactivateDriver(driver.id(), null, reason);
            return null;
        });
    }

    @When("driver {string} is reactivated because {string}")
    public void reactivateDriver(String licence, String reason) {
        DriverView driver = driver(licence);
        inTransaction(() -> {
            maintenance.reactivateDriver(driver.id(), null, reason);
            return null;
        });
    }

    @Then("driver {string} is {string}")
    public void driverIs(String licence, String status) {
        assertThat(driver(licence).status()).isEqualTo(status);
    }

    // -------------------------------------------------------------- partners

    @When("partner {string} is suspended without a reason")
    public void suspendWithoutReason(String code) {
        PartnerView partner = partner(code);
        attempt(() -> maintenance.transitionPartner(partner.id(), BusinessPartner.PartnerStatus.SUSPENDED, "", null));
    }

    @When("partner {string} is suspended because {string}")
    public void suspendBecause(String code, String reason) {
        PartnerView partner = partner(code);
        inTransaction(() -> {
            maintenance.transitionPartner(partner.id(), BusinessPartner.PartnerStatus.SUSPENDED, reason, partner.version());
            return null;
        });
    }

    @Then("partner {string} is {string}")
    public void partnerIs(String code, String status) {
        assertThat(partner(code).status()).isEqualTo(status);
    }

    @Then("the default partner list no longer shows {string}")
    public void partnerListed(String code) {
        assertThat(inTransaction(() -> masterData.listPartners(null, null, null, 200, code)).items()).isEmpty();
        assertThat(inTransaction(() -> masterData.listPartners(null, "ALL", null, 200, code)).items()).hasSize(1);
    }

    // ----------------------------------------------------------------- shared

    @Then("the edit is refused as stale")
    public void refusedAsStale() {
        assertThat(failure).isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Then("the change is refused for want of a reason")
    public void refusedForReason() {
        assertThat(failure).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reason");
    }

    @Then("the change succeeded")
    public void succeeded() {
        assertThat(failure).isNull();
    }

    @Then("the audit trail records reasons for {string}")
    public void auditReason(String action) {
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = :a AND after_state ->> 'reason' IS NOT NULL")
                .param("a", action).query(Long.class).single()).isPositive();
    }

    private void updateVehicle(VehicleView v, long version, int gross) {
        inTransaction(() -> {
            maintenance.updateVehicle(v.id(), version, v.ownerPartnerId(), v.category(), v.vehicleType(),
                    v.axleConfig(), BigDecimal.valueOf(gross), v.tareWeightKg(), v.maxVolumeM3(),
                    v.hazmatCertified(), v.reeferCapable());
            return null;
        });
    }

    private void attempt(Runnable action) {
        failure = null;
        try {
            action.run();
        } catch (RuntimeException e) {
            failure = e;
        }
    }

    /** By the name another feature gave it, else by registration. */
    private VehicleView vehicle(String registration) {
        UUID known = known("vehicle:" + registration);
        if (known != null) {
            return inTransaction(() -> masterData.findVehicle(known)).orElseThrow();
        }
        return inTransaction(() -> masterData.listVehicles("ALL", null, 5, registration.toLowerCase()).items())
                .stream().filter(v -> v.registrationNo().equals(registration)).findFirst().orElseThrow();
    }

    /** By the name another feature gave it, else by licence number. */
    private DriverView driver(String nameOrLicence) {
        UUID known = known("driver:" + nameOrLicence);
        if (known != null) {
            return inTransaction(() -> masterData.findDriver(known)).orElseThrow();
        }
        return inTransaction(() -> masterData.listDrivers("ALL", null, 5, nameOrLicence.toLowerCase()).items())
                .stream().filter(d -> d.licenceNo().equals(nameOrLicence)).findFirst().orElseThrow();
    }

    private UUID known(String key) {
        try {
            return world.ref(key);
        } catch (IllegalStateException unknown) {
            return null;
        }
    }

    private PartnerView partner(String code) {
        return inTransaction(() -> masterData.findPartnerByCode(code)).orElseThrow();
    }

    private <T> T inTransaction(java.util.function.Supplier<T> action) {
        TenantContext.requireTenantId();
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }
}
