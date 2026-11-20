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
 * A role as the roles screen shows it.
 *
 * @param standard    one of the five every tenant starts with (the Administrator among them)
 * @param permissions the permission codes it grants, sorted
 * @param userCount   how many users hold it, which decides whether it can be deleted
 */
public record RoleView(
        UUID id,
        String code,
        String name,
        boolean standard,
        long userCount,
        Long version,
        List<String> permissions) {
}
