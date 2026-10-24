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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.lms.client.api.MultimodalApi;
import com.lms.client.model.AuthResult;
import com.lms.client.model.ItinerarySlice;
import com.lms.client.model.ItinerarySummary;
import com.lms.client.model.PlanItineraryLegRequest;
import com.lms.client.model.PlanItineraryRequest;
import com.lms.client.model.PublishRailTariffRequest;
import com.lms.client.model.RailMilestoneRequest;
import com.lms.client.model.RailTariff;
import com.lms.client.model.RailTariffSlice;
import com.lms.client.model.RegisterPartnerRequest;
import com.lms.client.model.RegisterPointRadiusTerminalRequest;
import feign.FeignException;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Rail rate cards and the itinerary listing over HTTP: a card is published, a
 * successor closes it the day before it starts, rail shipments are priced from
 * whichever card was in force on their departure day, and the list of
 * itineraries shows each one's derived status.
 *
 * <p>The itineraries are rail-only (a single leg between two railheads), which
 * is the smallest shipment the API accepts without trips, so the scenario
 * stays about the cards and the listing. Latitude band 57.
 *
 * <p>The listing has no status filter in the contract (only a cursor and a
 * limit), so the status assertions read it page by page and pick out their own
 * shipments.
 */
public class RailTariffReadSteps {

    private static final int WAGON_V1 = 500;

    @Value("${local.server.port}")
    private int port;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private HttpWorld world;
    private MovementSupport support;
    private OffsetDateTime now;
    private LocalDate today;
    private UUID railheadA;
    private UUID railheadB;
    private UUID site;
    private UUID carrier;
    private UUID dormantCarrier;
    private UUID firstCard;
    private UUID secondCard;
    private String firstCode;
    private String secondCode;
    private UUID plannedShipment;
    private UUID underwayShipment;
    private UUID completedShipment;
    private AuthResult otherTenant;

    @Given("the demo administrator is signed in at the rail rate card desk")
    public void signIn() {
        world = new HttpWorld(port);
        world.signInAsDemoAdministrator();
        support = new MovementSupport(world, port, jdbc, passwordEncoder, transactionManager);
        now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
        today = now.toLocalDate();
    }

    @Given("two railheads, a plain site, an active rail carrier and a dormant one are registered for the rate cards")
    public void network() {
        railheadA = world.terminal(RegisterPointRadiusTerminalRequest.CategoryEnum.RAILHEAD,
                "RT-HEAD-A", 57.10, 72.90);
        railheadB = world.terminal(RegisterPointRadiusTerminalRequest.CategoryEnum.RAILHEAD,
                "RT-HEAD-B", 57.50, 73.50);
        site = world.terminal(RegisterPointRadiusTerminalRequest.CategoryEnum.CUSTOMER_SITE,
                "RT-SITE", 57.20, 73.00);
        carrier = world.activeVendor("RTC");
        dormantCarrier = world.partner("RTD", RegisterPartnerRequest.PartnerTypeEnum.VENDOR);
    }

    // ------------------------------------------------------------- the cards

    @When("the rail carrier publishes a rate card of {int} per wagon and {int} per container between the railheads from ten days ago")
    public void firstCard(int perWagon, int perContainer) {
        firstCode = world.code("RT1");
        firstCard = publish(firstCode, today.minusDays(10), perWagon, perContainer).getId();
    }

    @When("the rail carrier publishes a successor of {int} per wagon from three days ago")
    public void secondCard(int perWagon) {
        secondCode = world.code("RT2");
        secondCard = publish(secondCode, today.minusDays(3), perWagon, perWagon + 100).getId();
    }

    @Then("the first card reads back by id with its lane, rates and no end date")
    public void firstCardReadsBack() {
        RailTariff card = world.api(MultimodalApi.class).getRailTariff(firstCard);
        assertThat(card.getId()).isEqualTo(firstCard);
        assertThat(card.getCode()).isEqualTo(firstCode);
        assertThat(card.getCarrierPartnerId()).isEqualTo(carrier);
        assertThat(card.getCarrierCode()).isEqualTo("RTC-" + world.salt);
        assertThat(card.getOriginTerminalId()).isEqualTo(railheadA);
        assertThat(card.getOriginTerminalCode()).isEqualTo("RT-HEAD-A-" + world.salt);
        assertThat(card.getDestinationTerminalId()).isEqualTo(railheadB);
        assertThat(card.getDestinationTerminalCode()).isEqualTo("RT-HEAD-B-" + world.salt);
        assertThat(card.getCurrency()).isEqualTo("INR");
        assertThat(card.getPerWagonRate()).isEqualByComparingTo(BigDecimal.valueOf(WAGON_V1));
        assertThat(card.getPerContainerRate()).isEqualByComparingTo("700");
        assertThat(card.getMinimumCharge()).isEqualByComparingTo("1000");
        assertThat(card.getEffectiveFrom()).isEqualTo(today.minusDays(10));
        assertThat(card.getEffectiveTo()).isNull();
        assertThat(card.getCreatedBy()).isNotBlank();
        assertThat(card.getCreatedAt()).isNotNull();
    }

    @Then("the successor closed the first card the day before it starts and is itself in force")
    public void successorClosesIncumbent() {
        MultimodalApi rail = world.api(MultimodalApi.class);
        RailTariff first = rail.getRailTariff(firstCard);
        RailTariff second = rail.getRailTariff(secondCard);
        assertThat(first.getEffectiveTo()).isEqualTo(today.minusDays(4));
        assertThat(first.getEffectiveFrom()).isEqualTo(today.minusDays(10));
        assertThat(second.getEffectiveFrom()).isEqualTo(today.minusDays(3));
        assertThat(second.getEffectiveTo()).isNull();
        assertThat(second.getPerWagonRate()).isEqualByComparingTo("600");
        assertThat(second.getPerContainerRate()).isEqualByComparingTo("700");
        assertThat(second.getCode()).isEqualTo(secondCode);
    }

    @Then("the rate card list shows the successor before the first card, each once, and pages by cursor")
    public void listAndPage() {
        MultimodalApi rail = world.api(MultimodalApi.class);
        RailTariffSlice all = rail.listRailTariffs(null, 100);
        List<UUID> ids = all.getItems().stream().map(RailTariff::getId).toList();
        assertThat(ids).containsOnlyOnce(firstCard, secondCard);
        assertThat(ids.indexOf(secondCard)).isLessThan(ids.indexOf(firstCard));
        RailTariff listed = all.getItems().get(ids.indexOf(firstCard));
        assertThat(listed.getEffectiveTo()).isEqualTo(today.minusDays(4));
        assertThat(listed.getCode()).isEqualTo(firstCode);

        // Newest first, one at a time: the two cards were published back to back.
        RailTariffSlice page = rail.listRailTariffs(null, 1);
        assertThat(page.getItems()).extracting(RailTariff::getId).containsExactly(secondCard);
        assertThat(page.getHasMore()).isTrue();
        assertThat(page.getNextCursor()).isNotBlank();
        RailTariffSlice next = rail.listRailTariffs(page.getNextCursor(), 1);
        assertThat(next.getItems()).extracting(RailTariff::getId).containsExactly(firstCard);
    }

    // ------------------------------------------------------------ itineraries

    @When("three rail-only shipments are planned for six days ago, yesterday and thirty hours ago with no cost given")
    public void planShipments() {
        plannedShipment = plan("SHP-A", now.minusDays(6), 40);
        underwayShipment = plan("SHP-B", now.minusDays(1), 10);
        completedShipment = plan("SHP-C", now.minusHours(30), 20);
    }

    @Then("each rail leg was priced from the card in force on its departure day")
    public void pricedFromTheCardInForce() {
        MultimodalApi rail = world.api(MultimodalApi.class);
        var oldest = rail.getItinerary(plannedShipment).getLegs().get(0);
        assertThat(oldest.getRailTariffCode()).isEqualTo(firstCode);
        assertThat(oldest.getRailTariffId()).isEqualTo(firstCard);
        assertThat(oldest.getCostAmount()).isEqualByComparingTo(BigDecimal.valueOf(40L * WAGON_V1));
        var newer = rail.getItinerary(underwayShipment).getLegs().get(0);
        assertThat(newer.getRailTariffCode()).isEqualTo(secondCode);
        assertThat(newer.getCostAmount()).isEqualByComparingTo("6000");
        var newest = rail.getItinerary(completedShipment).getLegs().get(0);
        assertThat(newest.getRailTariffCode()).isEqualTo(secondCode);
        assertThat(newest.getCostAmount()).isEqualByComparingTo("12000");
    }

    @When("the second shipment's train is reported placed and the third is driven to arrival")
    public void moveTrains() {
        MultimodalApi rail = world.api(MultimodalApi.class);
        milestone(rail, underwayShipment, RailMilestoneRequest.MilestoneEnum.PLACED, 20);
        milestone(rail, completedShipment, RailMilestoneRequest.MilestoneEnum.PLACED, 36);
        milestone(rail, completedShipment, RailMilestoneRequest.MilestoneEnum.LOADED, 35);
        milestone(rail, completedShipment, RailMilestoneRequest.MilestoneEnum.DEPARTED, 34);
        milestone(rail, completedShipment, RailMilestoneRequest.MilestoneEnum.ARRIVED, 10);
    }

    @Then("the itinerary list shows the shipments newest first with their derived status, cost and leg count")
    public void itineraryList() {
        List<ItinerarySummary> mine = mySummaries();
        assertThat(mine).extracting(ItinerarySummary::getId)
                .containsExactly(completedShipment, underwayShipment, plannedShipment);

        ItinerarySummary done = mine.get(0);
        ItinerarySummary underway = mine.get(1);
        ItinerarySummary planned = mine.get(2);
        assertThat(planned.getStatus()).isEqualTo(ItinerarySummary.StatusEnum.PLANNED);
        assertThat(underway.getStatus()).isEqualTo(ItinerarySummary.StatusEnum.IN_PROGRESS);
        assertThat(done.getStatus()).isEqualTo(ItinerarySummary.StatusEnum.COMPLETED);
        assertThat(planned.getTotalCost()).isEqualByComparingTo("20000");
        assertThat(underway.getTotalCost()).isEqualByComparingTo("6000");
        assertThat(done.getTotalCost()).isEqualByComparingTo("12000");
        assertThat(mine).extracting(ItinerarySummary::getLegCount).containsOnly(1);
        assertThat(mine).extracting(ItinerarySummary::getCurrency).containsOnly("INR");
        assertThat(mine).extracting(ItinerarySummary::getConnectionMissed).containsOnly(false);
        assertThat(planned.getShipmentRef()).isEqualTo(world.code("SHP-A"));
        assertThat(planned.getItineraryNo()).isNotBlank();
        assertThat(planned.getCreatedAt()).isNotNull();

        // There is no status parameter; picking the completed ones out of the
        // list finds exactly the shipment that arrived.
        assertThat(mine.stream().filter(s -> s.getStatus()
                == ItinerarySummary.StatusEnum.COMPLETED)).extracting(ItinerarySummary::getId)
                .containsExactly(completedShipment);
    }

    @Then("the itinerary list pages by cursor, newest first")
    public void itineraryPaging() {
        MultimodalApi rail = world.api(MultimodalApi.class);
        ItinerarySlice first = rail.listItineraries(null, 1);
        assertThat(first.getItems()).extracting(ItinerarySummary::getId)
                .containsExactly(completedShipment);
        assertThat(first.getHasMore()).isTrue();
        ItinerarySlice second = rail.listItineraries(first.getNextCursor(), 1);
        assertThat(second.getItems()).extracting(ItinerarySummary::getId)
                .containsExactly(underwayShipment);
    }

    @Then("a cursor the listings never issued is refused with a code")
    public void foreignCursor() {
        MultimodalApi rail = world.api(MultimodalApi.class);
        Throwable onItineraries = catchThrowable(() -> rail.listItineraries("not-a-cursor", 5));
        Throwable onCards = catchThrowable(() -> rail.listRailTariffs("not-a-cursor", 5));
        for (Throwable refusal : List.of(onItineraries, onCards)) {
            assertThat(refusal).isInstanceOf(FeignException.class);
            assertThat(((FeignException) refusal).status()).isIn(400, 409);
            assertThat(MovementSupport.problemOf((FeignException) refusal).path("code").asText())
                    .isEqualTo("invalid-cursor");
        }
    }

    // --------------------------------------------------------------- refusals

    @Then("a successor that does not start after the incumbent is refused as backdated and changes nothing")
    public void backdated() {
        MultimodalApi rail = world.api(MultimodalApi.class);
        int before = rail.listRailTariffs(null, 100).getItems().size();
        for (int daysAgo : new int[] {3, 5}) {
            MovementSupport.assertRefused(
                    () -> publish(world.code("RTX"), today.minusDays(daysAgo), 700, 800),
                    409, "rail-tariff-backdated");
        }
        assertThat(rail.getRailTariff(secondCard).getEffectiveTo()).isNull();
        assertThat(rail.getRailTariff(firstCard).getEffectiveTo()).isEqualTo(today.minusDays(4));
        assertThat(rail.listRailTariffs(null, 100).getItems()).hasSize(before);
    }

    @Then("cards that break the rules are refused with their codes")
    public void ruleBreakers() {
        MovementSupport.assertRefused(() -> publish(world.code("RTY"), site, railheadB, carrier,
                today, "800", "900"), 409, "rail-tariff-not-railhead");
        MovementSupport.assertRefused(() -> publish(world.code("RTY"), railheadA, site, carrier,
                today, "800", "900"), 409, "rail-tariff-not-railhead");
        MovementSupport.assertRefused(() -> publish(world.code("RTY"), railheadA, railheadA,
                carrier, today, "800", "900"), 409, "rail-tariff-goes-nowhere");
        MovementSupport.assertRefused(() -> publish(world.code("RTY"), railheadA, railheadB,
                carrier, today, null, null), 409, "rail-tariff-prices-nothing");
        MovementSupport.assertRefused(() -> publish(world.code("RTY"), railheadA, railheadB,
                dormantCarrier, today, "800", "900"), 409, "rail-tariff-carrier-cannot-transact");
        MovementSupport.assertRefused(() -> publish(world.code("RTY"), railheadA, railheadB,
                UUID.randomUUID(), today, "800", "900"), 409, "rail-tariff-carrier-cannot-transact");
        MovementSupport.assertRefused(() -> publish(world.code("RTY"), UUID.randomUUID(),
                railheadB, carrier, today, "800", "900"), 404, "not-found");
    }

    @Then("a card with no name or a negative rate is refused as invalid with a code")
    public void invalidCards() {
        MovementSupport.assertRefused(() -> publish(" ", railheadA, railheadB, carrier, today,
                "800", "900"), 400, "validation-failed");
        MovementSupport.assertRefused(() -> publish(world.code("RTZ"), railheadA, railheadB,
                carrier, today, "-1", "900"), 400, "validation-failed");
        MovementSupport.assertRefused(() -> world.api(MultimodalApi.class).planItinerary(
                new PlanItineraryRequest().shipmentRef(world.code("SHP-BAD")).legs(List.of()),
                null), 400, "validation-failed");
    }

    @Then("a card, a plan or a shipment that is not there is answered as not found")
    public void notFound() {
        MultimodalApi rail = world.api(MultimodalApi.class);
        MovementSupport.assertRefused(() -> rail.getRailTariff(UUID.randomUUID()), 404, "not-found");
        MovementSupport.assertRefused(() -> rail.getItinerary(UUID.randomUUID()), 404, "not-found");
    }

    @Then("callers lacking the permission are refused as forbidden")
    public void forbidden() {
        AuthResult reader = support.callerWith("railreader", "MULTIMODAL_READ");
        AuthResult manager = support.callerWith("railmanager", "TARIFF_MANAGE");
        MovementSupport.assertRefused(() -> publishAs(reader, world.code("RTF")), 403, "access-denied");
        MultimodalApi asManager = support.as(manager, MultimodalApi.class);
        MovementSupport.assertRefused(() -> asManager.getRailTariff(firstCard), 403, "access-denied");
        MovementSupport.assertRefused(() -> asManager.listRailTariffs(null, 5), 403, "access-denied");
        MovementSupport.assertRefused(() -> asManager.listItineraries(null, 5), 403, "access-denied");

        // And the reader, who may read, reads the same card the administrator does.
        assertThat(support.as(reader, MultimodalApi.class).getRailTariff(firstCard).getCode())
                .isEqualTo(firstCode);
    }

    @Then("another tenant's administrator cannot read the cards or the shipments and cannot publish against these railheads")
    public void otherTenant() {
        otherTenant = support.otherTenantAdministrator();
        MultimodalApi theirs = support.as(otherTenant, MultimodalApi.class);
        MovementSupport.assertRefused(() -> theirs.getRailTariff(firstCard), 404, "not-found");
        MovementSupport.assertRefused(() -> theirs.getRailTariff(secondCard), 404, "not-found");
        MovementSupport.assertRefused(() -> theirs.getItinerary(plannedShipment), 404, "not-found");
        assertThat(theirs.listRailTariffs(null, 100).getItems()).extracting(RailTariff::getId)
                .doesNotContain(firstCard, secondCard);
        assertThat(theirs.listItineraries(null, 100).getItems())
                .extracting(ItinerarySummary::getId)
                .doesNotContain(plannedShipment, underwayShipment, completedShipment);
        MovementSupport.assertRefused(() -> publishAs(otherTenant, world.code("RTO")), 404, "not-found");
    }

    // ---------------------------------------------------------------- helpers

    private com.lms.client.model.IdResponse publish(String code, LocalDate from, int perWagon,
                                                    int perContainer) {
        return world.api(MultimodalApi.class).publishRailTariff(new PublishRailTariffRequest()
                .code(code)
                .carrierPartnerId(carrier)
                .originTerminalId(railheadA)
                .destinationTerminalId(railheadB)
                .currency("INR")
                .perWagonRate(BigDecimal.valueOf(perWagon))
                .perContainerRate(BigDecimal.valueOf(perContainer))
                .minimumCharge(new BigDecimal("1000"))
                .effectiveFrom(from), null);
    }

    private void publish(String code, UUID from, UUID to, UUID carrierId, LocalDate effective,
                         String perWagon, String perContainer) {
        world.api(MultimodalApi.class).publishRailTariff(new PublishRailTariffRequest()
                .code(code)
                .carrierPartnerId(carrierId)
                .originTerminalId(from)
                .destinationTerminalId(to)
                .perWagonRate(perWagon == null ? null : new BigDecimal(perWagon))
                .perContainerRate(perContainer == null ? null : new BigDecimal(perContainer))
                .effectiveFrom(effective), null);
    }

    private void publishAs(AuthResult caller, String code) {
        support.as(caller, MultimodalApi.class).publishRailTariff(new PublishRailTariffRequest()
                .code(code)
                .carrierPartnerId(carrier)
                .originTerminalId(railheadA)
                .destinationTerminalId(railheadB)
                .perWagonRate(new BigDecimal("800"))
                .effectiveFrom(today.plusDays(1)), null);
    }

    private UUID plan(String ref, OffsetDateTime departure, int wagons) {
        return world.api(MultimodalApi.class).planItinerary(new PlanItineraryRequest()
                .shipmentRef(world.code(ref))
                .currency("INR")
                .legs(List.of(new PlanItineraryLegRequest()
                        .mode(PlanItineraryLegRequest.ModeEnum.RAIL)
                        .fromTerminalId(railheadA).toTerminalId(railheadB)
                        .plannedDepartureAt(departure)
                        .plannedArrivalAt(departure.plusHours(20))
                        .unitType(PlanItineraryLegRequest.UnitTypeEnum.WAGON)
                        .unitCount(wagons)
                        .bookingRef(world.code("BK-" + ref))
                        .carrierPartnerId(carrier))), null).getId();
    }

    private void milestone(MultimodalApi rail, UUID itinerary,
                           RailMilestoneRequest.MilestoneEnum milestone, int hoursAgo) {
        rail.recordRailMilestone(itinerary, 1, new RailMilestoneRequest()
                .milestone(milestone).at(now.minusHours(hoursAgo)), null);
    }

    /** This scenario's three shipments, as the list shows them, newest first. */
    private List<ItinerarySummary> mySummaries() {
        List<ItinerarySummary> found = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 20 && found.size() < 3; page++) {
            ItinerarySlice slice = world.api(MultimodalApi.class).listItineraries(cursor, 100);
            slice.getItems().stream()
                    .filter(s -> List.of(plannedShipment, underwayShipment, completedShipment)
                            .contains(s.getId()))
                    .forEach(found::add);
            if (!Boolean.TRUE.equals(slice.getHasMore())) {
                break;
            }
            cursor = slice.getNextCursor();
        }
        return found;
    }
}
