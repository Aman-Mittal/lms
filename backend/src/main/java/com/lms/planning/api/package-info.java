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
 * The synchronous surface of the planning module.
 *
 * <p>Sourcing and execution both need to read a load and to move it along its
 * lifecycle, and both need the answer now: an allocation cannot be offered
 * against a load whose weight arrives eventually, and a dispatch cannot be
 * blocked by a rule that has not run yet.
 *
 * <p>The state machine stays behind the port. Callers say what happened --
 * awarded, dispatched, completed -- and planning decides whether that is a
 * legal move for the load. A caller that could set the status directly would be
 * a second place the rules live.
 */
@org.springframework.modulith.NamedInterface("api")
package com.lms.planning.api;
