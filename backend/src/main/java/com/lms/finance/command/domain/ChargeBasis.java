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

package com.lms.finance.command.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * What a catalogue charge is counted in, and so how a quantity and a rate make
 * an amount.
 *
 * <p>A flat charge is one thing, whatever the quantity a person types: the
 * quantity is taken as one, so "flat 500" cannot become 1 500 by typing 3. The
 * others multiply, rounded half-up to two decimals like every other line the
 * platform prices.
 */
public enum ChargeBasis {

    FLAT("flat"),
    PER_KG("kg"),
    PER_HOUR("hour"),
    PER_DAY("day"),
    PER_UNIT("unit");

    private final String unit;

    ChargeBasis(String unit) {
        this.unit = unit;
    }

    /** The unit a line of this basis shows. */
    public String unit() {
        return unit;
    }

    /** The quantity that is actually charged for the one asked for. */
    public BigDecimal chargedQuantity(BigDecimal quantity) {
        return this == FLAT ? BigDecimal.ONE.setScale(3) : quantity.setScale(3, RoundingMode.HALF_UP);
    }

    /** The amount of {@code quantity} at {@code rate}. */
    public BigDecimal amount(BigDecimal quantity, BigDecimal rate) {
        return chargedQuantity(quantity).multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }
}
