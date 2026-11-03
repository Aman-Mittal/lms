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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.lms.planning.command.PlanningCommandService;
import com.lms.planning.command.domain.LoadUnit;
import com.lms.planning.web.PlanningController.BulkAssignConsignmentsRequest;
import com.lms.shared.error.BusinessRuleViolationException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The pieces that let one request put a whole vehicle's worth of consignments
 * on a load: the questions the load answers before any freight is weighed, the
 * limits on the request, and a refusal that names every offender at once.
 */
class BulkLoadBuildingTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void validation() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    private static LoadUnit load(boolean hazmatCertified) {
        return LoadUnit.open(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "LOAD-B",
                UUID.randomUUID(), UUID.randomUUID(), "RIGID_16T", hazmatCertified,
                new BigDecimal("30000"), new BigDecimal("90"));
    }

    @Test
    @DisplayName("a draft or planned load can still be built; a dispatched one cannot")
    void buildableUntilItLeaves() {
        LoadUnit draft = load(false);
        draft.requireBuildable();
        LoadUnit planned = draft.addConsignment(BigDecimal.TEN, BigDecimal.ONE, false)
                .transitionTo(LoadUnit.LoadStatus.PLANNED);
        planned.requireBuildable();

        LoadUnit dispatched = planned.transitionTo(LoadUnit.LoadStatus.AWARDED)
                .transitionTo(LoadUnit.LoadStatus.DISPATCHED);
        assertThatThrownBy(dispatched::requireBuildable)
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo("load-not-open"))
                .hasMessageContaining("DISPATCHED");
    }

    @Test
    @DisplayName("dangerous goods may board a certified vehicle, or a load with no vehicle yet")
    void dangerousGoodsFollowCertification() {
        assertThat(load(true).takesDangerousGoods()).isTrue();
        assertThat(load(false).takesDangerousGoods()).isFalse();

        LoadUnit noVehicle = LoadUnit.open(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "LOAD-N", UUID.randomUUID(), "RIGID_16T",
                new BigDecimal("10000"), new BigDecimal("40"));
        assertThat(noVehicle.takesDangerousGoods()).isTrue();
    }

    @Test
    @DisplayName("150 consignments' worth of freight is added to a load as one total")
    void aBatchIsAddedAsOneTotal() {
        BigDecimal each = new BigDecimal("150");
        BigDecimal total = each.multiply(BigDecimal.valueOf(150));

        LoadUnit built = load(false).addConsignment(total, new BigDecimal("75"), false);

        assertThat(built.plannedWeightKg()).isEqualByComparingTo("22500");
        assertThatThrownBy(() -> built.addConsignment(new BigDecimal("7501"), BigDecimal.ONE, false))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo("load-capacity-exceeded"));
    }

    @Test
    @DisplayName("a refusal of many keeps each offender's reason, in the order they were asked about")
    void refusalKeepsEveryOffenderInOrder() {
        Map<String, String> reasons = new LinkedHashMap<>();
        for (int i = 20; i > 0; i--) {
            reasons.put("LR-" + i, "consignment-not-assignable: LR-" + i + " is IN_TRANSIT");
        }

        BusinessRuleViolationException e = new BusinessRuleViolationException(
                "load-bulk-assignment-refused", "Load L cannot take 20 of the 150 consignments",
                reasons);

        assertThat(e.errors()).containsExactlyEntriesOf(reasons);
        assertThat(new ArrayList<>(e.errors().keySet())).startsWith("LR-20", "LR-19");
        assertThatThrownBy(() -> e.errors().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(new BusinessRuleViolationException("load-not-open", "closed").errors()).isEmpty();
    }

    @Test
    @DisplayName("a request names between one and five hundred consignments, none twice")
    void requestLimits() {
        assertThat(violations(ids(1))).isEmpty();
        assertThat(violations(ids(PlanningCommandService.MAX_BULK_ASSIGNMENT))).isEmpty();

        assertThat(violations(List.of())).containsExactly("consignmentIds");
        assertThat(violations(null)).containsExactly("consignmentIds");
        assertThat(violations(ids(PlanningCommandService.MAX_BULK_ASSIGNMENT + 1)))
                .containsExactly("consignmentIds");

        UUID twice = UUID.randomUUID();
        assertThat(violations(List.of(twice, UUID.randomUUID(), twice)))
                .containsExactly("eachConsignmentNamedOnce");

        List<UUID> withNull = new ArrayList<>(ids(2));
        withNull.add(null);
        assertThat(violations(withNull)).hasSize(1).allMatch(p -> p.startsWith("consignmentIds"));
    }

    private static List<UUID> ids(int count) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(UUID.randomUUID());
        }
        return ids;
    }

    private static List<String> violations(List<UUID> consignmentIds) {
        Set<ConstraintViolation<BulkAssignConsignmentsRequest>> found =
                validator.validate(new BulkAssignConsignmentsRequest(consignmentIds));
        return found.stream().map(v -> v.getPropertyPath().toString()).toList();
    }
}
