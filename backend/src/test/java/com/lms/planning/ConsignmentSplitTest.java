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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.lms.order.api.FreightMode;
import com.lms.order.api.MaterialClass;
import com.lms.planning.command.domain.Consignment;
import com.lms.planning.command.domain.Consignment.ConsignmentStatus;
import com.lms.planning.command.domain.Consignment.PartSpec;
import com.lms.shared.error.BusinessRuleViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** One consignment carried by many vehicles: the split rule on the aggregate. */
class ConsignmentSplitTest {

    private static Consignment whole(String weightKg, String volumeM3, String chargeableKg) {
        return Consignment.generate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "LR-WHOLE", UUID.randomUUID(), UUID.randomUUID(), new BigDecimal(weightKg),
                new BigDecimal(volumeM3), new BigDecimal(chargeableKg), true,
                List.of(MaterialClass.GENERAL, MaterialClass.FLAMMABLE), FreightMode.FTL,
                UUID.randomUUID(), null, null);
    }

    private static List<PartSpec> equalParts(int count, String weightKg, String volumeM3) {
        return Collections.nCopies(count, new PartSpec(new BigDecimal(weightKg), new BigDecimal(volumeM3)));
    }

    private static Consignment.Split split(Consignment whole, List<PartSpec> specs) {
        List<UUID> ids = new ArrayList<>();
        List<String> refs = new ArrayList<>();
        for (int i = 1; i <= specs.size(); i++) {
            ids.add(UUID.randomUUID());
            refs.add(Consignment.partRef(whole.trackingRef(), i, specs.size()));
        }
        return whole.split(specs, ids, refs);
    }

    private static String codeOf(Runnable action) {
        try {
            action.run();
        } catch (BusinessRuleViolationException e) {
            return e.code();
        }
        throw new AssertionError("the split was accepted");
    }

    @Test
    @DisplayName("two thousand tonnes become a hundred twenty-tonne parts, and the whole becomes SPLIT")
    void bulkFreightSplitsIntoAHundredParts() {
        Consignment whole = whole("2000000", "6000", "2000000");

        Consignment.Split split = split(whole, equalParts(100, "20000", "60"));

        assertThat(split.parent().status()).isEqualTo(ConsignmentStatus.SPLIT);
        assertThat(split.parent().id()).isEqualTo(whole.id());
        assertThat(split.parts()).hasSize(100);
        assertThat(split.parts()).allSatisfy(part -> {
            assertThat(part.status()).isEqualTo(ConsignmentStatus.PLANNED);
            assertThat(part.parentConsignmentId()).isEqualTo(whole.id());
            assertThat(part.isPart()).isTrue();
            assertThat(part.orderId()).isEqualTo(whole.orderId());
            assertThat(part.consigneePartnerId()).isEqualTo(whole.consigneePartnerId());
            assertThat(part.destinationTerminalId()).isEqualTo(whole.destinationTerminalId());
            assertThat(part.customerPartnerId()).isEqualTo(whole.customerPartnerId());
            assertThat(part.freightMode()).isEqualTo(FreightMode.FTL);
            assertThat(part.containsHazmat()).as("each part may hold any of the whole").isTrue();
            assertThat(part.materialClassSet()).isEqualTo(whole.materialClassSet());
            assertThat(part.totalDeadWeightKg()).isEqualByComparingTo("20000");
            assertThat(part.chargeableWeightKg()).isEqualByComparingTo("20000");
            assertThat(part.version()).as("new, so saved as an insert").isNull();
        });
        assertThat(split.parts()).extracting(Consignment::trackingRef)
                .startsWith("LR-WHOLE-001", "LR-WHOLE-002").endsWith("LR-WHOLE-100")
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("SPLIT is terminal: the whole is never loaded, cancelled or delivered again")
    void splitIsTerminal() {
        Consignment parent = split(whole("100", "1", "100"), equalParts(2, "50", "0.5")).parent();

        for (ConsignmentStatus target : ConsignmentStatus.values()) {
            if (target == ConsignmentStatus.SPLIT) {
                continue;
            }
            assertThatThrownBy(() -> parent.transitionTo(target))
                    .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                            e -> assertThat(e.code()).isEqualTo("consignment-illegal-transition"));
        }
        assertThat(codeOf(() -> split(parent, equalParts(2, "50", "0.5"))))
                .isEqualTo("consignment-not-splittable");
    }

    @Test
    @DisplayName("chargeable weight follows dead weight, rounded down, the last part taking the remainder")
    void chargeableWeightIsApportioned() {
        Consignment whole = whole("100", "3", "100.001");

        List<Consignment> parts = split(whole, List.of(
                new PartSpec(new BigDecimal("33.333"), BigDecimal.ONE),
                new PartSpec(new BigDecimal("33.333"), BigDecimal.ONE),
                new PartSpec(new BigDecimal("33.334"), BigDecimal.ONE))).parts();

        // 100.001 x 33.333 / 100 = 33.3333 -> 33.333, twice; the last takes 33.335.
        assertThat(parts).extracting(Consignment::chargeableWeightKg)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("33.333"), new BigDecimal("33.333"),
                        new BigDecimal("33.335"));
        assertThat(parts.stream().map(Consignment::chargeableWeightKg)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("100.001");
    }

    @Test
    @DisplayName("the remainder never goes negative, however many parts and however small the last")
    void remainderNeverNegative() {
        List<PartSpec> specs = new ArrayList<>(equalParts(499, "3.333", "0"));
        specs.add(new PartSpec(new BigDecimal("0.001"), BigDecimal.ZERO));
        BigDecimal weight = new BigDecimal("3.333").multiply(BigDecimal.valueOf(499)).add(new BigDecimal("0.001"));
        Consignment whole = whole(weight.toPlainString(), "0", "9999.999");

        List<Consignment> parts = split(whole, specs).parts();

        assertThat(parts).hasSize(500);
        assertThat(parts).allSatisfy(part -> assertThat(part.chargeableWeightKg().signum()).isNotNegative());
        assertThat(parts.stream().map(Consignment::chargeableWeightKg)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("9999.999");
    }

    @Test
    @DisplayName("parts must add up exactly, and the refusal names the difference")
    void partsMustAddUp() {
        Consignment whole = whole("1000", "10", "1000");

        BusinessRuleViolationException heavy = (BusinessRuleViolationException) catchThrown(() ->
                split(whole, List.of(new PartSpec(new BigDecimal("600"), new BigDecimal("5")),
                        new PartSpec(new BigDecimal("400.5"), new BigDecimal("5")))));
        assertThat(heavy.code()).isEqualTo("split-weights-do-not-add-up");
        assertThat(heavy.getMessage()).contains("1000.5").contains("difference of 0.5 kg");

        assertThat(codeOf(() -> split(whole, List.of(new PartSpec(new BigDecimal("500"), new BigDecimal("5")),
                new PartSpec(new BigDecimal("500"), new BigDecimal("4.999"))))))
                .isEqualTo("split-volumes-do-not-add-up");
        assertThat(whole.status()).as("a refused split changes nothing").isEqualTo(ConsignmentStatus.PLANNED);
    }

    @Test
    @DisplayName("between two and five hundred parts")
    void partCountIsBounded() {
        assertThat(codeOf(() -> split(whole("100", "0", "100"), equalParts(1, "100", "0"))))
                .isEqualTo("split-part-count-invalid");
        assertThat(codeOf(() -> split(whole("100", "0", "100"), List.of())))
                .isEqualTo("split-part-count-invalid");
        assertThat(codeOf(() -> split(whole("501", "0", "501"), equalParts(501, "1", "0"))))
                .isEqualTo("split-part-count-invalid");
        assertThat(split(whole("500", "0", "500"), equalParts(500, "1", "0")).parts()).hasSize(500);
    }

    @Test
    @DisplayName("every part carries some weight, no negative volume, and no finer than a gram")
    void partsAreWellFormed() {
        Consignment whole = whole("100", "2", "100");
        assertThat(codeOf(() -> split(whole, List.of(new PartSpec(BigDecimal.ZERO, BigDecimal.ONE),
                new PartSpec(new BigDecimal("100"), BigDecimal.ONE))))).isEqualTo("split-part-invalid");
        assertThat(codeOf(() -> split(whole, List.of(new PartSpec(new BigDecimal("50"), new BigDecimal("-1")),
                new PartSpec(new BigDecimal("50"), new BigDecimal("3")))))).isEqualTo("split-part-invalid");
        assertThat(codeOf(() -> split(whole, List.of(new PartSpec(new BigDecimal("49.9995"), BigDecimal.ONE),
                new PartSpec(new BigDecimal("50.0005"), BigDecimal.ONE))))).isEqualTo("split-part-invalid");
        assertThat(codeOf(() -> split(whole, java.util.Arrays.asList(null,
                new PartSpec(new BigDecimal("100"), new BigDecimal("2")))))).isEqualTo("split-part-invalid");
    }

    @Test
    @DisplayName("only a planned full truckload is split: not a docket, a loaded one, a part, or a warehouse unit")
    void onlyAPlannedFullTruckload() {
        Consignment docket = Consignment.generate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "DKT-1", UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("100"), BigDecimal.ONE,
                new BigDecimal("100"), false, List.of(), FreightMode.LTL, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID());
        assertThat(codeOf(() -> split(docket, equalParts(2, "50", "0.5")))).isEqualTo("docket-cannot-be-split");

        Consignment loaded = whole("100", "1", "100").transitionTo(ConsignmentStatus.ASSIGNED_TO_LOAD);
        assertThat(codeOf(() -> split(loaded, equalParts(2, "50", "0.5")))).isEqualTo("consignment-not-splittable");

        Consignment part = split(whole("100", "1", "100"), equalParts(2, "50", "0.5")).parts().get(0);
        assertThat(codeOf(() -> split(part, equalParts(2, "25", "0.25")))).isEqualTo("consignment-not-splittable");

        Consignment unit = Consignment.fromHandlingUnit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "LR-HU", UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("100"), BigDecimal.ONE,
                new BigDecimal("100"));
        assertThat(codeOf(() -> split(unit, equalParts(2, "50", "0.5")))).isEqualTo("consignment-not-splittable");

        Consignment rail = Consignment.generate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "LR-R", UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("100"), BigDecimal.ONE,
                new BigDecimal("100"), false, List.of(), FreightMode.RAIL, UUID.randomUUID(), null, null);
        assertThat(codeOf(() -> split(rail, equalParts(2, "50", "0.5")))).isEqualTo("consignment-not-splittable");
    }

    @Test
    @DisplayName("a part is planned like any consignment, and rides through to delivery")
    void aPartTravelsLikeAnyConsignment() {
        Consignment part = split(whole("100", "1", "100"), equalParts(2, "50", "0.5")).parts().get(1);

        Consignment delivered = part.transitionTo(ConsignmentStatus.ASSIGNED_TO_LOAD)
                .transitionTo(ConsignmentStatus.IN_TRANSIT)
                .transitionTo(ConsignmentStatus.DELIVERED);

        assertThat(delivered.status()).isEqualTo(ConsignmentStatus.DELIVERED);
        assertThat(delivered.parentConsignmentId()).as("kept through every transition")
                .isEqualTo(part.parentConsignmentId());
    }

    @Test
    @DisplayName("part references are padded to the width of the count, never under two digits")
    void partReferences() {
        assertThat(Consignment.partRef("LR-AB", 1, 2)).isEqualTo("LR-AB-01");
        assertThat(Consignment.partRef("LR-AB", 12, 12)).isEqualTo("LR-AB-12");
        assertThat(Consignment.partRef("LR-AB", 7, 100)).isEqualTo("LR-AB-007");
        assertThat(Consignment.partRef("LR-AB", 500, 500)).isEqualTo("LR-AB-500");
        Set<String> refs = IntStream.rangeClosed(1, 500)
                .mapToObj(i -> Consignment.partRef("LR-AB", i, 500)).collect(Collectors.toSet());
        assertThat(refs).hasSize(500);
        List<String> sorted = IntStream.rangeClosed(1, 500)
                .mapToObj(i -> Consignment.partRef("LR-AB", i, 500)).sorted().toList();
        assertThat(sorted.get(0)).isEqualTo("LR-AB-001");
        assertThat(sorted.get(499)).as("sorted by reference is sorted by part number").isEqualTo("LR-AB-500");
    }

    private static Throwable catchThrown(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            return e;
        }
        throw new AssertionError("nothing was thrown");
    }
}
