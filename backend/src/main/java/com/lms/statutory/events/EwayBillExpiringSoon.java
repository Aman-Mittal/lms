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
package com.lms.statutory.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A live e-way bill came within its unit's threshold of lapsing. A fact for
 * anyone who wants to warn a dispatcher; raised once per bill (an extension
 * clears the flag, and the bill can be flagged again). A listener must be safe
 * to run twice.
 */
public record EwayBillExpiringSoon(
        UUID tenantId,
        UUID ewayBillId,
        UUID consignmentId,
        String billNo,
        Instant validUntil,
        Instant occurredAt) {
}
