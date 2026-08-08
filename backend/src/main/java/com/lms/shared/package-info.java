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
 * Cross-cutting infrastructure shared by every bounded context: geometry
 * ({@code shared.geo}), tenant context ({@code shared.tenant}), configuration
 * helpers ({@code shared.config}), and the common error model
 * ({@code shared.error}).
 *
 * <p>Declared {@link org.springframework.modulith.ApplicationModule.Type#OPEN}
 * so that its sub-packages are accessible to other modules. A closed module
 * exposes only its root package, which would force every utility into one flat
 * namespace purely to satisfy the boundary check.
 *
 * <p>This is a deliberate, narrow exemption. It is not licence to put domain
 * logic here: anything belonging to a specific context belongs in that context.
 * Code lands here only if it is genuinely used by several contexts and carries
 * no business meaning of its own.
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN,
        displayName = "Shared Kernel")
package com.lms.shared;
