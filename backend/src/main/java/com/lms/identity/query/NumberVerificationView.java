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
package com.lms.identity.query;

import java.util.UUID;

/**
 * Whether a number belongs to one of the tenant's series of a type, and if so
 * where it stands.
 *
 * @param belongs  false when no series of the type could have produced it; the
 *                 remaining fields are then null
 * @param status   ISSUED (handed out, and not given back since), RELEASED (given
 *                 back and not issued again) or NOT_ISSUED (the counter has not
 *                 reached it yet)
 */
public record NumberVerificationView(
        String number,
        String documentType,
        boolean belongs,
        UUID seriesId,
        String seriesCode,
        Integer financialYear,
        Long counter,
        String status) {
}
