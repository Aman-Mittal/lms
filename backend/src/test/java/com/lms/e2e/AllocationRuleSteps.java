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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.client.api.AllocationRulesApi;
import com.lms.client.api.OrdersApi;
import com.lms.client.api.PlanningApi;
import com.lms.client.api.SourcingApi;
import com.lms.client.model.AddOrderLineRequest;
import com.lms.client.model.AddVendorRequest;
import com.lms.client.model.AllocateRequest;
import com.lms.client.model.Allocation;
import com.lms.client.model.AllocationEvaluation;
import com.lms.client.model.AllocationEvaluationRequest;
import com.lms.client.model.AllocationRuleAction;
import com.lms.client.model.AllocationRuleRequest;
import com.lms.client.model.AssignConsignmentRequest;
import com.lms.client.model.DefineGuideRequest;
import com.lms.client.model.IdResponse;
import com.lms.client.model.Offer;
import com.lms.client.model.OpenLoadRequest;
import com.lms.client.model.RaiseOrderRequest;
import com.lms.client.model.RegisterPartnerRequest;
import com.lms.client.model.RegisterPointRadiusTerminalRequest;
import com.lms.client.model.VendorResponseRequest;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Value;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Allocation rules over HTTP, against a real planned load and a real routing
 * guide of three vendors.
 *
 * <p>Every step text carries "allocation-rule" because Cucumber glue is global
 * across the package and a phrase may exist only once. Each rule is written
 * with its scenario's own lane as a condition, so a rule from one scenario can
 * never fire for another's load: the demo company is shared by all of them.
 * The geography stays near 76 degrees north.
 */
public class AllocationRuleSteps {

    private static final double LON = 12.50;

    @Value("${local.server.port}")
    private int port;

    private HttpWorld world;
    private UUID customer;
    private UUID consignee;
    private UUID origin;
    private UUID destination;
    private UUID lorry;
    private final Map<String, UUID> vendors = new HashMap<>();
    private final Map<UUID, String> vendorLabels = new HashMap<>();
    private final Map<String, UUID> ruleIds = new HashMap<>();
    private final Map<String, AllocationRuleRequest> ruleRequests = new HashMap<>();
    private int loadsPlanned;

    private UUID loadId;
    private UUID allocationId;
    private AllocationEvaluation evaluation;

    private String fullName(String ruleName) {
        return ruleName + " " + world.salt;
    }

    @Given("the demo administrator is signed in at the allocation-rule desk")
    public void signIn() {
        world = new HttpWorld(port);
        world.signInAsDemoAdministrator();
    }

    @Given("allocation-rule vendors {string}, {string} and {string} are ranked 1, 2 and 3 on a lane served by a lorry")
    public void vendorsAndLane(String first, String second, String third) {
        customer = world.partner("ARC", RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER);
        consignee = world.partner("ARN", RegisterPartnerRequest.PartnerTypeEnum.CONSIGNEE);
        origin = world.terminal(RegisterPointRadiusTerminalRequest.CategoryEnum.CUSTOMER_SITE,
                "AR-ORIGIN", 76.10, LON);
        destination = world.terminal(RegisterPointRadiusTerminalRequest.CategoryEnum.CUSTOMER_SITE,
                "AR-DEST", 76.30, LON + 0.20);
        lorry = world.lorry("AR", 20000);

        SourcingApi sourcing = world.api(SourcingApi.class);
        UUID guideId = sourcing.defineRoutingGuide(new DefineGuideRequest()
                .orgUnitId(world.orgUnitId())
                .originTerminalId(origin)
                .destinationTerminalId(destination)
                .vehicleType(HttpWorld.VEHICLE_TYPE), null).getId();
        int rank = 1;
        for (String label : List.of(first, second, third)) {
            UUID vendor = world.activeVendor("AR" + label);
            vendors.put(label, vendor);
            vendorLabels.put(vendor, label);
            sourcing.addRoutingGuideVendor(guideId, new AddVendorRequest()
                    .vendorPartnerId(vendor).rankNo(rank++).responseSlaMinutes(60)
                    .agreedRate(new BigDecimal("20000")), null);
        }
    }

    // ---------------------------------------------------------------- rules

    @Given("the allocation-rule rule {string} exists:")
    public void ruleExists(String name, DataTable table) {
        Map<String, String> row = table.asMaps().get(0);
        AllocationRuleRequest request = new AllocationRuleRequest()
                .name(fullName(name))
                .priority(Integer.valueOf(row.get("priority")))
                .action(AllocationRuleAction.valueOf(row.get("action")))
                .originTerminalId(origin)
                .destinationTerminalId(destination)
                .vendorPartnerIds(Arrays.stream(row.get("vendors").split(","))
                        .map(label -> vendors.get(label.strip())).toList());
        if (present(row, "minWeightKg")) {
            request.minWeightKg(new BigDecimal(row.get("minWeightKg")));
        }
        if (present(row, "maxWeightKg")) {
            request.maxWeightKg(new BigDecimal(row.get("maxWeightKg")));
        }
        if (present(row, "materialClass")) {
            request.materialClass(AllocationRuleRequest.MaterialClassEnum.fromValue(row.get("materialClass")));
        }
        UUID id = world.api(AllocationRulesApi.class).createAllocationRule(request, null).getId();
        ruleIds.put(name, id);
        ruleRequests.put(name, request);
    }

    private static boolean present(Map<String, String> row, String column) {
        String value = row.get(column);
        return value != null && !value.isBlank();
    }

    @Given("the allocation-rule rule {string} is retired")
    public void retire(String name) {
        AllocationRuleRequest request = ruleRequests.get(name).active(false);
        world.api(AllocationRulesApi.class).reviseAllocationRule(ruleIds.get(name), request, null);
    }

    // ----------------------------------------------------------------- load

    @Given("an allocation-rule load of {int} kg of {word} goods is planned")
    public void loadPlanned(int weightKg, String materialClass) {
        int n = ++loadsPlanned;
        OrdersApi orders = world.api(OrdersApi.class);
        UUID orderId = orders.raiseOrder(new RaiseOrderRequest()
                .orgUnitId(world.orgUnitId())
                .customerPartnerId(customer)
                .orderNo(world.code("SO-AR" + n))
                .originTerminalId(origin)
                .requestedPickupAt(OffsetDateTime.now(ZoneOffset.UTC).plusHours(4))
                .source(RaiseOrderRequest.SourceEnum.API), null).getId();
        orders.addOrderLine(orderId, new AddOrderLineRequest()
                .lineNo(1)
                .materialCode("GOODS")
                .materialDescription("Goods")
                .materialClass(materialClass)
                .quantity(new BigDecimal("10"))
                .uom("CASE")
                .deadWeightKg(BigDecimal.valueOf(weightKg))
                .consigneePartnerId(consignee)
                .destinationTerminalId(destination), null);
        orders.validateOrder(orderId, null);

        PlanningApi planning = world.api(PlanningApi.class);
        List<IdResponse> generated = planning.generateConsignments(orderId, null);
        loadId = planning.openLoad(new OpenLoadRequest()
                .orgUnitId(world.orgUnitId())
                .loadNo(world.code("LD-AR" + n))
                .originTerminalId(origin)
                .vehicleId(lorry), null).getId();
        planning.assignConsignment(loadId,
                new AssignConsignmentRequest().consignmentId(generated.get(0).getId()), null);
        planning.planLoad(loadId, null);
    }

    // ------------------------------------------------------------ offering

    @When("the allocation-rule load is offered")
    public void offered() {
        allocationId = world.api(SourcingApi.class).allocateLoad(new AllocateRequest().loadId(loadId), null).getId();
    }

    @When("{string} rejects the allocation-rule offer")
    public void rejects(String label) {
        world.api(SourcingApi.class).rejectOffer(allocationId,
                new VendorResponseRequest().vendorPartnerId(vendors.get(label)), null);
    }

    @When("the allocation-rule dry run is asked for the load")
    public void dryRun() {
        evaluation = world.api(AllocationRulesApi.class)
                .evaluateAllocationRules(new AllocationEvaluationRequest().loadId(loadId));
    }

    // ------------------------------------------------------------- answers

    @Then("the allocation-rule dry run names no rule and offers first to {string}")
    public void dryRunNoRule(String label) {
        assertThat(evaluation.getMatchedRuleId()).isNull();
        assertThat(evaluation.getMatchedRuleName()).isNull();
        assertThat(evaluation.getNextVendor().getVendorPartnerId()).isEqualTo(vendors.get(label));
    }

    @Then("the allocation-rule dry run names rule {string} and offers first to {string}")
    public void dryRunRule(String ruleName, String label) {
        assertThat(evaluation.getMatchedRuleName()).isEqualTo(fullName(ruleName));
        assertThat(evaluation.getMatchedRuleId()).isEqualTo(ruleIds.get(ruleName));
        assertThat(evaluation.getNextVendor().getVendorPartnerId()).isEqualTo(vendors.get(label));
        assertThat(evaluation.getCandidateOrder()).isNotEmpty();
    }

    @Then("the allocation-rule offer is with {string}")
    public void offerIsWith(String label) {
        Allocation allocation = world.api(SourcingApi.class).getAllocation(allocationId);
        assertThat(allocation.getStatus()).isEqualTo("OFFERED");
        List<Offer> offers = world.api(SourcingApi.class).listOffers(allocationId);
        // Offers are listed by rank, which is not the order they were made in.
        assertThat(offers).filteredOn(o -> o.getOutcome() == Offer.OutcomeEnum.PENDING)
                .extracting(Offer::getVendorPartnerId).containsExactly(vendors.get(label));
        assertThat(allocation.getPendingVendorName()).startsWith("AR" + label + " ");
    }

    @Then("the allocation-rule allocation records rule {string} as {word}")
    public void recordsRule(String ruleName, String action) {
        Allocation allocation = world.api(SourcingApi.class).getAllocation(allocationId);
        assertThat(allocation.getAppliedRuleName()).isEqualTo(fullName(ruleName));
        assertThat(allocation.getAppliedRuleId()).isEqualTo(ruleIds.get(ruleName));
        assertThat(allocation.getAppliedRuleAction()).isEqualTo(AllocationRuleAction.valueOf(action));
    }

    @Then("the allocation-rule allocation records that no rule decided it")
    public void recordsNoRule() {
        Allocation allocation = world.api(SourcingApi.class).getAllocation(allocationId);
        assertThat(allocation.getAppliedRuleId()).isNull();
        assertThat(allocation.getAppliedRuleName()).isNull();
        assertThat(allocation.getAppliedRuleAction()).isNull();
    }

    @Then("the allocation-rule allocation is {word}")
    public void allocationIs(String status) {
        assertThat(world.api(SourcingApi.class).getAllocation(allocationId).getStatus()).isEqualTo(status);
    }

    @Then("alpha was never offered the allocation-rule load")
    public void alphaNeverAsked() {
        assertThat(world.api(SourcingApi.class).listOffers(allocationId))
                .extracting(Offer::getVendorPartnerId).doesNotContain(vendors.get("ALPHA"));
    }
}
