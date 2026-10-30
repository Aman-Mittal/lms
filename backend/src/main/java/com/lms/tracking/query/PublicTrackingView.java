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

import java.math.BigDecimal;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What anyone holding a link may see. Deliberately small: no ids, no partner,
 * amount, vehicle or driver, and the position is rounded to about a kilometre.
 *
 * @param status one of PLANNED, IN_TRANSIT, DELIVERED, CANCELLED
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicTrackingView(
        String reference,
        String status,
        String originName,
        String destinationName,
        Instant requestedDeliveryAt,
        Instant deliveredAt,
        boolean proofRecorded,
        Position position) {

    /** The last reported position of the carrying trip, to two decimals. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Position(BigDecimal lat, BigDecimal lon, Instant recordedAt) {
    }
}
