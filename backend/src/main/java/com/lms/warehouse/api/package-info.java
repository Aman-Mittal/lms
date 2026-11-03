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
 * The synchronous, read-only surface of warehousing.
 *
 * <p>Exists for one reader today: client billing, which charges a 3PL client
 * for the stock it keeps in the tenant's warehouses and needs the figure from
 * the ledger rather than from whoever types it in. Nothing here mutates stock
 * or exposes the aggregates, repositories or command services behind it; a
 * peer declares {@code allowedDependencies = "warehouse::api"} and gets
 * exactly this.
 *
 * <p>If you find yourself wanting to add a mutating operation here, you want an
 * event instead.
 */
@org.springframework.modulith.NamedInterface("api")
package com.lms.warehouse.api;
