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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import com.lms.masterdata.command.FleetCommandService;
import com.lms.masterdata.command.domain.BusinessPartner;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.tenant.TenantContext;
import com.lms.sourcing.command.AllocationOfferRepository;
import com.lms.sourcing.command.AllocationRepository;
import com.lms.sourcing.command.AllocationService;
import com.lms.sourcing.command.AllocationTimeoutSweeper;
import com.lms.sourcing.command.RoutingGuideEntryRepository;
import com.lms.sourcing.command.RoutingGuideRepository;
import com.lms.sourcing.command.VendorAwardTally;
import com.lms.sourcing.command.domain.Allocation;
import com.lms.sourcing.command.domain.AllocationOffer;
import com.lms.sourcing.command.domain.RoutingGuide;
import com.lms.sourcing.command.domain.RoutingGuideEntry;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Step definitions for {@code sourcing.feature}. */
public class SourcingSteps {

    private static final Map<String, Integer> ORDINALS =
            Map.of("first", 1, "second", 2, "third", 3, "fourth", 4);

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private SpineFixtures fixtures;
    @Autowired
    private FleetCommandService fleet;
    @Autowired
    private AllocationService allocationService;
    @Autowired
    private AllocationTimeoutSweeper sweeper;
    @Autowired
    private RoutingGuideRepository guides;
    @Autowired
    private RoutingGuideEntryRepository guideEntries;
    @Autowired
    private AllocationRepository allocations;
    @Autowired
    private AllocationOfferRepository offers;
    @Autowired
    private VendorAwardTally tally;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID guideId;
    private UUID allocationId;
    private RuntimeException failure;

    // ------------------------------------------------------------------ given

    /**
     * A vendor that may actually be given work.
     *
     * <p>Walked all the way to ACTIVE, because a partner is registered as DRAFT
     * and 3.2.1 forbids giving work to anything that is not ACTIVE. A carrier
     * left in DRAFT would be silently skipped by every allocation, and the
     * scenario would fail for a reason that has nothing to do with sourcing.
     */
    @Given("a carrier {string}")
    public void aCarrier(String code) {
        UUID id = fleet.registerPartner(world.orgUnitId(), world.uniqueCode(code),
                code + " Transport", BusinessPartner.PartnerType.VENDOR, "TAX-" + code);
        fleet.transitionPartner(id, BusinessPartner.PartnerStatus.PENDING_VERIFICATION);
        fleet.transitionPartner(id, BusinessPartner.PartnerStatus.ACTIVE);
        world.putRef(code, id);
    }

    @Given("carrier {string} is blacklisted")
    public void carrierBlacklisted(String code) {
        fleet.transitionPartner(world.ref(code), BusinessPartner.PartnerStatus.BLACKLISTED);
    }

    @Given("a load {string} ready for sourcing from {string} to {string} at {string} on lorry {string}")
    public void aSourceableLoad(String loadNo, String originCode, String consigneeCode,
                                String destinationCode, String registration) {
        world.putRef("load:" + loadNo, fixtures.plannedLoad(world.orgUnitId(),
                world.uniqueCode(loadNo), world.ref("CUST"), world.ref(originCode),
                world.ref(consigneeCode), world.ref(destinationCode),
                world.ref("vehicle:" + registration), new BigDecimal("5000"), false));
    }

    @Given("a load {string} is left in draft from {string} on lorry {string}")
    public void aDraftLoad(String loadNo, String originCode, String registration) {
        world.putRef("load:" + loadNo, fixtures.draftLoad(world.orgUnitId(),
                world.uniqueCode(loadNo), world.ref("CUST"), world.ref(originCode),
                world.ref("CONS-A"), world.ref("DEST-A"),
                world.ref("vehicle:" + registration), new BigDecimal("1000"), false));
    }

    @Given("a routing guide from {string} to {string} for {string}")
    public void aContractualGuide(String originCode, String destinationCode, String vehicleType) {
        defineGuide(originCode, destinationCode, vehicleType, RoutingGuide.Strategy.CONTRACTUAL);
    }

    @Given("a round-robin routing guide from {string} to {string} for {string}")
    public void aRoundRobinGuide(String originCode, String destinationCode, String vehicleType) {
        defineGuide(originCode, destinationCode, vehicleType, RoutingGuide.Strategy.ROUND_ROBIN);
    }

    @Given("the guide ranks {string} {word} with a {int} minute SLA")
    public void guideRanks(String vendorCode, String ordinal, int slaMinutes) {
        int rank = ORDINALS.get(ordinal);
        inTransaction(() -> guideEntries.save(RoutingGuideEntry.at(UUID.randomUUID(),
                TenantContext.requireTenantId(), guideId, rank, world.ref(vendorCode),
                slaMinutes, new BigDecimal("10000"))));
    }

    @Given("{string} has already been awarded {int} loads this month")
    public void vendorAlreadyAwarded(String vendorCode, int count) {
        UUID vendorId = world.ref(vendorCode);
        inTransaction(() -> {
            for (int i = 0; i < count; i++) {
                tally.recordAward(TenantContext.requireTenantId(), guideId, vendorId,
                        LocalDate.now(ZoneOffset.UTC));
            }
            return null;
        });
    }

    /**
     * Winds the offer's deadline into the past.
     *
     * <p>Moving the data rather than the clock. A response SLA has a positive
     * minimum -- a zero-minute SLA would lapse in the same transaction that
     * created it -- so the only honest way to test the sweeper without sleeping
     * for an hour is to backdate the deadline it reads.
     */
    @Given("the deadline for the offer to {string} has passed")
    public void deadlineHasPassed(String vendorCode) {
        UUID vendorId = world.ref(vendorCode);
        inTransaction(() -> jdbc.sql("""
                        UPDATE allocation_offer
                           SET responds_by = :past
                         WHERE allocation_id = :allocationId
                           AND vendor_partner_id = :vendorId
                           AND outcome = 'PENDING'
                        """)
                // OffsetDateTime, not Instant. The PostgreSQL driver refuses
                // to infer a SQL type for Instant and fails with "Can't infer
                // the SQL type to use" -- Spring Data's converters handle it on
                // the mapping path, but a raw JdbcClient statement has none.
                .param("past", Instant.now().minusSeconds(600).atOffset(ZoneOffset.UTC))
                .param("allocationId", allocationId)
                .param("vendorId", vendorId)
                .update());
    }

    // ------------------------------------------------------------------- when

    @When("load {string} is allocated")
    public void allocateLoad(String loadNo) {
        attempt(() -> {
            allocationId = allocationService.allocate(world.ref("load:" + loadNo));
            return allocationId;
        });
    }

    @When("{string} accepts the offer")
    public void vendorAccepts(String vendorCode) {
        attempt(() -> {
            allocationService.accept(allocationId, world.ref(vendorCode));
            return null;
        });
    }

    @When("{string} rejects the offer")
    public void vendorRejects(String vendorCode) {
        attempt(() -> {
            allocationService.reject(allocationId, world.ref(vendorCode));
            return null;
        });
    }

    /**
     * Runs the sweep directly rather than waiting for the scheduler.
     *
     * <p>Deliberately the real component, including its per-tenant iteration.
     * A test that called {@code AllocationService.timeOut} straight would pass
     * even if the sweeper found nothing -- which is exactly the failure mode
     * row-level security produces for unscoped scheduled jobs, and exactly what
     * this scenario exists to catch.
     */
    @When("lapsed offers are swept")
    public void sweepLapsedOffers() {
        // The sweeper binds its own scope per tenant, so the scenario's scope is
        // cleared first to prove it does not lean on an ambient one.
        TenantContext.Scope scope = TenantContext.current().orElseThrow();
        TenantContext.clear();
        try {
            sweeper.sweepLapsedOffers();
        } finally {
            TenantContext.set(scope.tenantId(), scope.orgPath());
        }
    }

    // ------------------------------------------------------------------- then

    @Then("the offer is with {string} at rank {int}")
    public void offerIsWith(String vendorCode, int rank) {
        AllocationOffer pending = pendingOffer();
        assertThat(pending.vendorPartnerId()).isEqualTo(world.ref(vendorCode));
        assertThat(pending.rankNo()).isEqualTo(rank);
    }

    @Then("the allocation is {string}")
    public void allocationIs(String expected) {
        assertThat(allocation().status().name()).isEqualTo(expected);
    }

    @Then("the allocation is {string} to {string}")
    public void allocationIsAwardedTo(String expected, String vendorCode) {
        Allocation current = allocation();
        assertThat(current.status().name()).isEqualTo(expected);
        assertThat(current.awardedVendorPartnerId()).isEqualTo(world.ref(vendorCode));
    }

    @Then("the offer to {string} is recorded as {string}")
    public void offerRecordedAs(String vendorCode, String expected) {
        UUID vendorId = world.ref(vendorCode);
        AllocationOffer offer = history().stream()
                .filter(o -> o.vendorPartnerId().equals(vendorId))
                .findFirst()
                .orElseThrow(() -> new AssertionError(vendorCode + " was never offered the load"));
        assertThat(offer.outcome().name()).isEqualTo(expected);
    }

    @Then("{int} offers were made")
    public void offersMade(int expected) {
        assertThat(history()).hasSize(expected);
    }

    @Then("allocation is refused mentioning {string}")
    public void allocationRefused(String fragment) {
        assertRefusal(fragment);
    }

    @Then("the response is refused mentioning {string}")
    public void responseRefused(String fragment) {
        assertRefusal(fragment);
    }

    // ---------------------------------------------------------------- helpers

    private void defineGuide(String originCode, String destinationCode, String vehicleType,
                             RoutingGuide.Strategy strategy) {
        guideId = inTransaction(() -> guides.save(RoutingGuide.define(UUID.randomUUID(),
                TenantContext.requireTenantId(), world.orgUnitId(),
                world.ref(originCode), world.ref(destinationCode), vehicleType, strategy)).id());
    }

    private Allocation allocation() {
        return inTransaction(() -> allocations.findById(allocationId).orElseThrow());
    }

    private List<AllocationOffer> history() {
        return inTransaction(() ->
                offers.findByAllocation(TenantContext.requireTenantId(), allocationId));
    }

    private AllocationOffer pendingOffer() {
        return inTransaction(() -> offers.findPending(TenantContext.requireTenantId(), allocationId))
                .orElseThrow(() -> new AssertionError("No offer is outstanding on this allocation"));
    }

    private void assertRefusal(String fragment) {
        assertThat(failure)
                .as("expected a refusal mentioning '%s' but the operation succeeded", fragment)
                .isInstanceOf(BusinessRuleViolationException.class);
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
        TenantContext.requireTenantId();
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }
}
