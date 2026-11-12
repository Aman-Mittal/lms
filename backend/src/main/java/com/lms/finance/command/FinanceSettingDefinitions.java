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
import java.util.List;

import com.lms.settings.api.ScopeType;
import com.lms.settings.api.SettingContributor;
import com.lms.settings.api.SettingDefinition;
import com.lms.settings.api.SettingKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * How far a vendor's invoice may differ from the computed cost and still be
 * approved for payment: a percentage of the computed amount and an absolute
 * floor, the allowance being the greater. The system defaults are the
 * deployment's properties, as before profiles existed.
 *
 * <p>May be set for the tenant, an organisational unit and a vendor, and is
 * resolved for the bill's own unit and vendor: a vendor whose weighbridge
 * rounds differently can be given its own allowance without widening
 * everyone's.
 */
@Component
public class FinanceSettingDefinitions implements SettingContributor {

    public static final SettingKey<BigDecimal> MATCH_TOLERANCE_PERCENT =
            SettingKey.decimal("finance.match.tolerancePercent");
    public static final SettingKey<BigDecimal> MATCH_TOLERANCE_ABSOLUTE =
            SettingKey.decimal("finance.match.toleranceAbsolute");

    /**
     * The amount above which a manual charge needs an approval (when the
     * tenant's {@code MANUAL_CHARGE} policy asks for one), in the charge's
     * currency. Resolved for the record in hand: its unit, the customer of a
     * receivable or the vendor of a payable.
     */
    public static final SettingKey<BigDecimal> MANUAL_CHARGE_APPROVAL_THRESHOLD =
            SettingKey.decimal("finance.manualCharge.approvalThreshold");
    /** Days from the vendor's invoice date to its due date, for the vendor and the unit that owes it. */
    public static final SettingKey<Integer> PAYMENT_TERMS_DAYS = SettingKey.integer("finance.payables.paymentTermsDays");
    /** The most of a trip's freight that may be advanced to its vendor, in percent. */
    public static final SettingKey<BigDecimal> ADVANCE_MAX_PERCENT_OF_FREIGHT =
            SettingKey.decimal("finance.advance.maxPercentOfFreight");

    private final BigDecimal defaultPercent;
    private final BigDecimal defaultAbsolute;

    public FinanceSettingDefinitions(@Value("${lms.finance.match-tolerance-pct:2}") BigDecimal defaultPercent,
                                     @Value("${lms.finance.match-tolerance-absolute:100}")
                                     BigDecimal defaultAbsolute) {
        this.defaultPercent = defaultPercent;
        this.defaultAbsolute = defaultAbsolute;
    }

    @Override
    public List<SettingDefinition> definitions() {
        return List.of(
                SettingDefinition.of(MATCH_TOLERANCE_PERCENT, "Invoice match tolerance (%)",
                                "How far a vendor's claim may differ from the computed cost, as a"
                                        + " percentage of it, and still be approved for payment.")
                        .defaultValue(defaultPercent).range(0, 100).scale(2)
                        .alsoAt(ScopeType.ORG_UNIT, ScopeType.VENDOR, ScopeType.COMBINATION).build(),
                SettingDefinition.of(MATCH_TOLERANCE_ABSOLUTE, "Invoice match tolerance (amount)",
                                "The smallest difference, in the bill's currency, that is always"
                                        + " tolerated; the allowance is the greater of this and the"
                                        + " percentage.")
                        .defaultValue(defaultAbsolute).range(0, 100_000_000).scale(2)
                        .alsoAt(ScopeType.ORG_UNIT, ScopeType.VENDOR, ScopeType.COMBINATION).build(),
                SettingDefinition.of(MANUAL_CHARGE_APPROVAL_THRESHOLD, "Manual charge approval threshold",
                                "A manual charge whose amount is above this needs an approval before it takes"
                                        + " effect; one at or below it is in force at once.")
                        .defaultValue(new BigDecimal("10000.00")).range(0, 1_000_000_000).scale(2)
                        .alsoAt(ScopeType.ORG_UNIT, ScopeType.CUSTOMER, ScopeType.VENDOR, ScopeType.COMBINATION)
                        .build(),
                SettingDefinition.of(PAYMENT_TERMS_DAYS, "Vendor payment terms (days)",
                                "Days from a vendor's invoice date to the day it falls due.")
                        .defaultValue(30).range(0, 365)
                        .alsoAt(ScopeType.ORG_UNIT, ScopeType.VENDOR, ScopeType.COMBINATION).build(),
                SettingDefinition.of(ADVANCE_MAX_PERCENT_OF_FREIGHT, "Advance cap (% of freight)",
                                "The most of a trip's freight, in percent, that may be advanced to its"
                                        + " vendor, across every live advance on the trip.")
                        .defaultValue(new BigDecimal("70")).range(0, 100).scale(2)
                        .alsoAt(ScopeType.ORG_UNIT, ScopeType.VENDOR, ScopeType.COMBINATION).build());
    }
}
