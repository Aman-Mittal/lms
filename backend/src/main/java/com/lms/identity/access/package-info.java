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
 * What other modules may know about attribute-based access control.
 *
 * <p>Two things, and deliberately no more. {@link com.lms.identity.access.AccessSubject}
 * is who is calling, as attributes -- a read side needs it to narrow a listing
 * for a portal user. {@link com.lms.identity.access.ResourceAttributeProvider}
 * is how a module tells the decision point what one of its records looks like
 * without the decision point reading that module's tables.
 *
 * <p>The decision point itself is not here. Services reach it by bean name
 * from {@code @PreAuthorize} -- {@code @abac.permits(...)} -- so no module
 * compiles against the engine, and the engine can change without them.
 */
@org.springframework.modulith.NamedInterface("access")
package com.lms.identity.access;
