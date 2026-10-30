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
 * Event dead-letter.
 *
 * <p>Module events travel through Spring Modulith's JDBC publication registry.
 * A listener that fails leaves its publication incomplete, and the registry on
 * its own retries it only on restart, without limit or pause. This module
 * bounds that: failures are recorded as they happen, incomplete publications
 * are resubmitted on an exponential backoff, and one that has used up its
 * attempts is parked -- taken out of the live registry and kept, per tenant,
 * where an operator can replay or discard it.
 *
 * <p>Depends on nothing. It never imports an event type: the tenant a parked
 * event belongs to is read from the serialized payload's {@code tenantId}
 * field, which every event in the platform carries.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Event Dead-Letter",
        allowedDependencies = {})
package com.lms.events;
