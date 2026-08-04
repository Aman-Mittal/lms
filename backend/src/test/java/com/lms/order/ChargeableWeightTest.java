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
package com.lms.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.lms.order.command.domain.MaterialClass;
import com.lms.order.command.domain.OrderLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chargeable weight decides what a customer pays, so an error here is a
 * revenue error rather than a crash. Worth testing to the arithmetic.
 */
class ChargeableWeightTest {

    private static OrderLine line(String deadWeightKg, String l, String w, String h,
                                  String divisor, MaterialClass materialClass, String unCode) {
        return new OrderLine(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                "MAT-1", "Test material", materialClass, unCode,
                BigDecimal.ONE, "EA", new BigDecimal(deadWeightKg),
                l == null ? null : new BigDecimal(l),
                w == null ? null : new BigDecimal(w),
                h == null ? null : new BigDecimal(h),
                divisor == null ? null : new BigDecimal(divisor),
                UUID.randomUUID(), UUID.randomUUID(), Instant.now());
    }

    @Nested
    @DisplayName("chargeable weight")
    class Chargeable {

        @Test
        @DisplayName("uses dead weight for dense freight")
        void denseFreightBillsOnDeadWeight() {
            // 1 m3 of steel: 1,000,000 cm3 / 5000 = 200 kg volumetric, far below
            // the 2000 kg it actually weighs.
            OrderLine steel = line("2000", "1", "1", "1", null, MaterialClass.GENERAL, null);

            assertThat(steel.volumetricWeightKg()).isEqualByComparingTo("200.000");
            assertThat(steel.chargeableWeightKg()).isEqualByComparingTo("2000.000");
        }

        @Test
        @DisplayName("uses volumetric weight for light bulky freight")
        void bulkyFreightBillsOnVolume() {
            // 8 m3 of cushions weighing 50 kg: 8,000,000 / 5000 = 1600 kg.
            // Billing this on dead weight would give the vehicle away.
            OrderLine cushions = line("50", "2", "2", "2", null, MaterialClass.GENERAL, null);

            assertThat(cushions.volumetricWeightKg()).isEqualByComparingTo("1600.000");
            assertThat(cushions.chargeableWeightKg()).isEqualByComparingTo("1600.000");
        }

        @Test
        @DisplayName("honours a contract-specific divisor")
        void respectsPerLineDivisor() {
            // A divisor of 6000 is more generous to the customer than 5000.
            OrderLine generous = line("50", "2", "2", "2", "6000", MaterialClass.GENERAL, null);

            assertThat(generous.volumetricWeightKg()).isEqualByComparingTo("1333.333");
        }

        @Test
        @DisplayName("falls back to dead weight when dimensions are absent")
        void missingDimensionsMeanNoVolumetricWeight() {
            OrderLine noDimensions = line("500", null, null, null, null, MaterialClass.GENERAL, null);

            assertThat(noDimensions.volumeM3()).isEqualByComparingTo("0");
            assertThat(noDimensions.volumetricWeightKg()).isEqualByComparingTo("0");
            assertThat(noDimensions.chargeableWeightKg()).isEqualByComparingTo("500.000");
        }

        @Test
        @DisplayName("scales volume by quantity")
        void volumeScalesWithQuantity() {
            OrderLine tenBoxes = new OrderLine(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    1, "MAT", "Ten boxes", MaterialClass.GENERAL, null,
                    BigDecimal.TEN, "EA", new BigDecimal("100"),
                    BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, null,
                    UUID.randomUUID(), UUID.randomUUID(), Instant.now());

            assertThat(tenBoxes.volumeM3()).isEqualByComparingTo("10.000");
            assertThat(tenBoxes.volumetricWeightKg()).isEqualByComparingTo("2000.000");
        }

        @Test
        @DisplayName("treats equal dead and volumetric weight without preference")
        void equalWeightsAreStable() {
            // 1 m3 at divisor 5000 gives exactly 200 kg.
            OrderLine balanced = line("200", "1", "1", "1", null, MaterialClass.GENERAL, null);

            assertThat(balanced.chargeableWeightKg()).isEqualByComparingTo("200.000");
        }
    }

    @Nested
    @DisplayName("hazmat and compatibility")
    class Compatibility {

        @Test
        @DisplayName("a UN code marks the line as dangerous goods")
        void unCodeMarksHazmat() {
            assertThat(line("100", null, null, null, null, MaterialClass.FLAMMABLE, "UN1203").isHazmat())
                    .isTrue();
            assertThat(line("100", null, null, null, null, MaterialClass.GENERAL, null).isHazmat())
                    .isFalse();
            // Blank is not a UN code.
            assertThat(line("100", null, null, null, null, MaterialClass.GENERAL, "  ").isHazmat())
                    .isFalse();
        }

        @Test
        @DisplayName("food may not travel with toxic materials")
        void foodAndToxicAreIncompatible() {
            assertThat(MaterialClass.FOOD.isCompatibleWith(MaterialClass.TOXIC)).isFalse();
            assertThat(MaterialClass.TOXIC.isCompatibleWith(MaterialClass.FOOD)).isFalse();
        }

        @Test
        @DisplayName("the incompatibility matrix is symmetric")
        void matrixIsSymmetric() {
            // Asymmetry would make the answer depend on which item happened to
            // be added to the load first -- a bug that appears only sometimes.
            for (MaterialClass a : MaterialClass.values()) {
                for (MaterialClass b : MaterialClass.values()) {
                    assertThat(a.isCompatibleWith(b))
                            .as("%s vs %s should match %s vs %s", a, b, b, a)
                            .isEqualTo(b.isCompatibleWith(a));
                }
            }
        }

        @Test
        @DisplayName("every class is compatible with itself")
        void selfCompatible() {
            for (MaterialClass materialClass : MaterialClass.values()) {
                assertThat(materialClass.isCompatibleWith(materialClass))
                        .as("%s should travel with itself", materialClass)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("general cargo travels with anything")
        void generalIsUniversallyCompatible() {
            for (MaterialClass materialClass : MaterialClass.values()) {
                assertThat(MaterialClass.GENERAL.isCompatibleWith(materialClass)).isTrue();
            }
        }
    }
}
