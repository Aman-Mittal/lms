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
 */package com.lms.masterdata.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A vehicle as a planning or dispatch screen shows it.
 *
 * <p>{@code payloadCapacityKg} is gross less tare, computed in SQL. It is the
 * number planning actually uses -- what the lorry can carry once it has carried
 * itself -- and deriving it per row in the application would mean shipping both
 * weights to every client so each could subtract them the same way.
 *
 * <p>{@code earliestDocumentExpiry} is what turns a fleet list into a fleet
 * that can be dispatched. The hard stop of 3.2.2 refuses a vehicle whose
 * paperwork has lapsed; seeing that a fortnight beforehand is the difference
 * between arranging cover and finding out at the gate.
 */
public record VehicleView(
        UUID id,
        String registrationNo,
        String category,
        String vehicleType,
        String axleConfig,
        String status,
        BigDecimal grossWeightKg,
        BigDecimal tareWeightKg,
        BigDecimal payloadCapacityKg,
        BigDecimal maxVolumeM3,
        boolean hazmatCertified,
        boolean reeferCapable,
        UUID ownerPartnerId,
        String ownerPartnerName,
        LocalDate earliestDocumentExpiry,
        int expiredDocumentCount,
        Instant createdAt) {
}
