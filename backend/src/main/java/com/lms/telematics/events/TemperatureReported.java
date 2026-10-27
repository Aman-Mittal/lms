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
package com.lms.telematics.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A vehicle on a trip reported a cargo temperature. One per reading, so a
 * consumer can measure how long a reading stayed out of range; only pings that
 * carry a temperature produce one.
 *
 * @param recordedAt when the device recorded the reading, not when it arrived
 */
public record TemperatureReported(
        UUID tenantId,
        UUID tripId,
        String tripNo,
        BigDecimal celsius,
        Instant recordedAt,
        BigDecimal lat,
        BigDecimal lon,
        Instant publishedAt) {
}
