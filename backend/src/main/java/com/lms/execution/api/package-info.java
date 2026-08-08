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
 * The synchronous surface of the execution module.
 *
 * <p>Exists for telematics. A geofence crossing has to move the trip state
 * machine now -- a position stream that advanced trips eventually would report
 * a lorry arriving after it had already left, and would price detention from
 * timestamps that arrived in the wrong order.
 *
 * <p>The machine itself stays behind the port. Telematics reports what it
 * observed -- entered this terminal, left that one -- and execution decides
 * what that means for the trip. Telematics has no business knowing that
 * leaving the origin means DISPATCHED becomes IN_TRANSIT.
 */
@org.springframework.modulith.NamedInterface("api")
package com.lms.execution.api;
