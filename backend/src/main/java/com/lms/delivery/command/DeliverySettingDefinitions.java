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
package com.lms.delivery.command;

import java.math.BigDecimal;
import java.util.List;

import com.lms.settings.api.ScopeType;
import com.lms.settings.api.SettingContributor;
import com.lms.settings.api.SettingDefinition;
import com.lms.settings.api.SettingKey;
import org.springframework.stereotype.Component;

/**
 * What a proof of delivery must carry before it can be submitted. May be set
 * for the tenant, an organisational unit, a customer or a combination, and is
 * resolved for the consignment's own order. The defaults keep a submission
 * lenient, as proofs were before lines existed.
 */
@Component
public class DeliverySettingDefinitions implements SettingContributor {

    /** How far the quantity received may differ from the gross quantity, in percent. */
    public static final SettingKey<BigDecimal> POD_QUANTITY_TOLERANCE =
            SettingKey.decimal("delivery.pod.quantityTolerancePercent");
    /** Whether a proof must be fed with its lines before it is submitted. */
    public static final SettingKey<Boolean> POD_FEEDING_REQUIRED = SettingKey.bool("delivery.pod.feedingRequired");
    /** Whether a proof must carry a front document. */
    public static final SettingKey<Boolean> POD_FRONT_REQUIRED = SettingKey.bool("delivery.pod.frontDocumentRequired");
    /** Whether a proof must carry a back document. */
    public static final SettingKey<Boolean> POD_BACK_REQUIRED = SettingKey.bool("delivery.pod.backDocumentRequired");

    @Override
    public List<SettingDefinition> definitions() {
        return List.of(
                SettingDefinition.of(POD_QUANTITY_TOLERANCE, "POD quantity tolerance (%)",
                                "How far the quantity a proof says arrived (received, damaged and excess) may"
                                        + " differ from the gross quantity of its consignment, in percent of the"
                                        + " gross, before it is refused at submission. The default, 100, only"
                                        + " refuses a proof that claims more than double or nothing at all.")
                        .defaultValue(new BigDecimal("100")).range(0, 100).scale(2)
                        .alsoAt(ScopeType.ORG_UNIT, ScopeType.CUSTOMER, ScopeType.COMBINATION).build(),
                SettingDefinition.of(POD_FEEDING_REQUIRED, "POD lines required",
                                "Whether a proof of delivery must be fed with what was received against its"
                                        + " order lines before it can be submitted.")
                        .defaultValue(false)
                        .alsoAt(ScopeType.ORG_UNIT, ScopeType.CUSTOMER, ScopeType.COMBINATION).build(),
                SettingDefinition.of(POD_FRONT_REQUIRED, "POD front document required",
                                "Whether a proof of delivery must list a front document to be submitted.")
                        .defaultValue(false)
                        .alsoAt(ScopeType.ORG_UNIT, ScopeType.CUSTOMER, ScopeType.COMBINATION).build(),
                SettingDefinition.of(POD_BACK_REQUIRED, "POD back document required",
                                "Whether a proof of delivery must list a back document to be submitted.")
                        .defaultValue(false)
                        .alsoAt(ScopeType.ORG_UNIT, ScopeType.CUSTOMER, ScopeType.COMBINATION).build());
    }
}
