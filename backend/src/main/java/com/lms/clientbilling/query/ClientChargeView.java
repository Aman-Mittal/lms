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
package com.lms.clientbilling.query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One captured charge.
 *
 * @param late true when the charge is billed in a later month than it
 *             occurred in, because its own month had already been invoiced
 */
public record ClientChargeView(
        UUID id,
        UUID clientPartnerId,
        String serviceType,
        String serviceCode,
        BigDecimal quantity,
        String unit,
        LocalDate occurredOn,
        LocalDate billingPeriod,
        boolean late,
        String reference,
        String sourceKey,
        UUID tripId,
        UUID invoiceId) {
}
