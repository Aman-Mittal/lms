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
import java.util.UUID;

import com.lms.planning.command.domain.LoadUnit;
import com.lms.shared.error.BusinessRuleViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The capacity hard stop of vision document 3.4.2, and the load state machine. */
class LoadCapacityTest {

    private static LoadUnit load(String capacityKg, String capacityM3) {
        return LoadUnit.open(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "LOAD-1",
                UUID.randomUUID(), "RIGID_16T", new BigDecimal(capacityKg), new BigDecimal(capacityM3));
    }

    /** A load built against a real vehicle, which is what carries certification. */
    private static LoadUnit vehicleLoad(String capacityKg, String capacityM3, boolean hazmatCertified) {
        return LoadUnit.open(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "LOAD-V",
                UUID.randomUUID(), UUID.randomUUID(), "RIGID_16T", hazmatCertified,
                new BigDecimal(capacityKg), new BigDecimal(capacityM3));
    }

    @Test
    @DisplayName("accumulates consignments within capacity")
    void accumulatesWithinCapacity() {
        LoadUnit built = load("10000", "40")
                .addConsignment(new BigDecimal("3000"), new BigDecimal("12"), false)
                .addConsignment(new BigDecimal("4000"), new BigDecimal("15"), false);

        assertThat(built.plannedWeightKg()).isEqualByComparingTo("7000");
        assertThat(built.plannedVolumeM3()).isEqualByComparingTo("27");
        assertThat(built.remainingWeightKg()).isEqualByComparingTo("3000");
    }

    @Test
    @DisplayName("refuses a consignment that would exceed weight capacity")
    void refusesOverWeight() {
        LoadUnit nearlyFull = load("10000", "40").addConsignment(new BigDecimal("9000"), BigDecimal.ONE, false);

        assertThatThrownBy(() -> nearlyFull.addConsignment(new BigDecimal("2000"), BigDecimal.ONE, false))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("weight capacity");
    }

    @Test
    @DisplayName("refuses a consignment that would exceed volume capacity")
    void refusesOverVolume() {
        // Light but bulky: well inside the weight limit, out of room.
        LoadUnit nearlyFull = load("10000", "40").addConsignment(new BigDecimal("100"), new BigDecimal("38"), false);

        assertThatThrownBy(() -> nearlyFull.addConsignment(new BigDecimal("100"), new BigDecimal("5"), false))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("volume capacity");
    }

    @Test
    @DisplayName("reports both dimensions when both are exceeded")
    void reportsBothOverages() {
        // An operator told only about weight would fix the weight and try again,
        // then be told about volume. Both at once saves the round trip.
        LoadUnit empty = load("1000", "10");

        assertThatThrownBy(() -> empty.addConsignment(new BigDecimal("2000"), new BigDecimal("20"), false))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("weight capacity")
                .hasMessageContaining("volume capacity");
    }

    @Test
    @DisplayName("accepts a consignment that fills capacity exactly")
    void exactFitIsAllowed() {
        // The limit is inclusive: a lorry loaded to precisely its rated capacity
        // is legal, and an off-by-one here would reject perfectly good plans.
        LoadUnit full = load("10000", "40").addConsignment(new BigDecimal("10000"), new BigDecimal("40"), false);

        assertThat(full.remainingWeightKg()).isEqualByComparingTo("0");
        assertThat(full.weightUtilisationPct()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("a hazmat consignment marks the whole load")
    void hazmatPropagates() {
        LoadUnit mixed = load("10000", "40")
                .addConsignment(new BigDecimal("1000"), BigDecimal.ONE, false)
                .addConsignment(new BigDecimal("1000"), BigDecimal.ONE, true);

        // Once set it must not be cleared by a subsequent ordinary consignment:
        // the load still contains dangerous goods.
        LoadUnit afterOrdinary = mixed.addConsignment(new BigDecimal("500"), BigDecimal.ONE, false);

        assertThat(mixed.requiresHazmat()).isTrue();
        assertThat(afterOrdinary.requiresHazmat()).isTrue();
    }

    @Test
    @DisplayName("dangerous goods are refused on an uncertified vehicle")
    void hazmatRefusedWithoutCertification() {
        // The 3.2.2 hard stop, reaching into planning. Certification belongs to
        // the individual vehicle, so a load sized only against a vehicle *type*
        // cannot answer this -- which is why the check is keyed on there being a
        // real vehicle behind the load.
        LoadUnit uncertified = vehicleLoad("10000", "40", false);

        assertThatThrownBy(() -> uncertified.addConsignment(
                new BigDecimal("100"), BigDecimal.ONE, true))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("not certified");
    }

    @Test
    @DisplayName("ordinary freight is unaffected by the certification check")
    void ordinaryFreightOnUncertifiedVehicle() {
        LoadUnit uncertified = vehicleLoad("10000", "40", false)
                .addConsignment(new BigDecimal("100"), BigDecimal.ONE, false);

        assertThat(uncertified.plannedWeightKg()).isEqualByComparingTo("100");
        assertThat(uncertified.requiresHazmat()).isFalse();
    }

    @Test
    @DisplayName("a certified vehicle takes dangerous goods")
    void hazmatAllowedWhenCertified() {
        LoadUnit certified = vehicleLoad("10000", "40", true)
                .addConsignment(new BigDecimal("100"), BigDecimal.ONE, true);

        assertThat(certified.requiresHazmat()).isTrue();
    }

    @Test
    @DisplayName("a dispatched load can no longer be built on")
    void closedLoadRejectsConsignments() {
        LoadUnit dispatched = load("10000", "40")
                .transitionTo(LoadUnit.LoadStatus.PLANNED)
                .transitionTo(LoadUnit.LoadStatus.AWARDED)
                .transitionTo(LoadUnit.LoadStatus.DISPATCHED);

        assertThatThrownBy(() -> dispatched.addConsignment(BigDecimal.ONE, BigDecimal.ONE, false))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("can no longer be built");
    }

    @Test
    @DisplayName("illegal state transitions are refused")
    void illegalTransitionsRefused() {
        LoadUnit draft = load("10000", "40");

        assertThatThrownBy(() -> draft.transitionTo(LoadUnit.LoadStatus.DISPATCHED))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("cannot move from DRAFT to DISPATCHED");
    }

    @Test
    @DisplayName("completed and cancelled are terminal")
    void terminalStates() {
        LoadUnit cancelled = load("10000", "40").transitionTo(LoadUnit.LoadStatus.CANCELLED);

        assertThatThrownBy(() -> cancelled.transitionTo(LoadUnit.LoadStatus.PLANNED))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    @DisplayName("a load must open with positive capacity")
    void rejectsNonsensicalCapacity() {
        assertThatThrownBy(() -> LoadUnit.open(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "BAD", UUID.randomUUID(), "RIGID", BigDecimal.ZERO, BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
