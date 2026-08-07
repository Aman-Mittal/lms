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
package com.lms.order.api;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * What a line item is, for the purpose of deciding what it may travel with.
 *
 * <p>Implements the compatibility matrix of vision document 3.3: "System SHALL
 * reject the grouping of incompatible line items (e.g. toxic chemicals and
 * edible food items) into the same transport unit."
 */
public enum MaterialClass {

    GENERAL,
    FOOD,
    PHARMA,
    CHEMICAL,
    TOXIC,
    FLAMMABLE,
    FRAGILE,
    TEMPERATURE_CONTROLLED;

    /**
     * Classes that must never share a transport unit.
     *
     * <p>Declared symmetrically and verified as such by
     * {@code MaterialClassTest}. An asymmetric matrix would make the answer
     * depend on which item happened to be added to the load first, which is
     * exactly the kind of bug that only appears in production and only
     * sometimes.
     *
     * <p>The rules encoded here are the obvious contamination pairs. They are
     * not a substitute for a jurisdiction's dangerous goods segregation
     * regulations, which are considerably more intricate and vary by mode.
     */
    private static final Map<MaterialClass, Set<MaterialClass>> INCOMPATIBLE =
            new EnumMap<>(MaterialClass.class);

    static {
        // Contamination risk: anything ingested must not ride with poisons.
        forbid(FOOD, TOXIC);
        forbid(FOOD, CHEMICAL);
        forbid(FOOD, FLAMMABLE);
        forbid(PHARMA, TOXIC);
        forbid(PHARMA, CHEMICAL);
        // Reaction risk.
        forbid(FLAMMABLE, TOXIC);
    }

    private static void forbid(MaterialClass a, MaterialClass b) {
        INCOMPATIBLE.computeIfAbsent(a, k -> EnumSet.noneOf(MaterialClass.class)).add(b);
        INCOMPATIBLE.computeIfAbsent(b, k -> EnumSet.noneOf(MaterialClass.class)).add(a);
    }

    /**
     * Classes whose carriage is regulated as dangerous goods.
     *
     * <p>Used to enforce the other half of the 3.3 hazmat rule: a line in one
     * of these classes must carry a UN number, because without one there is
     * nothing to print on the transport document and no way to tell a driver
     * what they are carrying.
     */
    private static final Set<MaterialClass> DANGEROUS = EnumSet.of(CHEMICAL, TOXIC, FLAMMABLE);

    /** Whether these two classes may share a transport unit. */
    public boolean isCompatibleWith(MaterialClass other) {
        return !INCOMPATIBLE.getOrDefault(this, Set.of()).contains(other);
    }

    /** Whether carriage of this class is regulated as dangerous goods. */
    public boolean isDangerous() {
        return DANGEROUS.contains(this);
    }

    /** The classes this one may not travel with. */
    public Set<MaterialClass> incompatibleClasses() {
        return Set.copyOf(INCOMPATIBLE.getOrDefault(this, Set.of()));
    }
}
