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
 * Proof of delivery and claims.
 *
 * <p>A consignee confirms a delivery with a one-time code, and the delivery
 * desk records who received it, when, where and a reference to the photo. A
 * claim -- damage, shortage, loss or delay -- is raised against a consignment
 * and decided through a short lifecycle.
 *
 * <p>Depends on nothing. That a consignment exists, for the caller's tenant,
 * and whether it is in transit, is read with a lookup under row-level security
 * rather than by importing the module that owns it: all that is needed is a
 * status, not a type.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Delivery and Claims",
        allowedDependencies = {})
package com.lms.delivery;
