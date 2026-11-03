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
package com.lms.finance.query;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One weight band of a rate card.
 *
 * @param maxWeightKg the first weight the band does not cover; null for the open top band
 * @param rateBasis   {@code PER_KG} or {@code FLAT}
 */
public record TariffSlabView(
        UUID id,
        BigDecimal minWeightKg,
        BigDecimal maxWeightKg,
        String rateBasis,
        BigDecimal rateAmount) {
}
