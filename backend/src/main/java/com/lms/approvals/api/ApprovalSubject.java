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
package com.lms.approvals.api;

import java.util.Objects;
import java.util.UUID;

/**
 * What a request is about.
 *
 * @param type      one of {@link ApprovalSubjectTypes}
 * @param id        the subject's identifier in its owning module
 * @param orgUnitId the unit the subject belongs to, for attribute-based
 *                  access control on the decision; null when it has none
 */
public record ApprovalSubject(String type, UUID id, UUID orgUnitId) {

    public ApprovalSubject {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
    }
}
