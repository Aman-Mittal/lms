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
package com.lms.finance.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A completed trip has been priced.
 *
 * <p>Carries the computed amount and whether it could be priced at all, which
 * is what a control tower or a notification engine needs in order to chase the
 * gap. An unpriced bill is not a failure of the platform -- it means no rate
 * card covers that lane on that date, and somebody has to negotiate one.
 */
public record FreightBillRaised(
        UUID tenantId,
        UUID billId,
        String billNo,
        UUID tripId,
        UUID vendorPartnerId,
        BigDecimal computedAmount,
        boolean priced,
        Instant occurredAt) {
}
