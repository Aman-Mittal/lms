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
package com.lms.sourcing.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A load has been put to a vendor, who has until {@code respondsBy} to answer.
 *
 * <p>What a notification channel would listen for. It carries {@code tenantId}
 * because listeners may run on another thread after the publishing transaction
 * commits, and the tenant scope is a thread-local that does not follow a
 * hand-off.
 */
public record LoadOffered(
        UUID tenantId,
        UUID loadId,
        UUID allocationId,
        UUID offerId,
        UUID vendorPartnerId,
        int rankNo,
        Instant respondsBy,
        Instant occurredAt) {
}
