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
package com.lms.delivery.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A consignment's confirmed proof of delivery was reversed, through an
 * approved request, before any bill was settled or invoiced on it.
 *
 * <p>Planning puts the consignment back on its way if nothing else delivered
 * it; finance holds again the completed trips the proof had released. Carries
 * no reason: it is free text, kept on the approval and in the audit trail.
 */
public record DeliveryProofReversed(
        UUID tenantId,
        UUID consignmentId,
        UUID approvalId,
        Instant occurredAt) {
}
