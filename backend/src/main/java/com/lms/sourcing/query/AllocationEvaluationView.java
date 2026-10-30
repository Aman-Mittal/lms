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
package com.lms.sourcing.query;

import java.util.List;
import java.util.UUID;

/**
 * What the rules would do to a load, without offering it to anyone.
 *
 * @param matchedRuleId   the rule that would fire, or null when none would
 * @param nextVendor      who would be offered the load first
 * @param candidateOrder  the vendors in the order they would be considered
 */
public record AllocationEvaluationView(
        UUID loadId,
        String loadNo,
        UUID matchedRuleId,
        String matchedRuleName,
        String action,
        AllocationRuleView.VendorRef nextVendor,
        List<AllocationRuleView.VendorRef> candidateOrder) {
}
