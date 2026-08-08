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
 * Events published by the identity module.
 *
 * <p>This is the module's only importable surface. A peer declares
 * {@code allowedDependencies = "identity::events"} and gains these records
 * and nothing else -- not the aggregates, not the repositories, not the
 * command or query services.
 *
 * <p>Events are past-tense facts carrying identifiers and values only. Never
 * put an aggregate in an event: a shared mutable object graph across a
 * boundary defeats the purpose of having the boundary.
 */
@org.springframework.modulith.NamedInterface("events")
package com.lms.identity.events;
