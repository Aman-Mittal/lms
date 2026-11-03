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
package com.lms.multimodal.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A rail leg has been released: the train ran, the goods were unloaded at the
 * destination railhead and handed on. The carrier's work is done and it can be
 * paid.
 *
 * <p>Carries what a payable needs and nothing finance would have to ask
 * multimodal for: the carrier, the cost recorded on the leg when it was
 * planned (the expected charge the carrier's invoice is matched against), the
 * unit booked, and the organisational unit that owns the origin railhead, so
 * the approver's scope applies to the bill. {@code legId} is the idempotency
 * key: a redelivered event must raise no second bill.
 */
public record RailLegCompleted(
        UUID tenantId,
        UUID itineraryId,
        String itineraryNo,
        UUID legId,
        int legSeq,
        UUID orgUnitId,
        UUID carrierPartnerId,
        BigDecimal costAmount,
        String currency,
        String unitType,
        Integer unitCount,
        String bookingRef,
        String trainNo,
        Instant departedAt,
        Instant releasedAt) {
}
