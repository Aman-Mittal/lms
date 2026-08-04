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
 * The synchronous, read-only surface of master data.
 *
 * <p>Events are the default way modules talk here, and they are the right
 * default. This package exists because a small number of checks genuinely
 * cannot be eventual: vision document 3.2.2 requires dispatch to be
 * <em>blocked</em> when a vehicle's documents have lapsed, and a decision that
 * blocks an operation has to be answered before the operation proceeds, not
 * some time afterwards.
 *
 * <p>It is deliberately narrow. Every type here is a read-only query or an
 * assertion — nothing in this package mutates master data, and peers still
 * cannot reach the aggregates, repositories or command services behind it.
 * A peer declares {@code allowedDependencies = "masterdata::api"} and gets
 * exactly this.
 *
 * <p>If you find yourself wanting to add a mutating operation here, you want an
 * event instead.
 */
@org.springframework.modulith.NamedInterface("api")
package com.lms.masterdata.api;
