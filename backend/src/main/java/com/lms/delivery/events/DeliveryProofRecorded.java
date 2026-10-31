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
 * A consignment's delivery was proved with the consignee's code.
 *
 * <p>Carries no recipient name: a listener that needs who signed reads the
 * proof under its own permissions, and an event copied to an outside system
 * should not carry a person's name by default.
 */
public record DeliveryProofRecorded(
        UUID tenantId,
        UUID consignmentId,
        Instant deliveredAt,
        Instant occurredAt) {
}
