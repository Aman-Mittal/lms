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
import java.time.Instant;
import java.util.UUID;

/** A catalogue charge as the admin screen and the charge picker show it; {@code version} is the ETag. */
public record ChargeCatalogueView(
        UUID id,
        String code,
        String name,
        String basis,
        BigDecimal defaultRate,
        String currency,
        String direction,
        boolean active,
        Long version,
        Instant createdAt,
        Instant updatedAt) {
}
