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
package com.lms.planning.command;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.lms.order.api.FreightMode;
import com.lms.order.api.MaterialClass;
import com.lms.planning.command.domain.Consignment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The clash check a bulk assignment runs between members of one batch: a pair
 * that must not share a vehicle is found from either side, so both are named.
 */
class BatchCompatibilityTest {

    private static Consignment consignment(String ref, MaterialClass materialClass) {
        return Consignment.generate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), ref,
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("100"), BigDecimal.ONE,
                new BigDecimal("100"), materialClass == MaterialClass.TOXIC, Set.of(materialClass),
                FreightMode.FTL, UUID.randomUUID(), null, null);
    }

    @Test
    @DisplayName("two clashing members of a batch each name the other")
    void bothSidesOfAClashAreNamed() {
        Consignment food = consignment("LR-FOOD", MaterialClass.FOOD);
        Consignment poison = consignment("LR-TOX", MaterialClass.TOXIC);
        Consignment general = consignment("LR-GEN", MaterialClass.GENERAL);

        assertThat(PlanningCommandService.incompatibilities(food, List.of(poison, general), "in"))
                .containsExactly("FOOD in LR-FOOD must not travel with TOXIC in LR-TOX");
        assertThat(PlanningCommandService.incompatibilities(poison, List.of(food, general), "in"))
                .containsExactly("TOXIC in LR-TOX must not travel with FOOD in LR-FOOD");
        assertThat(PlanningCommandService.incompatibilities(general, List.of(food, poison), "in"))
                .isEmpty();
    }

    @Test
    @DisplayName("a clash with what is already aboard is worded as such")
    void clashWithWhatIsAboard() {
        Consignment aboard = consignment("LR-ON", MaterialClass.FOOD);
        Consignment arriving = consignment("LR-NEW", MaterialClass.CHEMICAL);

        assertThat(PlanningCommandService.incompatibilities(arriving, List.of(aboard), "already on"))
                .containsExactly("CHEMICAL in LR-NEW must not travel with FOOD already on LR-ON");
    }
}
