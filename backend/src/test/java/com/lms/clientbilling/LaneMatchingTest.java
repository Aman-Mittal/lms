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
package com.lms.clientbilling;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.lms.clientbilling.command.domain.ContractLane;
import com.lms.clientbilling.command.domain.LaneMatching;
import com.lms.shared.error.BusinessRuleViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which lane an order travels on and which refusal it earns when none does,
 * without a database.
 */
class LaneMatchingTest {

    private static final UUID MUMBAI = UUID.randomUUID();
    private static final UUID DELHI = UUID.randomUUID();
    private static final UUID PUNE = UUID.randomUUID();
    private static final UUID ACME = UUID.randomUUID();
    private static final UUID GLOBEX = UUID.randomUUID();

    private static ContractLane lane(String code, ContractLane.Place from, ContractLane.Place to, String mode,
                                     String loadType, Set<UUID> consignees) {
        return new ContractLane(UUID.randomUUID(), UUID.randomUUID(), code, from, to, mode, loadType,
                ContractLane.RateBasis.PER_KG, new BigDecimal("5"), null, null, null, consignees);
    }

    private static LaneMatching.Site site(UUID terminal, String zone) {
        return new LaneMatching.Site(terminal, zone);
    }

    private static LaneMatching.Order order(String mode, String loadType, LaneMatching.Leg... legs) {
        return new LaneMatching.Order(site(MUMBAI, "WEST"), mode, loadType, List.of(legs));
    }

    private static LaneMatching.Leg to(UUID terminal, String zone, UUID consignee) {
        return new LaneMatching.Leg(site(terminal, zone), consignee);
    }

    @Test
    @DisplayName("a lane serves the order whose origin, destination, mode and load type it covers")
    void servesTheOrder() {
        ContractLane lane = lane("MUM-DEL", ContractLane.Place.terminal(MUMBAI), ContractLane.Place.terminal(DELHI),
                "FTL", "32FT", Set.of());
        assertThat(LaneMatching.match(List.of(lane), order("FTL", "32FT", to(DELHI, null, ACME))).lane())
                .isSameAs(lane);
        // Unstated mode and load type are not held against the lane.
        assertThat(LaneMatching.match(List.of(lane), order(null, null, to(DELHI, null, ACME))).matched()).isTrue();
        // Stated and different are.
        assertThat(LaneMatching.match(List.of(lane), order("LTL", "32FT", to(DELHI, null, ACME))).refusal())
                .isEqualTo(LaneMatching.OUTSIDE_LANES);
        assertThat(LaneMatching.match(List.of(lane), order("FTL", "OPEN", to(DELHI, null, ACME))).refusal())
                .isEqualTo(LaneMatching.OUTSIDE_LANES);
    }

    @Test
    @DisplayName("an end may be a zone, which covers every terminal in it")
    void zonesCoverTheirTerminals() {
        ContractLane lane = lane("WEST-NORTH", ContractLane.Place.zone("west"), ContractLane.Place.zone("north"),
                null, null, Set.of());
        assertThat(LaneMatching.match(List.of(lane), order(null, null, to(DELHI, "NORTH", null))).matched()).isTrue();
        assertThat(LaneMatching.match(List.of(lane), order(null, null, to(PUNE, "WEST", null))).refusal())
                .isEqualTo(LaneMatching.OUTSIDE_LANES);
        // A terminal in no zone is covered by no zone lane.
        assertThat(LaneMatching.match(List.of(lane), order(null, null, to(DELHI, null, null))).matched()).isFalse();
    }

    @Test
    @DisplayName("every leg of the order must be covered, and the first lane by code is chosen")
    void everyLegAndAStableChoice() {
        ContractLane toDelhi = lane("B-DEL", ContractLane.Place.terminal(MUMBAI), ContractLane.Place.terminal(DELHI),
                null, null, Set.of());
        ContractLane toPune = lane("A-PUN", ContractLane.Place.terminal(MUMBAI), ContractLane.Place.terminal(PUNE),
                null, null, Set.of());
        ContractLane anyWhere = lane("C-ZONE", ContractLane.Place.terminal(MUMBAI), ContractLane.Place.zone("north"),
                null, null, Set.of());
        // Two destinations no single lane serves.
        assertThat(LaneMatching.match(List.of(toDelhi, toPune),
                order(null, null, to(DELHI, null, null), to(PUNE, null, null))).refusal())
                .isEqualTo(LaneMatching.OUTSIDE_LANES);
        // Two lanes serve Delhi: the first by code answers, whatever the order of the list.
        assertThat(LaneMatching.match(List.of(anyWhere, toDelhi),
                order(null, null, to(DELHI, "NORTH", null))).lane().laneCode()).isEqualTo("B-DEL");
        assertThat(LaneMatching.match(List.of(toDelhi, anyWhere),
                order(null, null, to(DELHI, "NORTH", null))).lane().laneCode()).isEqualTo("B-DEL");
    }

    @Test
    @DisplayName("a consignee the lane does not list is refused by name, unless another lane lists it")
    void consignees() {
        ContractLane acmeOnly = lane("A-ACME", ContractLane.Place.terminal(MUMBAI),
                ContractLane.Place.terminal(DELHI), null, null, Set.of(ACME));
        LaneMatching.Outcome refused = LaneMatching.match(List.of(acmeOnly),
                order(null, null, to(DELHI, null, GLOBEX)));
        assertThat(refused.matched()).isFalse();
        assertThat(refused.refusal()).isEqualTo(LaneMatching.CONSIGNEE_NOT_ALLOWED);
        // A leg with no consignee cannot be shown to be allowed.
        assertThat(LaneMatching.match(List.of(acmeOnly), order(null, null, to(DELHI, null, null))).refusal())
                .isEqualTo(LaneMatching.CONSIGNEE_NOT_ALLOWED);
        assertThat(LaneMatching.match(List.of(acmeOnly), order(null, null, to(DELHI, null, ACME))).matched())
                .isTrue();

        ContractLane open = lane("B-OPEN", ContractLane.Place.terminal(MUMBAI), ContractLane.Place.terminal(DELHI),
                null, null, Set.of());
        assertThat(LaneMatching.match(List.of(acmeOnly, open), order(null, null, to(DELHI, null, GLOBEX)))
                .lane().laneCode()).isEqualTo("B-OPEN");
    }

    @Test
    @DisplayName("an order with no lines yet can only be judged by its header")
    void headerOnly() {
        ContractLane lane = lane("MUM-DEL", ContractLane.Place.terminal(MUMBAI), ContractLane.Place.terminal(DELHI),
                null, null, Set.of(ACME));
        assertThat(LaneMatching.match(List.of(lane), order(null, null)).matched()).isTrue();
        LaneMatching.Order fromPune = new LaneMatching.Order(site(PUNE, null), null, null, List.of());
        assertThat(LaneMatching.match(List.of(lane), fromPune).refusal()).isEqualTo(LaneMatching.OUTSIDE_LANES);
    }

    @Test
    @DisplayName("a chosen lane is held to every rule, each refused by its own name")
    void requireTheChosenLane() {
        ContractLane lane = lane("MUM-DEL", ContractLane.Place.terminal(MUMBAI), ContractLane.Place.terminal(DELHI),
                "FTL", "32FT", Set.of(ACME));
        LaneMatching.require(lane, order("FTL", "32FT", to(DELHI, null, ACME)));
        assertRefused(() -> LaneMatching.require(lane, new LaneMatching.Order(site(PUNE, null), "FTL", "32FT",
                List.of())), LaneMatching.OUTSIDE_LANES);
        assertRefused(() -> LaneMatching.require(lane, order("LTL", "32FT")), LaneMatching.OUTSIDE_LANES);
        assertRefused(() -> LaneMatching.require(lane, order("FTL", "OPEN")), LaneMatching.LOAD_TYPE_NOT_ALLOWED);
        assertRefused(() -> LaneMatching.require(lane, order("FTL", "32FT", to(PUNE, null, ACME))),
                LaneMatching.OUTSIDE_LANES);
        assertRefused(() -> LaneMatching.require(lane, order("FTL", "32FT", to(DELHI, null, GLOBEX))),
                LaneMatching.CONSIGNEE_NOT_ALLOWED);
    }

    @Test
    @DisplayName("the contract's load types are a comma separated list, compared in upper case; none lists all")
    void contractLoadTypes() {
        LaneMatching.requireLoadTypeAllowed(null, "reefer");
        LaneMatching.requireLoadTypeAllowed("32FT,OPEN", null);
        LaneMatching.requireLoadTypeAllowed("32FT,OPEN", " open ");
        assertRefused(() -> LaneMatching.requireLoadTypeAllowed("32FT,OPEN", "reefer"),
                LaneMatching.LOAD_TYPE_NOT_ALLOWED);
    }

    private static void assertRefused(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessRuleViolationException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }
}
