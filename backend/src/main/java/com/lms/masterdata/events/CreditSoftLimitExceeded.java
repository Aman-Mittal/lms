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
package com.lms.masterdata.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An action took a customer's exposure above its soft limit. A warning, not a
 * refusal: the action went ahead.
 */
public record CreditSoftLimitExceeded(
        UUID tenantId,
        UUID partnerId,
        String event,
        String subjectKey,
        BigDecimal exposure,
        BigDecimal softLimit,
        BigDecimal creditLimit,
        Instant occurredAt) {
}
