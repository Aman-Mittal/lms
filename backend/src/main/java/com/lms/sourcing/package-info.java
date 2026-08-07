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
 * Vendor sourcing and load allocation (vision document 3.5).
 *
 * <p>Decides who moves a planned load: a contractual routing guide walked in
 * rank order, or a round-robin that spreads work across a month. Both are
 * implementations of one strategy interface, shaped so that a reverse auction
 * can be added later without any caller changing.
 *
 * <p>Reads loads through {@code planning::api} and vendor standing through
 * {@code masterdata::api}. Both are synchronous because both gate the offer:
 * a load cannot be offered before its weight is known, and an offer must never
 * go to a blacklisted vendor -- an event arriving a moment later would arrive
 * after the vendor had already been asked.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Sourcing & Allocation",
        allowedDependencies = {"planning::api", "masterdata::api"})
package com.lms.sourcing;
