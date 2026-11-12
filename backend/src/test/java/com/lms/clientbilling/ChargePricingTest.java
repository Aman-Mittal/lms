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
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import com.lms.clientbilling.command.domain.ChargePricing;
import com.lms.clientbilling.command.domain.ClientCharge;
import com.lms.clientbilling.command.domain.ClientContract;
import com.lms.clientbilling.command.domain.ContractLane;
import com.lms.clientbilling.command.domain.ContractServiceRate;
import com.lms.clientbilling.command.domain.ServiceType;
import com.lms.shared.error.BusinessRuleViolationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The pricing of one charge, without a database.
 *
 * <p>{@code clientbilling.feature} proves a month bills end to end; these pin
 * the edges -- the transport floor, rounding, a value-added service the
 * contract does not price -- that are not worth a scenario each.
 */
class ChargePricingTest {

    private static final LocalDate DAY = LocalDate.of(2026, 3, 10);

    private static ClientContract contract(String perKg, String minimum) {
        return ClientContract.publish(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "C-1", UUID.randomUUID(), "INR", LocalDate.of(2026, 1, 1),
                new ClientContract.Rates(new BigDecimal(perKg), new BigDecimal(minimum),
                        new BigDecimal("12.50"), new BigDecimal("25"), new BigDecimal("30")));
    }

    private static ClientCharge charge(ServiceType type, String code, String quantity) {
        return ClientCharge.capture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                type, code, new BigDecimal(quantity), type.unit() == null ? "UNIT" : type.unit(),
                DAY, DAY.withDayOfMonth(1), "KEY-" + UUID.randomUUID(), "REF", null, null);
    }

    @Test
    void transportIsWeightTimesRate() {
        ChargePricing.Priced line = ChargePricing.price(
                charge(ServiceType.TRANSPORT, null, "5000"), contract("5.00", "0"), Map.of());
        assertThat(line.amount()).isEqualByComparingTo("25000.00");
        assertThat(line.unit()).isEqualTo("KG");
        assertThat(line.narrative()).doesNotContain("minimum");
    }

    @Test
    void lightTransportIsBilledTheFloorAndSaysSo() {
        ChargePricing.Priced line = ChargePricing.price(
                charge(ServiceType.TRANSPORT, null, "100"), contract("5.00", "2000"), Map.of());
        assertThat(line.amount()).isEqualByComparingTo("2000.00");
        assertThat(line.narrative()).contains("minimum charge applied");
    }

    @Test
    void storageRoundsToMoneyHalfUp() {
        ChargePricing.Priced line = ChargePricing.price(
                charge(ServiceType.STORAGE, null, "0.333"), contract("1", "0"), Map.of());
        // 0.333 x 12.50 = 4.1625
        assertThat(line.amount()).isEqualByComparingTo("4.16");
        assertThat(line.amount().scale()).isEqualTo(2);
    }

    @Test
    void handlingInAndOutUseTheirOwnRates() {
        ClientContract terms = contract("1", "0");
        assertThat(ChargePricing.price(charge(ServiceType.HANDLING_IN, null, "10"), terms,
                Map.of()).amount()).isEqualByComparingTo("250");
        assertThat(ChargePricing.price(charge(ServiceType.HANDLING_OUT, null, "10"), terms,
                Map.of()).amount()).isEqualByComparingTo("300");
    }

    @Test
    void valueAddedServiceUsesTheContractsRateAndUnit() {
        ClientContract terms = contract("1", "0");
        ContractServiceRate label = ContractServiceRate.of(UUID.randomUUID(), terms.tenantId(),
                terms.id(), "label", "sheet", new BigDecimal("2.00"));
        ChargePricing.Priced line = ChargePricing.price(
                charge(ServiceType.VALUE_ADDED, "Label", "200"), terms,
                Map.of(label.serviceCode(), label));
        assertThat(line.amount()).isEqualByComparingTo("400.00");
        assertThat(line.unit()).isEqualTo("SHEET");
    }

    @Test
    void anUnpricedValueAddedServiceIsRefusedNotZeroRated() {
        assertThatThrownBy(() -> ChargePricing.price(
                charge(ServiceType.VALUE_ADDED, "KITTING", "5"), contract("1", "0"), Map.of()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("KITTING");
    }

    @Test
    void aServiceCodeOnlyBelongsToValueAddedCharges() {
        assertThatThrownBy(() -> charge(ServiceType.STORAGE, "LABEL", "1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> charge(ServiceType.VALUE_ADDED, null, "1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void contractVersionCoversItsOwnDaysOnly() {
        ClientContract terms = contract("1", "0");
        ClientContract closed = terms.closeOn(LocalDate.of(2026, 2, 28));
        assertThat(closed.coversOn(LocalDate.of(2026, 2, 28))).isTrue();
        assertThat(closed.coversOn(LocalDate.of(2026, 3, 1))).isFalse();
        assertThat(closed.coversOn(LocalDate.of(2025, 12, 31))).isFalse();
    }

    private static ContractLane lane(ContractLane.RateBasis basis, String rate, ContractLane.Slab... slabs) {
        return new ContractLane(UUID.randomUUID(), UUID.randomUUID(), "MUM-DEL",
                ContractLane.Place.terminal(UUID.randomUUID()), ContractLane.Place.terminal(UUID.randomUUID()),
                null, null, basis, rate == null ? null : new BigDecimal(rate), null, java.util.List.of(slabs),
                null, null);
    }

    @Test
    void aLanePricesTransportByItsBasisAndShowsItsUnit() {
        ClientContract terms = contract("5.00", "0");
        ChargePricing.Priced perTonne = ChargePricing.price(charge(ServiceType.TRANSPORT, null, "2500"), terms,
                Map.of(), lane(ContractLane.RateBasis.PER_TONNE, "9000"));
        assertThat(perTonne.amount()).isEqualByComparingTo("22500.00");
        assertThat(perTonne.unit()).isEqualTo("TONNE");
        assertThat(perTonne.rate()).isEqualByComparingTo("9000");
        assertThat(perTonne.quantity()).isEqualByComparingTo("2.5");
        assertThat(perTonne.narrative()).contains("lane MUM-DEL");

        ChargePricing.Priced perTrip = ChargePricing.price(charge(ServiceType.TRANSPORT, null, "2500"), terms,
                Map.of(), lane(ContractLane.RateBasis.PER_TRIP, "18000"));
        assertThat(perTrip.amount()).isEqualByComparingTo("18000.00");
        assertThat(perTrip.unit()).isEqualTo("TRIP");
        assertThat(perTrip.quantity()).isEqualByComparingTo("1");

        ChargePricing.Priced perKg = ChargePricing.price(charge(ServiceType.TRANSPORT, null, "2500"), terms,
                Map.of(), lane(ContractLane.RateBasis.PER_KG, "6"));
        assertThat(perKg.amount()).isEqualByComparingTo("15000.00");
        assertThat(perKg.unit()).isEqualTo("KG");
    }

    @Test
    void theSlabCoveringTheWeightPricesIt() {
        ContractLane slabbed = lane(ContractLane.RateBasis.PER_KG, "20",
                new ContractLane.Slab(BigDecimal.ZERO, new BigDecimal("1000"), ContractLane.RateBasis.PER_KG,
                        new BigDecimal("12")),
                new ContractLane.Slab(new BigDecimal("1000"), null, ContractLane.RateBasis.PER_TONNE,
                        new BigDecimal("8000")));
        ClientContract terms = contract("5.00", "0");
        assertThat(ChargePricing.price(charge(ServiceType.TRANSPORT, null, "999"), terms, Map.of(), slabbed)
                .amount()).isEqualByComparingTo("11988.00");
        // The upper boundary belongs to the upper band only.
        ChargePricing.Priced onTheEdge = ChargePricing.price(charge(ServiceType.TRANSPORT, null, "1000"), terms,
                Map.of(), slabbed);
        assertThat(onTheEdge.unit()).isEqualTo("TONNE");
        assertThat(onTheEdge.amount()).isEqualByComparingTo("8000.00");
    }

    @Test
    void aLaneWithNoRateForTheWeightFallsBackToThePerKiloRate() {
        ContractLane gap = lane(ContractLane.RateBasis.PER_KG, null,
                new ContractLane.Slab(new BigDecimal("500"), new BigDecimal("1000"), ContractLane.RateBasis.PER_KG,
                        new BigDecimal("12")));
        ChargePricing.Priced line = ChargePricing.price(charge(ServiceType.TRANSPORT, null, "100"),
                contract("5.00", "0"), Map.of(), gap);
        assertThat(line.amount()).isEqualByComparingTo("500.00");
        assertThat(line.narrative()).doesNotContain("lane");
    }

    @Test
    void theMinimumChargeStillAppliesToALane() {
        ChargePricing.Priced line = ChargePricing.price(charge(ServiceType.TRANSPORT, null, "100"),
                contract("5.00", "2000"), Map.of(), lane(ContractLane.RateBasis.PER_KG, "6"));
        assertThat(line.amount()).isEqualByComparingTo("2000.00");
        assertThat(line.narrative()).contains("minimum charge applied");
    }

    @Test
    void aLaneNeverPricesWhatIsNotTransport() {
        ChargePricing.Priced storage = ChargePricing.price(charge(ServiceType.STORAGE, null, "10"),
                contract("5.00", "0"), Map.of(), lane(ContractLane.RateBasis.PER_TRIP, "1"));
        assertThat(storage.amount()).isEqualByComparingTo("125.00");
    }
}
