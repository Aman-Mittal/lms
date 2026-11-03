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
package com.lms.tracking.query;

import java.time.Instant;
import java.util.UUID;

/** A link as the desk sees it. There is no token here: it was shown once and is not kept. */
public record TrackingLinkView(
        UUID id,
        Instant createdAt,
        Instant expiresAt,
        Instant revokedAt,
        String status) {
}
