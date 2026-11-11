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

import java.util.List;
import java.util.UUID;

/**
 * The signed-in caller, as their own profile page shows them.
 *
 * <p>The identity, tenant and unit come from the database where the user's
 * row exists and from the access token otherwise; permissions are always the
 * token's, because they are what this session may actually do.
 *
 * @param roles the codes of the roles the user holds
 */
public record MeView(
        UUID userId,
        String email,
        String fullName,
        UUID tenantId,
        String tenantCode,
        String tenantName,
        UUID orgUnitId,
        String orgPath,
        String orgUnitName,
        List<String> roles,
        List<String> permissions) {
}
