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
package com.lms.planning;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.lms.order.api.FreightMode;
import com.lms.order.api.MaterialClass;
import com.lms.planning.command.domain.Consignment;
import com.lms.planning.command.domain.Consignment.ConsignmentStatus;
import com.lms.planning.command.domain.LoadUnit;
import com.lms.planning.command.domain.LoadUnit.LoadStatus;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.web.IfMatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rules of changing a load after it was built: which states may lose
 * freight, be un-planned or be cancelled, where a consignment taken off goes
 * back to, and that capacity is never exceeded however the freight moves.
 */
class LoadMaintenanceRulesTest {

    private static LoadUnit load(String capacityKg) {
        return LoadUnit.open(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "LOAD-M",
                UUID.randomUUID(), "RIGID_16T", new BigDecimal(capacityKg), new BigDecimal("40"));
    }

    private static LoadUnit in(LoadStatus status) {
        LoadUnit draft = load("10000");
        return switch (status) {
            case DRAFT -> draft;
            case PLANNED -> draft.transitionTo(LoadStatus.PLANNED);
            case AWARDED -> draft.transitionTo(LoadStatus.PLANNED).awardTo(UUID.randomUUID());
            case DISPATCHED -> in(LoadStatus.AWARDED).transitionTo(LoadStatus.DISPATCHED);
            case COMPLETED -> in(LoadStatus.DISPATCHED).transitionTo(LoadStatus.COMPLETED);
            case CANCELLED -> draft.transitionTo(LoadStatus.CANCELLED);
        };
    }

    private static Consignment consignment(FreightMode mode) {
        boolean ltl = mode == FreightMode.LTL;
        return Consignment.generate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "LR-1",
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("100"), BigDecimal.ONE,
                new BigDecimal("100"), false, List.of(MaterialClass.GENERAL), mode,
                ltl ? UUID.randomUUID() : null, ltl ? UUID.randomUUID() : null,
                ltl ? UUID.randomUUID() : null);
    }

    private static String codeOf(Runnable action) {
        try {
            action.run();
        } catch (BusinessRuleViolationException e) {
            return e.code();
        }
        return null;
    }

    // ------------------------------------------------------------- removal

    @Test
    @DisplayName("freight comes off a draft or planned load, and nothing else")
    void whoMayLoseFreight() {
        assertThat(codeOf(() -> in(LoadStatus.DRAFT).requireFreightRemovable())).isNull();
        assertThat(codeOf(() -> in(LoadStatus.PLANNED).requireFreightRemovable())).isNull();
        assertThat(codeOf(() -> in(LoadStatus.AWARDED).requireFreightRemovable()))
                .isEqualTo("load-awarded");
        assertThat(codeOf(() -> in(LoadStatus.DISPATCHED).requireFreightRemovable()))
                .isEqualTo("load-dispatched");
        assertThat(codeOf(() -> in(LoadStatus.COMPLETED).requireFreightRemovable()))
                .isEqualTo("load-dispatched");
        assertThat(codeOf(() -> in(LoadStatus.CANCELLED).requireFreightRemovable()))
                .isEqualTo("load-cancelled");
    }

    @Test
    @DisplayName("a consignment taken off goes back to the status it boarded from")
    void releasedToWhereItBoarded() {
        Consignment lorryReceipt = consignment(FreightMode.FTL)
                .transitionTo(ConsignmentStatus.ASSIGNED_TO_LOAD);
        assertThat(lorryReceipt.releasedFromLoad().status()).isEqualTo(ConsignmentStatus.PLANNED);

        Consignment lineHaulDocket = consignment(FreightMode.LTL)
                .transitionTo(ConsignmentStatus.ASSIGNED_TO_LOAD);
        assertThat(lineHaulDocket.releasedFromLoad().status()).isEqualTo(ConsignmentStatus.PLANNED);

        Consignment deliveryDocket = consignment(FreightMode.LTL)
                .transitionTo(ConsignmentStatus.ASSIGNED_TO_LOAD)
                .transitionTo(ConsignmentStatus.IN_TRANSIT)
                .transitionTo(ConsignmentStatus.ARRIVED_AT_HUB)
                .transitionTo(ConsignmentStatus.ASSIGNED_TO_DELIVERY);
        assertThat(deliveryDocket.releasedFromLoad().status())
                .isEqualTo(ConsignmentStatus.ARRIVED_AT_HUB);
    }

    @Test
    @DisplayName("a consignment that has moved with its vehicle cannot be taken off")
    void movedFreightStaysOn() {
        Consignment moving = consignment(FreightMode.FTL)
                .transitionTo(ConsignmentStatus.ASSIGNED_TO_LOAD)
                .transitionTo(ConsignmentStatus.IN_TRANSIT);
        assertThat(codeOf(moving::releasedFromLoad)).isEqualTo("consignment-illegal-transition");
        assertThat(codeOf(consignment(FreightMode.FTL)::releasedFromLoad))
                .isEqualTo("consignment-illegal-transition");
    }

    // ------------------------------------------------------------- un-plan

    @Test
    @DisplayName("only a planned load is un-planned, back to draft with its freight")
    void unplan() {
        LoadUnit planned = load("10000").addConsignment(new BigDecimal("4000"), BigDecimal.TEN, true)
                .transitionTo(LoadStatus.PLANNED);
        LoadUnit draft = planned.unplanned();
        assertThat(draft.status()).isEqualTo(LoadStatus.DRAFT);
        assertThat(draft.plannedWeightKg()).isEqualByComparingTo("4000");
        assertThat(draft.requiresHazmat()).isTrue();

        assertThat(codeOf(() -> in(LoadStatus.DRAFT).unplanned())).isEqualTo("load-not-planned");
        assertThat(codeOf(() -> in(LoadStatus.AWARDED).unplanned())).isEqualTo("load-awarded");
        assertThat(codeOf(() -> in(LoadStatus.DISPATCHED).unplanned())).isEqualTo("load-dispatched");
        assertThat(codeOf(() -> in(LoadStatus.CANCELLED).unplanned())).isEqualTo("load-cancelled");
    }

    // -------------------------------------------------------------- cancel

    @ParameterizedTest
    @EnumSource(value = LoadStatus.class, names = {"DRAFT", "PLANNED", "AWARDED"})
    @DisplayName("a load that has not left may be cancelled")
    void cancellable(LoadStatus status) {
        LoadUnit load = in(status);
        load.requireCancellable();
        assertThat(load.restated(BigDecimal.ZERO, BigDecimal.ZERO, false)
                .transitionTo(LoadStatus.CANCELLED).status()).isEqualTo(LoadStatus.CANCELLED);
    }

    @Test
    @DisplayName("a load that has left, or is already cancelled, is not cancelled again")
    void notCancellable() {
        assertThat(codeOf(() -> in(LoadStatus.DISPATCHED).requireCancellable()))
                .isEqualTo("load-dispatched");
        assertThat(codeOf(() -> in(LoadStatus.COMPLETED).requireCancellable()))
                .isEqualTo("load-dispatched");
        assertThat(codeOf(() -> in(LoadStatus.CANCELLED).requireCancellable()))
                .isEqualTo("load-already-cancelled");
    }

    // ------------------------------------------------- capacity, never over

    @Test
    @DisplayName("freight taken off frees exactly that room, and no more")
    void removalFreesOnlyItsRoom() {
        LoadUnit full = load("10000").addConsignment(new BigDecimal("6000"), BigDecimal.ONE, false)
                .addConsignment(new BigDecimal("4000"), BigDecimal.ONE, false);
        assertThat(full.canAccommodate(new BigDecimal("0.001"), BigDecimal.ZERO)).isFalse();

        // The 4000 kg consignment comes off: 4000 kg of room, not a gram more.
        LoadUnit lighter = full.restated(new BigDecimal("6000"), BigDecimal.ONE, false);
        assertThat(lighter.addConsignment(new BigDecimal("4000"), BigDecimal.ONE, false)
                .plannedWeightKg()).isEqualByComparingTo("10000");
        assertThatThrownBy(() -> lighter.addConsignment(new BigDecimal("4000.001"), BigDecimal.ONE, false))
                .isInstanceOf(BusinessRuleViolationException.class)
                .extracting(e -> ((BusinessRuleViolationException) e).code())
                .isEqualTo("load-capacity-exceeded");
    }

    @Test
    @DisplayName("there is no allowance over capacity, in any state that can still be built")
    void neverOverCapacity() {
        for (LoadUnit open : List.of(in(LoadStatus.DRAFT), in(LoadStatus.PLANNED))) {
            assertThatThrownBy(() -> open.addConsignment(new BigDecimal("10000.001"), BigDecimal.ONE, false))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .extracting(e -> ((BusinessRuleViolationException) e).code())
                    .isEqualTo("load-capacity-exceeded");
            assertThatThrownBy(() -> open.addConsignment(BigDecimal.ONE, new BigDecimal("40.001"), false))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .extracting(e -> ((BusinessRuleViolationException) e).code())
                    .isEqualTo("load-capacity-exceeded");
        }
    }

    // ------------------------------------------------------------ If-Match

    @Test
    @DisplayName("If-Match is read quoted, bare or weak; anything else is a bad request")
    void ifMatch() {
        assertThat(IfMatch.version("\"7\"", "v")).isEqualTo(7);
        assertThat(IfMatch.version("7", "v")).isEqualTo(7);
        assertThat(IfMatch.version("W/\"7\"", "v")).isEqualTo(7);
        assertThat(IfMatch.etag(3L)).isEqualTo("\"3\"");
        assertThat(IfMatch.etag(null)).isEqualTo("\"0\"");
        for (String bad : new String[] {null, " ", "*", "\"x\""}) {
            assertThatThrownBy(() -> IfMatch.version(bad, "v"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
