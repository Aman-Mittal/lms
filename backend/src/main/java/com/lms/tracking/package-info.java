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
 * Customer tracking links.
 *
 * <p>A tokenised, read-only view of one consignment's progress for someone with
 * no account. The token is shown once and only its hash is kept; the public
 * route finds the tenant from the token through a single narrow SECURITY
 * DEFINER lookup and then reads under row-level security like any other
 * request.
 *
 * <p>Depends on nothing. That a consignment exists, its status, its trip and
 * its proof of delivery are read with lookups under row-level security rather
 * than by importing the modules that own them: the view needs a few columns,
 * not their types.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Tracking Links",
        allowedDependencies = {})
package com.lms.tracking;
