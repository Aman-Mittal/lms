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

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A driver as a dispatch screen shows them.
 *
 * <p>The licence expiry is on the row because it is a dispatch blocker, not a
 * detail: a driver whose licence lapses on Friday cannot take a Saturday
 * departure, and that has to be visible while there is still time to find
 * somebody else.
 */
public record DriverView(
        UUID id,
        String fullName,
        String phone,
        String licenceNo,
        String licenceClass,
        LocalDate licenceExpiresOn,
        boolean licenceExpired,
        boolean hazmatEndorsed,
        String status,
        int hosMinutesToday,
        UUID employerPartnerId,
        String employerPartnerName,
        Instant createdAt) {
}
