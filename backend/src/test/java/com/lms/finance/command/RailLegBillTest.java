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
package com.lms.finance.command;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.lms.finance.command.domain.CostSheet;
import com.lms.finance.command.domain.FreightBill;
import com.lms.multimodal.events.RailLegCompleted;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pure parts of billing a released rail leg, without a database;
 * {@code rail_settlement.feature} proves the flow end to end.
 */
class RailLegBillTest {

    private static RailLegCompleted leg(String cost, String unitType, Integer units,
                                        String booking, String train) {
        return new RailLegCompleted(UUID.randomUUID(), UUID.randomUUID(), "MM-AB12CD34",
                UUID.randomUUID(), 2, UUID.randomUUID(), UUID.randomUUID(), new BigDecimal(cost),
                "INR", unitType, units, booking, train, Instant.parse("2026-03-01T06:00:00Z"),
                Instant.parse("2026-03-03T06:00:00Z"));
    }

    private static FreightBill bill(RailLegCompleted leg) {
        return FreightBill.forRailLeg(UUID.randomUUID(), leg.tenantId(), leg.orgUnitId(),
                leg.legId(), leg.carrierPartnerId(), BillingService.railBillNo(leg),
                LocalDate.of(2026, 3, 1), BillingService.railSheet(leg));
    }

    @Test
    void the_bill_number_names_the_itinerary_and_the_leg() {
        assertThat(BillingService.railBillNo(leg("1000", "WAGON", 1, null, null)))
                .isEqualTo("RB-MM-AB12CD34-2");
    }

    @Test
    void the_sheet_is_one_base_freight_line_equal_to_the_recorded_cost() {
        CostSheet sheet = BillingService.railSheet(leg("85000", "WAGON", 40, "RR-778", "T-12"));

        assertThat(sheet.charges()).hasSize(1);
        CostSheet.Charge line = sheet.charges().get(0);
        assertThat(line.amount()).isEqualByComparingTo("85000.00");
        assertThat(line.quantity()).isEqualByComparingTo("40");
        assertThat(line.unit()).isEqualTo("wagon");
        assertThat(line.rate()).isEqualByComparingTo("2125.0000");
        assertThat(line.narrative()).contains("40 WAGON", "booking RR-778", "train T-12");
        assertThat(sheet.total()).isEqualByComparingTo(sheet.baseFreight());
        assertThat(sheet.currency()).isEqualTo("INR");
    }

    @Test
    void a_cost_that_does_not_divide_evenly_keeps_the_recorded_amount() {
        CostSheet sheet = BillingService.railSheet(leg("100", "CONTAINER", 3, null, null));

        assertThat(sheet.total()).isEqualByComparingTo("100.00");
        assertThat(sheet.charges().get(0).rate()).isEqualByComparingTo("33.3333");
    }

    @Test
    void a_leg_booked_without_units_is_one_booking() {
        CostSheet sheet = BillingService.railSheet(leg("5000", null, null, null, null));

        assertThat(sheet.charges().get(0).quantity()).isEqualByComparingTo("1");
        assertThat(sheet.charges().get(0).unit()).isEqualTo("booking");
        assertThat(sheet.charges().get(0).narrative()).contains("1 booking");
    }

    @Test
    void a_rail_bill_has_a_leg_for_its_subject_and_no_trip_or_load() {
        FreightBill bill = bill(leg("85000", "WAGON", 40, null, null));

        assertThat(bill.isForRailLeg()).isTrue();
        assertThat(bill.itineraryLegId()).isNotNull();
        assertThat(bill.tripId()).isNull();
        assertThat(bill.loadId()).isNull();
        assertThat(bill.status()).isEqualTo(FreightBill.BillStatus.DRAFT);
        assertThat(bill.computedAmount()).isEqualByComparingTo("85000.00");
    }

    @Test
    void the_same_tolerance_rule_matches_a_rail_bill() {
        FreightBill bill = bill(leg("85000", "WAGON", 40, null, null));
        BigDecimal pct = new BigDecimal("2");
        BigDecimal floor = new BigDecimal("100");

        FreightBill inside = bill.matchAgainst(new BigDecimal("86700"), pct, floor, Instant.now());
        FreightBill outside = bill.matchAgainst(new BigDecimal("86701"), pct, floor, Instant.now());

        assertThat(inside.status()).isEqualTo(FreightBill.BillStatus.APPROVED_FOR_PAYMENT);
        assertThat(outside.status()).isEqualTo(FreightBill.BillStatus.DISPUTED);
        assertThat(outside.itineraryLegId()).isEqualTo(bill.itineraryLegId());
    }
}
