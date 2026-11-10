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
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.order.api.FreightMode;
import com.lms.order.api.MaterialClass;
import com.lms.planning.command.domain.Consignment;
import com.lms.planning.command.domain.Consignment.ConsignmentStatus;
import com.lms.planning.command.domain.ConsignmentAmendment;
import com.lms.planning.command.domain.ConsignmentAmendment.FieldResult;
import com.lms.planning.command.domain.ConsignmentAmendment.Outcome;
import com.lms.planning.command.domain.ConsignmentParticulars;
import com.lms.shared.error.BusinessRuleViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What may be said about a consignment after it is generated, and when it may be cancelled on its own. */
class ConsignmentAmendmentTest {

    private static Consignment planned() {
        return Consignment.generate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "LR-AMEND",
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("1000"), BigDecimal.ONE,
                new BigDecimal("1000"), false, List.of(MaterialClass.GENERAL), FreightMode.FTL,
                UUID.randomUUID(), null, null);
    }

    private static Map<String, Object> changes(Object... namesAndValues) {
        Map<String, Object> changes = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            changes.put((String) namesAndValues[i], namesAndValues[i + 1]);
        }
        return changes;
    }

    private static String codeOf(Runnable action) {
        try {
            action.run();
        } catch (BusinessRuleViolationException e) {
            return e.code();
        }
        throw new AssertionError("accepted");
    }

    @Test
    @DisplayName("named fields are applied, blank text clears, and an unnamed field is kept")
    void appliesNamedFields() {
        ConsignmentParticulars was = new ConsignmentParticulars("Old", null, "REF-1", null, null, null, "keep me");

        ConsignmentAmendment.Result result = ConsignmentAmendment.judge(was, changes(
                "contactName", "  Asha  ", "consigneeReference", " ", "deliveryWindowFrom", "2026-12-01T06:00:00Z"));

        assertThat(result.refused()).isFalse();
        assertThat(result.changed()).isTrue();
        assertThat(result.next()).isEqualTo(new ConsignmentParticulars("Asha", null, null, null,
                Instant.parse("2026-12-01T06:00:00Z"), null, "keep me"));
        assertThat(result.fields()).extracting(FieldResult::outcome)
                .containsExactly(Outcome.APPLIED, Outcome.APPLIED, Outcome.APPLIED);
    }

    @Test
    @DisplayName("a field set to what it already holds is UNCHANGED, a null is kept, and nothing changes")
    void unchanged() {
        ConsignmentParticulars was = new ConsignmentParticulars("Asha", null, null, null, null, null, null);
        Map<String, Object> asked = new HashMap<>();
        asked.put("remarks", "");
        asked.put("contactName", "Asha");
        asked.put("contactPhone", null);

        ConsignmentAmendment.Result result = ConsignmentAmendment.judge(was, asked);

        assertThat(result.changed()).isFalse();
        assertThat(result.fields()).as("a null is not named at all").hasSize(2)
                .extracting(FieldResult::outcome).containsOnly(Outcome.UNCHANGED);
    }

    @Test
    @DisplayName("contents are fixed: naming weight, volume or lines is refused with its own code")
    void contentsAreFixed() {
        ConsignmentAmendment.Result result = ConsignmentAmendment.judge(ConsignmentParticulars.NONE,
                changes("remarks", "fine", "totalDeadWeightKg", 5, "lines", List.of()));

        assertThat(result.refused()).isTrue();
        assertThat(result.refusalCode()).isEqualTo(ConsignmentAmendment.CONTENTS_FIXED);
        assertThat(result.refusals()).containsOnlyKeys("totalDeadWeightKg", "lines");
        assertThat(result.fields().getFirst().outcome()).isEqualTo(Outcome.APPLIED);
    }

    @Test
    @DisplayName("every bad field is named with its own code")
    void everyBadFieldIsNamed() {
        ConsignmentAmendment.Result result = ConsignmentAmendment.judge(ConsignmentParticulars.NONE, changes(
                "contactPhone", "call me", "clientReference", "has space", "contactName", 42,
                "remarks", "x".repeat(501), "status", "DELIVERED", "deliveryWindowTo", "tomorrow"));

        assertThat(result.refusalCode()).isEqualTo("consignment-amendment-invalid");
        assertThat(result.fields()).extracting(FieldResult::code).containsExactly("contact-phone-invalid",
                "client-reference-invalid", "value-not-text", "value-too-long", "field-not-amendable",
                "delivery-window-invalid");
    }

    @Test
    @DisplayName("a window that ends before it starts is refused, judged against what is kept")
    void windowMustBeOrdered() {
        ConsignmentParticulars was = new ConsignmentParticulars(null, null, null, null,
                Instant.parse("2026-12-02T00:00:00Z"), null, null);

        ConsignmentAmendment.Result result = ConsignmentAmendment.judge(was,
                changes("deliveryWindowTo", "2026-12-01T00:00:00Z"));

        assertThat(result.fields()).singleElement()
                .satisfies(f -> assertThat(f.code()).isEqualTo("delivery-window-invalid"));
        assertThat(ConsignmentAmendment.judge(was, changes("deliveryWindowTo", "2026-12-03T00:00:00Z"))
                .refused()).isFalse();
    }

    @Test
    @DisplayName("an amendment naming nothing is a malformed request")
    void nothingNamed() {
        assertThatThrownBy(() -> ConsignmentAmendment.judge(ConsignmentParticulars.NONE, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        Map<String, Object> onlyNulls = new HashMap<>();
        onlyNulls.put("remarks", null);
        assertThatThrownBy(() -> ConsignmentAmendment.judge(ConsignmentParticulars.NONE, onlyNulls))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("particulars are amended only while planned, and contents are kept")
    void onlyWhilePlanned() {
        Consignment consignment = planned();
        ConsignmentParticulars next = new ConsignmentParticulars("Asha", null, null, "C-1", null, null, null);

        Consignment amended = consignment.amended(next, 3L);
        assertThat(amended.particulars()).isEqualTo(next);
        assertThat(amended.version()).isEqualTo(3L);
        assertThat(amended.totalDeadWeightKg()).isEqualByComparingTo("1000");

        Consignment onLoad = consignment.transitionTo(ConsignmentStatus.ASSIGNED_TO_LOAD);
        assertThat(codeOf(() -> onLoad.amended(next, 3L))).isEqualTo("consignment-not-amendable");
    }

    @Test
    @DisplayName("a consignment is cancelled on its own while planned or on a load, and not otherwise")
    void cancellable() {
        Consignment consignment = planned();
        consignment.requireCancellable();
        consignment.transitionTo(ConsignmentStatus.ASSIGNED_TO_LOAD).requireCancellable();

        assertThat(codeOf(() -> consignment.transitionTo(ConsignmentStatus.CANCELLED).requireCancellable()))
                .isEqualTo("consignment-already-cancelled");
        assertThat(codeOf(() -> consignment.transitionTo(ConsignmentStatus.ASSIGNED_TO_LOAD)
                .transitionTo(ConsignmentStatus.IN_TRANSIT).requireCancellable()))
                .isEqualTo("consignment-dispatched");

        Consignment unit = Consignment.fromHandlingUnit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "LR-HU", UUID.randomUUID(), UUID.randomUUID(), BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN);
        assertThat(codeOf(unit::requireCancellable)).isEqualTo("consignment-from-warehouse");

        Consignment.Split split = consignment.split(
                List.of(new Consignment.PartSpec(new BigDecimal("500"), new BigDecimal("0.5")),
                        new Consignment.PartSpec(new BigDecimal("500"), new BigDecimal("0.5"))),
                List.of(UUID.randomUUID(), UUID.randomUUID()), List.of("LR-AMEND-01", "LR-AMEND-02"));
        assertThat(codeOf(split.parent()::requireCancellable)).isEqualTo("consignment-split");
        assertThat(codeOf(split.parts().getFirst()::requireCancellable)).isEqualTo("consignment-is-split-part");
    }

    @Test
    @DisplayName("a part inherits the whole's particulars but not its client reference")
    void partsDoNotInheritTheClientReference() {
        Consignment whole = planned().amended(new ConsignmentParticulars("Asha", "+91 98200 12345", null,
                "CLIENT-9", null, null, "Fragile"), null);

        Consignment part = whole.split(
                List.of(new Consignment.PartSpec(new BigDecimal("500"), new BigDecimal("0.5")),
                        new Consignment.PartSpec(new BigDecimal("500"), new BigDecimal("0.5"))),
                List.of(UUID.randomUUID(), UUID.randomUUID()), List.of("LR-AMEND-01", "LR-AMEND-02"))
                .parts().getFirst();

        assertThat(part.particulars().clientReference()).isNull();
        assertThat(part.particulars().contactName()).isEqualTo("Asha");
        assertThat(part.particulars().remarks()).isEqualTo("Fragile");
    }
}
