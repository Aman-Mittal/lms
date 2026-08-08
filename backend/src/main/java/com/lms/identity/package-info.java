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

/**
 * Identity, access and multi-tenancy (vision document 2.1 and 3.1).
 *
 * <p>Tenants, the organisational hierarchy, users, authentication, RBAC, and
 * the audit log.
 *
 * <p>{@code allowedDependencies} is empty by design. Identity is the base of
 * the platform: it answers "who is calling and what may they see", and it must
 * not need to ask any other context anything to do so. Every other module
 * depends on the tenant scope it establishes, so a dependency in the other
 * direction would be a cycle waiting to happen.
 *
 * <p>The {@code shared} kernel is available to every module and does not need
 * declaring here; it is registered via {@code @Modulithic(sharedModules)}.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Identity & Access",
        allowedDependencies = {})
package com.lms.identity;
