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
package com.lms.identity.access;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The attributes a user carries beyond their permissions and org unit.
 *
 * <p>Every one is optional, and absent means "not restricted along this
 * dimension". That convention is what lets the default policies be seeded
 * without changing what anybody could already do.
 *
 * @param region         an operating region code, e.g. {@code WEST}
 * @param freightModes   the modes the user works in; null for all of them
 * @param partnerId      set for an external portal user acting for a partner
 * @param approvalLimit  the most the user may approve; null for no limit
 */
public record SubjectAttributes(String region, List<String> freightModes, UUID partnerId,
                                BigDecimal approvalLimit) {

    /** The modes a user may be confined to. */
    public static final Set<String> FREIGHT_MODES = Set.of("FTL", "LTL", "RAIL", "WAREHOUSE");

    /** Access-token claim names. Short, because they travel on every request. */
    public static final String CLAIM_REGION = "region";
    public static final String CLAIM_MODES = "modes";
    public static final String CLAIM_PARTNER = "partner";
    /**
     * Carried as a string, not a JSON number: a number would round-trip
     * through a double in some clients, and a limit is money.
     */
    public static final String CLAIM_APPROVAL_LIMIT = "approvalLimit";

    public static final SubjectAttributes NONE = new SubjectAttributes(null, null, null, null);

    public SubjectAttributes {
        freightModes = freightModes == null ? null : List.copyOf(freightModes);
    }
}
